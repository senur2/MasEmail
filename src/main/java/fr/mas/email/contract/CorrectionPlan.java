package fr.mas.email.contract;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

@JsonPropertyOrder({"targetAgent", "correctionType", "requiresUserValidation", "instruction", "memoryProposals", "reason"})
public record CorrectionPlan(Target targetAgent, Type correctionType, boolean requiresUserValidation,
                             String instruction, List<KnowledgeProposal> memoryProposals, String reason) {
    public enum Target { ORCHESTRATOR, PARSER_AGENT, DECISION_AGENT, WRITER_AGENT, SCRIBE_AGENT }
    public enum Type { ADJUST_STYLE, REVISE_PLAN, REROUTE_DOMAIN, REPARSE_EMAIL, UPDATE_KNOWLEDGE }

    public CorrectionPlan {
        Checks.required(targetAgent, "targetAgent");
        Checks.required(correctionType, "correctionType");
        instruction = Checks.text(instruction, "instruction");
        reason = Checks.text(reason, "reason");
        memoryProposals = Checks.list(memoryProposals, "memoryProposals");
        Type expected = switch (targetAgent) {
            case ORCHESTRATOR -> Type.UPDATE_KNOWLEDGE;
            case PARSER_AGENT -> Type.REPARSE_EMAIL;
            case DECISION_AGENT -> Type.REROUTE_DOMAIN;
            case WRITER_AGENT -> Type.REVISE_PLAN;
            case SCRIBE_AGENT -> Type.ADJUST_STYLE;
        };
        Checks.rule(correctionType == expected, "Correction type does not match target");
        boolean updatesKnowledge = targetAgent == Target.ORCHESTRATOR;
        Checks.rule(updatesKnowledge ? requiresUserValidation && !memoryProposals.isEmpty()
                        : !requiresUserValidation && memoryProposals.isEmpty(),
                "Only knowledge updates require user validation and memory proposals");
        Checks.rule(memoryProposals.stream().map(KnowledgeProposal::key).distinct().count() == memoryProposals.size(),
                "Duplicate memory proposal keys");
    }
}
