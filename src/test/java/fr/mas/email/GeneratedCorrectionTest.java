package fr.mas.email;

import fr.mas.email.contract.*;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class GeneratedCorrectionTest {
    public static final String REPORTED_OUTPUT = """
            {
              "targetAgent": "ORCHESTRATOR",
              "correctionType": "UPDATE_KNOWLEDGE",
              "requiresUserValidation": true,
              "memoryProposals": [
                {
                  "suggestedScope": "AGENT_SPECIFIC",
                  "key": "date_publication_notes_INF312",
                  "value": "Les notes du cours INF312 seront publiées le 12 décembre."
                }
              ]
            }
            """;
    private final JsonCodec json = new JsonCodec();

    @Test void incompleteTargetOnlyAndMismatchedPlansRemainRejected() {
        assertThrows(ContractException.class, () -> json.readGenerated("{\"targetAgent\":\"SCRIBE_AGENT\"}", CorrectionPlan.class));
        assertThrows(InvalidOutputException.class, () -> json.readGenerated("""
                {"targetAgent":"SCRIBE_AGENT","correctionType":"UPDATE_KNOWLEDGE",
                 "requiresUserValidation":false,"instruction":"Tutoie-le","memoryProposals":[],"reason":"Style"}
                """, CorrectionPlan.class));
    }

    @Test
    void reportedOutputBecomesAValidProposalAndKeepsTheOriginalTrace() {
        var gateway = new FakeLlmGateway(json).enqueueJson(REPORTED_OUTPUT);
        var result = gateway.generate(new LlmRequest<>("Correction", "Les notes seront publiées le 12 décembre",
                Contract.CORRECTION_PLAN, Duration.ofSeconds(300), 0.0), TaskContext.start(), "CorrectingAgent");
        var success = assertInstanceOf(Success.class, result);
        var plan = assertInstanceOf(CorrectionPlan.class, success.value());
        assertEquals(REPORTED_OUTPUT, success.rawOutput());
        assertEquals("date_publication_notes_inf312", plan.memoryProposals().getFirst().key());
        assertEquals("Les notes du cours INF312 seront publiées le 12 décembre.", plan.memoryProposals().getFirst().value());
        assertTrue(plan.requiresUserValidation());
        assertFalse(plan.instruction().isBlank());
        assertFalse(plan.reason().isBlank());
        assertEquals(plan, json.read(json.write(plan), CorrectionPlan.class));
        assertThrows(RuntimeException.class, () -> json.read(REPORTED_OUTPUT, CorrectionPlan.class));
        assertThrows(InvalidOutputException.class,
                () -> new KnowledgeProposal(KnowledgeProposal.Scope.COMMON, "INF312", "Date"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../INF312", "date publication", "clé", "", "123_date"})
    void doesNotGuessInvalidKeys(String key) {
        var raw = REPORTED_OUTPUT.replace("date_publication_notes_INF312", key);
        assertThrows(RuntimeException.class, () -> json.readGenerated(raw, CorrectionPlan.class));
    }

    @Test
    void doesNotFillConsentValuesScopesOrNonMemoryInstructions() {
        for (String raw : new String[] {
                REPORTED_OUTPUT.replace("\"requiresUserValidation\": true,", ""),
                REPORTED_OUTPUT.replace("\"requiresUserValidation\": true", "\"requiresUserValidation\": false"),
                REPORTED_OUTPUT.replace("\"AGENT_SPECIFIC\"", "\"SESSION_ONLY\""),
                REPORTED_OUTPUT.replace("\"key\": \"date_publication_notes_INF312\",", ""),
                REPORTED_OUTPUT.replace("\"value\": \"Les notes du cours INF312 seront publiées le 12 décembre.\"",
                        "\"value\": null"),
                REPORTED_OUTPUT.replace("\"memoryProposals\":", "\"unexpected\": 1, \"memoryProposals\":"),
                """
                {"targetAgent":"SCRIBE_AGENT","correctionType":"ADJUST_STYLE",
                 "requiresUserValidation":false,"memoryProposals":[]}
                """
        }) {
            assertThrows(RuntimeException.class, () -> json.readGenerated(raw, CorrectionPlan.class));
        }
    }

    @Test
    void normalizationCannotMergeConflictingProposalsSilently() {
        var node = json.read(REPORTED_OUTPUT, com.fasterxml.jackson.databind.JsonNode.class);
        var proposals = (com.fasterxml.jackson.databind.node.ArrayNode) node.path("memoryProposals");
        proposals.addObject().put("suggestedScope", "COMMON").put("key", "date_publication_notes_inf312")
                .put("value", "Autre date");
        assertThrows(InvalidOutputException.class, () -> json.readGenerated(node.toString(), CorrectionPlan.class));
    }
}
