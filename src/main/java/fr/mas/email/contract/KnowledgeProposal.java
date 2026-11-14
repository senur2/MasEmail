package fr.mas.email.contract;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A suggested fact. The discussion protocol's MemoryProposal enum describes the UI action. */
@JsonPropertyOrder({"suggestedScope", "key", "value"})
public record KnowledgeProposal(Scope suggestedScope, String key, String value) {
    public enum Scope { COMMON, AGENT_SPECIFIC }

    public KnowledgeProposal {
        Checks.required(suggestedScope, "suggestedScope");
        key = Checks.text(key, "key");
        Checks.rule(key.matches("[a-z][a-z0-9_]*"), "Invalid memory proposal key");
        value = Checks.text(value, "value");
    }
}
