package fr.mas.email.contract;

import java.util.List;

/** Routing proposal; availability and authorization additionally require the Java registry. */
public record RouteDecision(String emailId, String selectedAgentId, List<String> candidateAgents,
                            DecisionStatus decisionStatus, List<String> reasons, Double confidence,
                            boolean requiresUserConfirmation, String questionToUser) {
    public enum DecisionStatus { SELECTED, NEEDS_CLARIFICATION }

    public RouteDecision {
        emailId = Checks.text(emailId, "emailId");
        var content = new Content(selectedAgentId, candidateAgents, decisionStatus, reasons,
                confidence, requiresUserConfirmation, questionToUser);
        candidateAgents = content.candidateAgents(); reasons = content.reasons();
    }

    public record Content(String selectedAgentId, List<String> candidateAgents, DecisionStatus decisionStatus,
                          List<String> reasons, Double confidence, boolean requiresUserConfirmation,
                          String questionToUser) {
        public Content {
            selectedAgentId = Checks.nullableText(selectedAgentId, "selectedAgentId");
            candidateAgents = Checks.texts(candidateAgents, "candidateAgents");
            Checks.required(decisionStatus, "decisionStatus"); reasons = Checks.texts(reasons, "reasons");
            questionToUser = Checks.nullableText(questionToUser, "questionToUser");
            Checks.rule(!reasons.isEmpty() && confidence == null);
            Checks.rule(requiresUserConfirmation == (questionToUser != null));
            if (decisionStatus == DecisionStatus.SELECTED) {
                Checks.rule(selectedAgentId != null && candidateAgents.contains(selectedAgentId));
            } else {
                Checks.rule(selectedAgentId == null && requiresUserConfirmation);
            }
        }

        public RouteDecision enrich(String emailId) {
            return new RouteDecision(emailId, selectedAgentId, candidateAgents, decisionStatus,
                    reasons, confidence, requiresUserConfirmation, questionToUser);
        }
    }
}
