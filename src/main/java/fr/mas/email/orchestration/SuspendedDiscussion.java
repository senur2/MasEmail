package fr.mas.email.orchestration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import fr.mas.email.contract.*;
import fr.mas.email.result.DiscussionPlan;
import java.util.List;

/** Stored in SharedWorld.detail without changing World permissions or its storage format.
 * initialResultJson preserves the initial typed business message; direct DiscussionPlan results
 * preserve their discussion message instead. No polymorphic Jackson deserialization is enabled.
 */
public record SuspendedDiscussion(AgentMessage<DiscussionPlan<?>> request, String initialResultJson,
                                  DiscussionResume resume, Context context) {
    public record Context(EmailAnalysis analysis, RouteDecision route, ResponsePlan plan, List<String> keys,
                          CorrectionPlan correctionPlan) {
        public Context(EmailAnalysis analysis, RouteDecision route, ResponsePlan plan, List<String> keys) {
            this(analysis, route, plan, keys, null);
        }

        @JsonIgnore
        public boolean isMemoryCorrection() {
            return correctionPlan != null && correctionPlan.targetAgent() == CorrectionPlan.Target.ORCHESTRATOR;
        }

        public Context {
            Checks.required(analysis, "analysis");
            Checks.required(route, "route");
            keys = Checks.texts(keys, "keys");
            Checks.rule(route.emailId().equals(analysis.emailId()));
            if (correctionPlan != null && correctionPlan.targetAgent() == CorrectionPlan.Target.ORCHESTRATOR) {
                Checks.rule(plan == null && route.decisionStatus() == RouteDecision.DecisionStatus.SELECTED
                        && !route.requiresUserConfirmation()
                        && keys.equals(correctionPlan.memoryProposals().stream().map(KnowledgeProposal::key).toList()));
            }
            if (plan != null) {
                Checks.rule(plan.requiresClarification() && plan.emailId().equals(analysis.emailId())
                        && plan.agentId().equals(route.selectedAgentId()));
            }
        }
    }

    public SuspendedDiscussion(AgentMessage<DiscussionPlan<?>> request, String initialResultJson, DiscussionResume resume) {
        this(request, initialResultJson, resume, null);
    }
    public SuspendedDiscussion {
        Checks.required(request, "request"); Contract.DISCUSSION_PLAN.validate(request);
        initialResultJson = Checks.text(initialResultJson, "initialResultJson");
        Checks.rule(request.metadata().correlationId().equals(request.payload().task().correlationId()));
        if (resume != null) {
            Checks.rule(resume.task().taskId().equals(request.payload().task().taskId())
                    && resume.task().correlationId().equals(request.payload().task().correlationId()));
        }
    }
}
