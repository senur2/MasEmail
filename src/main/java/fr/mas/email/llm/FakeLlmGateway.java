package fr.mas.email.llm;

import fr.mas.email.contract.Checks;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.result.*;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeoutException;

/** Deterministic sequential test adapter: no network, threads, waits, model or retry. */
public final class FakeLlmGateway implements LlmGateway {
    private final JsonCodec json;
    private final Deque<Outcome> outcomes = new ArrayDeque<>();
    private int calls;

    private sealed interface Outcome {}
    private record Json(String value) implements Outcome {}
    private record Failure(Exception cause) implements Outcome {}

    public FakeLlmGateway(JsonCodec json) {
        this.json = Checks.required(json, "json");
    }

    public FakeLlmGateway enqueueJson(String payload) {
        outcomes.addLast(new Json(Checks.required(payload, "payload")));
        return this;
    }

    public FakeLlmGateway enqueueFailure(Exception failure) {
        outcomes.addLast(new Failure(Checks.required(failure, "failure")));
        return this;
    }

    public FakeLlmGateway enqueueTimeout() {
        return enqueueFailure(new TimeoutException());
    }

    public int callCount() {
        return calls;
    }

    @Override
    public <T> AgentResult<T> generate(LlmRequest<T> request, TaskContext task, String component) {
        Checks.required(request, "request");
        Checks.required(task, "task");
        Checks.text(component, "component");
        calls++;
        Outcome outcome = outcomes.pollFirst();
        if (outcome == null) return failure(Errors.create(ErrorCode.LLM_UNAVAILABLE, component, task), request, component);
        try {
            return switch (outcome) {
                case Json response -> response.value().isBlank()
                        ? failure(Errors.create(ErrorCode.EMPTY_RESULT, component, task), request, component)
                        : new Success<>(json.readGenerated(response.value(), request.expectedContract().payloadType()),
                                response.value());
                case Failure failure -> throw failure.cause();
            };
        } catch (fr.mas.email.contract.ContractException | fr.mas.email.contract.InvalidOutputException failure) {
            var code = failure instanceof fr.mas.email.contract.ContractException
                    ? ErrorCode.INVALID_JSON : ErrorCode.INVALID_OUTPUT;
            return failure(Errors.validation(code, component, task, failure,
                    outcome instanceof Json response ? response.value() : null), request, component);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return failure(Errors.create(ErrorCode.INTERNAL_ERROR, component, task), request, component);
        } catch (IOException failure) {
            return failure(Errors.create(ErrorCode.LLM_UNAVAILABLE, component, task), request, component);
        } catch (Exception failure) {
            return failure(Errors.from(failure, component, task), request, component);
        }
    }

    private static <T> ErrorPlan<T> failure(ErrorPlan<T> error, LlmRequest<T> request, String agentId) {
        return Errors.forAgent(error, agentId, request.expectedContract().stage(),
                "FakeLlmGateway", request.expectedContract().messageType());
    }
}
