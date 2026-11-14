package fr.mas.email.orchestration;

import fr.mas.email.contract.*;
import java.util.UUID;

/** Current validated stage outputs and correction budget for one email. */
public record ExecutionTrace(UUID executionId, String email, int corrections, EmailAnalysis emailAnalysis,
                             RouteDecision routeDecision, ResponsePlan responsePlan, DraftResponse draftResponse) {
    public ExecutionTrace {
        Checks.required(executionId, "executionId");
        Checks.text(email, "email");
        Checks.rule(corrections >= 0 && corrections <= OrchestratorAgent.MAX_CORRECTIONS);
    }

    ExecutionTrace countCorrection() {
        return new ExecutionTrace(executionId, email, corrections + 1, emailAnalysis, routeDecision,
                responsePlan, draftResponse);
    }

    ExecutionTrace record(Object output) {
        return switch (output) {
            case EmailAnalysis analysis -> new ExecutionTrace(executionId, email, corrections, analysis, null, null, null);
            case RouteDecision route -> new ExecutionTrace(executionId, email, corrections, emailAnalysis, route, null, null);
            case ResponsePlan plan -> new ExecutionTrace(executionId, email, corrections, emailAnalysis, routeDecision, plan, null);
            case DraftResponse draft -> new ExecutionTrace(executionId, email, corrections, emailAnalysis, routeDecision,
                    responsePlan, draft);
            default -> this;
        };
    }
}
