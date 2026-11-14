package fr.mas.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import fr.mas.email.contract.*;
import fr.mas.email.llm.*;
import fr.mas.email.result.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** The supplied examples and schemas are read directly, never rewritten as test expectations. */
class ValidatedContractsTest {
    private static final JsonMapper TREE = new JsonMapper();
    private static final JsonCodec JSON = new JsonCodec();
    private static final Map<String, Contract<?>> CONTRACTS = Map.of(
            "email-analysis", Contract.EMAIL_ANALYSIS, "route-decision", Contract.ROUTE_DECISION,
            "response-plan", Contract.RESPONSE_PLAN, "draft-response", Contract.DRAFT_RESPONSE,
            "correction-plan", Contract.CORRECTION_PLAN, "discussion-request", Contract.DISCUSSION_PLAN,
            "error-plan", Contract.ERROR_PLAN);
    private static final Map<String, Contract<?>> LLM = Map.of(
            "email-analysis", Contract.EMAIL_ANALYSIS_CONTENT, "route-decision", Contract.ROUTE_DECISION_CONTENT,
            "response-plan", Contract.RESPONSE_PLAN_CONTENT, "draft-content", Contract.DRAFT_CONTENT,
            "correction-plan", Contract.CORRECTION_PLAN);

    static List<Path> examples(String validity) throws IOException {
        try (var paths = Files.list(Path.of("docs/examples", validity))) { return paths.sorted().toList(); }
    }
    static Stream<Path> validExamples() throws IOException { return examples("valid").stream(); }
    static Stream<Path> invalidExamples() throws IOException { return examples("invalid").stream(); }
    static String kind(Path path) { return path.getFileName().toString().split("\\.")[0]; }

    static Object readExample(Path path, JsonNode value) {
        String kind = kind(path);
        if (kind.equals("user-discussion-answer")) return JSON.read(value.toString(), UserDiscussionAnswer.class);
        if (kind.equals("discussion-resume")) return JSON.read(value.toString(), DiscussionResume.class);
        return JSON.readMessage(value.toString(), CONTRACTS.get(kind));
    }

    static JsonNode schema(String category, String name) throws IOException {
        return TREE.readTree(Files.readString(Path.of("docs/schemas", category, name + ".schema.json")));
    }
    static JsonSchema validator(JsonNode schema) {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schema, SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }

    @Test
    void emailAnalysisContentSchemaRejectsRemovedPropertyAndEvidenceField() throws IOException {
        var schema = validator(ContractSchemas.llmSchema(Contract.EMAIL_ANALYSIS_CONTENT));
        var content = (ObjectNode) TREE.readTree(JSON.write(TestData.analysisContent("fr", "REQUEST", List.of())));
        assertTrue(schema.validate(content).isEmpty());
        var legacy = content.deepCopy();
        legacy.putArray("missingInformation");
        assertFalse(schema.validate(legacy).isEmpty());
        content.putArray("evidence").addObject().put("field", "missingInformation").put("text", "Quelle date ?");
        assertFalse(schema.validate(content).isEmpty());
    }

    @ParameterizedTest(name = "valid: {0}") @MethodSource("validExamples")
    void suppliedValidExamplesRoundTripWithoutLosingFields(Path path) throws Exception {
        JsonNode example = TREE.readTree(Files.readString(path));
        Object value = readExample(path, example);
        JsonNode serialized = TREE.readTree(JSON.write(value));
        assertEquals(example, serialized);
        assertEquals(value, readExample(path, serialized));
    }

    @ParameterizedTest(name = "invalid: {0}") @MethodSource("invalidExamples")
    void suppliedInvalidExamplesAreRejected(Path path) throws Exception {
        JsonNode example = TREE.readTree(Files.readString(path));
        if (kind(path).equals("draft-response")) assertThrows(ContractException.class, () -> readExample(path, example));
        else assertThrows(InvalidOutputException.class, () -> readExample(path, example));
    }

    @TestFactory
    Stream<DynamicTest> requiredNullUnknownAndScalarFieldsMatchSuppliedSchemas() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        for (Path path : examples("valid")) {
            JsonNode value = TREE.readTree(Files.readString(path));
            String category = value.has("metadata") ? "message" : "payload";
            JsonSchema validator = validator(schema(category, kind(path)));
            collectMutations(tests, path, value, value, "", validator);
        }
        return tests.stream();
    }

    private static void collectMutations(List<DynamicTest> tests, Path file, JsonNode root, JsonNode node,
                                          String pointer, JsonSchema schema) {
        if (node.isObject()) {
            ObjectNode unknown = root.deepCopy();
            ((ObjectNode) unknown.at(pointer)).put("unexpectedField", "forged");
            tests.add(DynamicTest.dynamicTest(file.getFileName() + pointer + ": unknown", () ->
                    assertThrows(ContractException.class, () -> readExample(file, unknown))));
            node.properties().forEach(entry -> {
                String field = entry.getKey();
                ObjectNode missing = root.deepCopy();
                ((ObjectNode) missing.at(pointer)).remove(field);
                tests.add(DynamicTest.dynamicTest(file.getFileName() + pointer + "/" + field + ": missing", () ->
                        assertThrows(ContractException.class, () -> readExample(file, missing))));
                ObjectNode nulled = root.deepCopy();
                ((ObjectNode) nulled.at(pointer)).putNull(field);
                tests.add(DynamicTest.dynamicTest(file.getFileName() + pointer + "/" + field + ": null", () -> {
                    if (schema.validate(nulled).isEmpty()) assertDoesNotThrow(() -> readExample(file, nulled));
                    else {
                        var error = assertThrows(RuntimeException.class, () -> readExample(file, nulled));
                        assertTrue(error instanceof ContractException || error instanceof InvalidOutputException);
                    }
                }));
                if (entry.getValue().isTextual() || entry.getValue().isBoolean()) {
                    ObjectNode numeric = root.deepCopy();
                    ((ObjectNode) numeric.at(pointer)).put(field, 1);
                    tests.add(DynamicTest.dynamicTest(file.getFileName() + pointer + "/" + field + ": numeric coercion", () ->
                            assertThrows(ContractException.class, () -> readExample(file, numeric))));
                }
                if (entry.getValue().isTextual()) {
                    ObjectNode blank = root.deepCopy();
                    ((ObjectNode) blank.at(pointer)).put(field, "  ");
                    tests.add(DynamicTest.dynamicTest(file.getFileName() + pointer + "/" + field + ": blank", () ->
                            assertThrows(ContractException.class, () -> readExample(file, blank))));
                }
                collectMutations(tests, file, root, entry.getValue(), pointer + "/" + field, schema);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) collectMutations(tests, file, root, node.get(i), pointer + "/" + i, schema);
        }
    }

    @TestFactory
    Stream<DynamicTest> unknownEnumValuesCannotBeCoercedOrIgnored() throws Exception {
        Map<String, List<String>> fields = Map.of(
                "route-decision.selected", List.of("decisionStatus"),
                "response-plan.ready", List.of("responseIntent"),
                "draft-response.draft", List.of("status"),
                "correction-plan.ready", List.of("targetAgent", "correctionType"),
                "discussion-request.question", List.of("action", "memoryProposal"),
                "error-plan.timeout", List.of("stage", "reportedBy", "code", "status"),
                "user-discussion-answer", List.of("action", "memoryChoice"),
                "discussion-resume", List.of("action", "storageResult"));
        var tests = new ArrayList<DynamicTest>();
        for (var entry : fields.entrySet()) {
            Path path = Path.of("docs/examples/valid", entry.getKey() + ".json");
            ObjectNode root = (ObjectNode) TREE.readTree(Files.readString(path));
            for (String field : entry.getValue()) {
                ObjectNode invalid = root.deepCopy();
                ObjectNode target = invalid.has("payload") ? (ObjectNode) invalid.get("payload") : invalid;
                target.put(field, "UNRECOGNIZED");
                tests.add(DynamicTest.dynamicTest(entry.getKey() + ": invalid enum " + field, () ->
                        assertThrows(ContractException.class, () -> readExample(path, invalid))));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> eachMessageChecksItsOwnVersionAndFormats() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        for (Path path : examples("valid")) {
            ObjectNode value = (ObjectNode) TREE.readTree(Files.readString(path));
            if (!value.has("metadata")) continue;
            for (var entry : Map.of("schemaVersion", "99.0", "messageType", "UNRECOGNIZED",
                    "messageId", "1-1-1-1-1", "correlationId", "invalid", "createdAt", "2026-99-01").entrySet()) {
                ObjectNode invalid = value.deepCopy();
                ((ObjectNode) invalid.get("metadata")).put(entry.getKey(), entry.getValue());
                tests.add(DynamicTest.dynamicTest(path.getFileName() + ": " + entry.getKey(), () ->
                        assertThrows(ContractException.class, () -> readExample(path, invalid))));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> llmBoundariesRejectJavaFieldsAndRequireAllCognitiveFields() throws Exception {
        var tests = new ArrayList<DynamicTest>();
        for (var entry : LLM.entrySet()) {
            String name = entry.getKey(); Contract<?> contract = entry.getValue();
            String exampleKind = name.equals("draft-content") ? "draft-response" : name;
            Path path = examples("valid").stream().filter(p -> kind(p).equals(exampleKind)).findFirst().orElseThrow();
            ObjectNode full = (ObjectNode) TREE.readTree(Files.readString(path)).get("payload");
            ObjectNode content = full.deepCopy();
            var names = new ArrayList<String>();
            schema("llm", name).get("properties").fieldNames().forEachRemaining(names::add);
            content.retain(names);
            tests.add(DynamicTest.dynamicTest(name + ": valid LLM output", () -> {
                var gateway = new FakeLlmGateway(JSON).enqueueJson(content.toString());
                var result = gateway.generate(new LlmRequest<>("Test only", "Synthetic input", contract,
                        Duration.ofSeconds(1)), TaskContext.start(), "Agent");
                assertInstanceOf(Success.class, result);
                assertEquals(1, gateway.callCount());
                assertEquals(schema("llm", name), ContractSchemas.llmSchema(contract));
            }));
            for (String field : names) {
                ObjectNode missing = content.deepCopy(); missing.remove(field);
                tests.add(DynamicTest.dynamicTest(name + ": missing " + field, () ->
                        assertThrows(ContractException.class, () -> JSON.read(missing.toString(), contract.payloadType()))));
            }
            for (String field : List.of("emailId", "threadId", "planId", "agentId", "metadata", "task", "errorId")) {
                ObjectNode forged = content.deepCopy(); forged.put(field, "forged");
                tests.add(DynamicTest.dynamicTest(name + ": forged " + field, () ->
                        assertThrows(ContractException.class, () -> JSON.read(forged.toString(), contract.payloadType()))));
            }
        }
        return tests.stream();
    }

    @Test
    void gatewayCannotBeAskedToGenerateEnrichedMessagesDiscussionsOrErrors() {
        for (Contract<?> contract : List.of(Contract.EMAIL_ANALYSIS, Contract.ROUTE_DECISION,
                Contract.RESPONSE_PLAN, Contract.DRAFT_RESPONSE, Contract.DISCUSSION_PLAN, Contract.ERROR_PLAN)) {
            assertThrows(IllegalArgumentException.class, () -> new LlmRequest<>("Test", "Input", contract, Duration.ofSeconds(1)));
        }
        assertThrows(IllegalArgumentException.class, () -> new LlmRequest<>("Test", "Input",
                new Contract<>("DRAFT_RESPONSE", "2.0", DraftContent.class), Duration.ofSeconds(1)));
    }

    @Test
    void exampleWithMismatchedWriterIsStructurallyValidButCannotBeIssuedByJava() throws Exception {
        var example = JSON.readMessage(Files.readString(Path.of("docs/examples/valid/response-plan.clarification.json")),
                Contract.RESPONSE_PLAN);
        var factory = new MessageFactory(Clock.systemUTC());
        assertNotEquals(example.metadata().producerAgentId(), example.payload().agentId());
        assertThrows(InvalidOutputException.class, () -> factory.create(Contract.RESPONSE_PLAN, example.payload(),
                example.metadata().correlationId(), example.metadata().producerAgentId()));
    }

    @Test
    void javaEnrichmentOwnsIdsProfilesAndDraftStatus() {
        var content = TestData.planContent(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST, List.of());
        var plan = content.enrich("Writer", "email-1", List.of("greeting", "answer"), "fr", "formal");
        var newer = content.enrich("Writer", "email-1", List.of(), "fr", "formal");
        assertNotEquals(plan.planId(), newer.planId());
        var draft = new DraftContent("Re", "Bonjour").enrich(plan);
        assertEquals(plan.planId(), draft.planId()); assertEquals(plan.emailId(), draft.emailId());
        assertEquals(DraftResponse.Status.DRAFT, draft.status());
        assertEquals(List.of("greeting", "answer"), plan.replyStructure());
        var factory = new MessageFactory(Clock.systemUTC());
        var first = factory.create(Contract.DRAFT_RESPONSE, draft, UUID.randomUUID(), "Scribe");
        var second = factory.create(Contract.DRAFT_RESPONSE, draft, first.metadata().correlationId(), "Scribe");
        assertNotEquals(first.metadata().messageId(), second.metadata().messageId());
    }
}
