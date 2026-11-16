package fr.mas.email.agent;

import fr.mas.email.contract.Checks;
import fr.mas.email.contract.Contract;
import fr.mas.email.contract.DraftContent;
import fr.mas.email.contract.DraftResponse;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.ResponsePlan;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.orchestration.DraftResponseValidator;
import fr.mas.email.result.DiscussionPlan;
import fr.mas.email.result.AgentResult;
import fr.mas.email.result.CorrectionRequest;
import fr.mas.email.result.TaskContext;
import fr.mas.email.result.ErrorCode;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Errors;
import fr.mas.email.result.Success;
import fr.mas.email.world.MiniWorld;
import fr.mas.email.world.WorldRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Turns a ready response plan into a draft tied to that plan. */
public final class ScribeAgent extends ConfiguredAgent<ScribeInput, DraftResponse> {
    ScribeAgent(AgentConfig config, LlmGateway llm, WorldRepository<MiniWorld> world,
              AgentBehavior<ScribeInput, DraftResponse> behavior) {
        super(config, llm, world, readyPlansOnly(behavior));
    }

    public AgentResult<DraftResponse> execute(ResponsePlan plan, TaskContext task) {
        return execute(new ScribeInput(plan), task);
    }

    static AgentBehavior<ScribeInput, DraftResponse> drafting(Duration timeout) {
        Checks.required(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        String prompt = loadPrompt();
        var json = new JsonCodec();
        return (input, context) -> {
            var plan = input.responsePlan();
            var payload = input.correctionPlan() == null && input.threadHistory() == null ? json.write(plan) : json.write(input);
            var request = new LlmRequest<>(prompt, payload, Contract.DRAFT_CONTENT, timeout);
            return switch (context.llm().generate(request, context.task(), context.configuration().agentId())) {
                case Success<DraftContent> success -> {
                    try {
                        var draft = success.value().enrich(plan);
                        DraftResponseValidator.validateStructure(plan, draft);
                        yield new Success<>(draft, success.rawOutput());
                    } catch (fr.mas.email.contract.InvalidOutputException e) {
                        yield Errors.validation(ErrorCode.INVALID_OUTPUT, "ScribeAgent", context.task(), e, success.rawOutput());
                    }
                }
                case ErrorPlan<DraftContent> error -> error.retype();
                case CorrectionRequest<DraftContent> correction -> correction.retype();
                case null -> Errors.create(ErrorCode.NO_RESULT, "ScribeAgent", context.task());
                case DiscussionPlan<DraftContent> ignored ->
                        Errors.create(ErrorCode.INVALID_OUTPUT, "ScribeAgent", context.task());
            };
        };
    }

    private static String loadPrompt() {
        try (var resource = ScribeAgent.class.getResourceAsStream("/prompts/ScribePrompt.md")) {
            if (resource == null) throw new IllegalStateException("Missing ScribeAgent prompt");
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load ScribeAgent prompt", e);
        }
    }

    private static AgentBehavior<ScribeInput, DraftResponse> readyPlansOnly(AgentBehavior<ScribeInput, DraftResponse> behavior) {
        Checks.required(behavior, "behavior");
        return (input, context) -> {
            Checks.rule(!input.responsePlan().requiresClarification());
            return behavior.process(input, context);
        };
    }
}
