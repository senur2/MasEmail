package fr.mas.email.agent;

import fr.mas.email.contract.Checks;
import java.util.List;

/** Desires and constraints are configured in Java, not invented by generated payloads. */
public record AgentConfig(String agentId, AgentRole role, String specialization,
                          List<String> objectives, List<String> constraints) {
    public AgentConfig {
        agentId = Checks.text(agentId, "agentId");
        if (!agentId.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("agentId must be a safe identifier of at most 64 characters");
        }
        Checks.required(role, "role");
        specialization = Checks.text(specialization, "specialization");
        objectives = Checks.texts(objectives, "objectives");
        constraints = Checks.texts(constraints, "constraints");
    }
}
