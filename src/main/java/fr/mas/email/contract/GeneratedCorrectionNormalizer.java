package fr.mas.email.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;

/** Bounded formatting repair at the LLM boundary, before strict contract validation. */
final class GeneratedCorrectionNormalizer {
    private GeneratedCorrectionNormalizer() {}

    static void normalize(JsonNode value) {
        if (!(value instanceof ObjectNode plan)) return;
        for (var proposal : plan.path("memoryProposals")) {
            if (proposal instanceof ObjectNode object && object.path("key").isTextual()) {
                String key = object.path("key").textValue();
                if (key.matches("[a-zA-Z][a-zA-Z0-9_]*")) {
                    object.put("key", key.toLowerCase(Locale.ROOT));
                }
            }
        }
        // These labels describe the validation workflow. Never infer routing, scope, facts, or consent.
        if ("ORCHESTRATOR".equals(plan.path("targetAgent").asText())
                && "UPDATE_KNOWLEDGE".equals(plan.path("correctionType").asText())
                && plan.path("requiresUserValidation").isBoolean()
                && plan.path("requiresUserValidation").booleanValue()
                && plan.path("memoryProposals").isArray() && !plan.path("memoryProposals").isEmpty()) {
            if (!plan.has("instruction")) {
                plan.put("instruction", "Présenter les informations proposées à l’utilisateur pour validation.");
            }
            if (!plan.has("reason")) {
                plan.put("reason", "Une proposition de connaissance nécessite une validation utilisateur.");
            }
        }
    }
}
