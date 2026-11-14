package fr.mas.email.agent;

import fr.mas.email.contract.Checks;
import fr.mas.email.contract.Contract;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.RouteDecision;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.orchestration.RouteDecisionValidator;
import fr.mas.email.result.DiscussionPlan;
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

/** Proposes a Writer from the profiles authorized for the current task. */
public final class DecisionAgent extends ConfiguredAgent<DecisionInput, RouteDecision> {
    DecisionAgent(AgentConfig config, LlmGateway llm, WorldRepository<MiniWorld> world,
                  AgentBehavior<DecisionInput, RouteDecision> behavior) {
        super(config, llm, world, behavior);
    }

    static AgentBehavior<DecisionInput, RouteDecision> routing(Duration timeout) {
        Checks.required(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        String prompt = loadPrompt();
        var json = new JsonCodec();
        return (input, context) -> {
            var request = new LlmRequest<>(prompt, json.write(input), Contract.ROUTE_DECISION_CONTENT, timeout);
            var result = context.llm().generate(request, context.task(), context.configuration().agentId());
            return switch (result) {
                case Success<RouteDecision.Content> success -> {
                    try {
                        var content = success.value();
                        // The V1.2 prompt uses NEEDS_CLARIFICATION for all questions.
                        Checks.rule(content.decisionStatus() != RouteDecision.DecisionStatus.SELECTED
                                || !content.requiresUserConfirmation());
                        String emailId = input.analysis().emailId();
                        var decision = content.enrich(emailId);
                        new RouteDecisionValidator(emailId, input.availableWriters()).validate(decision);
                        yield new Success<>(decision, success.rawOutput());
                    } catch (fr.mas.email.contract.InvalidOutputException e) {
                        yield Errors.validation(ErrorCode.INVALID_OUTPUT, "DecisionAgent", context.task(), e, success.rawOutput());
                    }
                }
                case ErrorPlan<RouteDecision.Content> error -> error.retype();
                case fr.mas.email.result.CorrectionRequest<RouteDecision.Content> correction -> correction.retype();
                case null -> Errors.create(ErrorCode.NO_RESULT, "DecisionAgent", context.task());
                case DiscussionPlan<RouteDecision.Content> ignored ->
                        Errors.create(ErrorCode.INVALID_OUTPUT, "DecisionAgent", context.task());
            };
        };
    }

    private static String loadPrompt() {
        try (var resource = DecisionAgent.class.getResourceAsStream("/prompts/DecisionAgent.txt")) {
            if (resource == null) throw new IllegalStateException("Missing DecisionAgent prompt");
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load DecisionAgent prompt", e);
        }
    }
}
