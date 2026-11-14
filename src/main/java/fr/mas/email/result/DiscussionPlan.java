package fr.mas.email.result;

import fr.mas.email.contract.*;
import java.util.List;

/** Shared Java normalization of questions from the initial business payload. */
public record DiscussionPlan<T>(Action action, TaskContext task, List<String> questions,
                                String reason, MemoryProposal memoryProposal) implements AgentResult<T> {
    public enum Action { ASK_INFORMATION }

    public DiscussionPlan {
        Checks.required(action, "action"); Checks.required(task, "task");
        questions = Checks.texts(questions, "questions"); Checks.rule(!questions.isEmpty());
        reason = Checks.text(reason, "reason"); Checks.required(memoryProposal, "memoryProposal");
    }

    public DiscussionPlan(List<String> questions, String reason, MemoryProposal memoryProposal, TaskContext task) {
        this(Action.ASK_INFORMATION, task, questions, reason, memoryProposal);
    }

    public DiscussionPlan(String question, String reason, TaskContext task) {
        this(List.of(question), reason, MemoryProposal.NONE, task);
    }

    public <R> DiscussionPlan<R> retype() {
        return new DiscussionPlan<>(action, task, questions, reason, memoryProposal);
    }

    /** Converts explicit clarification fields from business plans without a second LLM call. */
    public static <T> AgentResult<T> normalize(T value, TaskContext task) {
        return switch (value) {
            case RouteDecision route when route.requiresUserConfirmation() ->
                    new DiscussionPlan<>(List.of(route.questionToUser()), String.join("; ", route.reasons()),
                            MemoryProposal.NONE, task);
            case ResponsePlan plan when plan.requiresClarification() ->
                    new DiscussionPlan<>(plan.missingInformation(), "Information required to complete the response plan",
                            MemoryProposal.NONE, task);
            case CorrectionPlan correction when correction.targetAgent() == CorrectionPlan.Target.ORCHESTRATOR ->
                    new DiscussionPlan<>(correction.memoryProposals().stream()
                            .map(proposal -> proposal.key() + " : " + proposal.value()).toList(),
                            correction.reason(), MemoryProposal.ASK_SAVE_SCOPE, task);
            default -> new Success<>(value);
        };
    }
}
