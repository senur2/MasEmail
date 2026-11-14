package fr.mas.email.world;

import fr.mas.email.contract.Checks;
import java.util.Map;

/** Private knowledge, separate from shared beliefs and workflow state. */
public record MiniWorld(String ownerAgentId, Map<String, String> knowledge) {
    public MiniWorld {
        ownerAgentId = Checks.text(ownerAgentId, "ownerAgentId");
        knowledge = Map.copyOf(Checks.required(knowledge, "knowledge"));
        knowledge.forEach((key, value) -> {
            Checks.text(key, "knowledge key");
            Checks.text(value, "knowledge value");
        });
    }

    public static MiniWorld empty(String agentId) {
        return new MiniWorld(agentId, Map.of());
    }
}
