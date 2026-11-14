package fr.mas.email.llm.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.mas.email.agent.AgentConfig;
import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.agent.DecisionInput;
import fr.mas.email.agent.ParserInput;
import fr.mas.email.agent.WriterInput;
import fr.mas.email.agent.WriterProfiles;
import fr.mas.email.contract.*;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.*;
import fr.mas.email.world.FileWorldStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Serveur HTTP local simulé : aucun modèle ni serveur Ollama requis pour ces tests. */
@Timeout(10)
class OllamaLlmGatewayTest {
    private final JsonCodec json = new JsonCodec();
    private final JsonMapper mapper = new JsonMapper();
    private final TaskContext task = TaskContext.start();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService workers;
    private OllamaLlmGateway gateway;
    private URI address;
    private Reply reply;

    @FunctionalInterface
    private interface Reply {
        void send(HttpExchange exchange) throws IOException, InterruptedException;
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        reply = exchange -> send(exchange, 200, envelope("{\"subject\":\"Re\",\"body\":\"Bonjour\"}"));
        server.createContext("/api/chat", exchange -> {
            try {
                calls.incrementAndGet();
                requestMethod.set(exchange.getRequestMethod());
                received.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                reply.send(exchange);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        address = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        gateway = new OllamaLlmGateway(address, "qwen3:4b", json);
    }

    @AfterEach
    void stopServer() {
        if (gateway != null) gateway.close();
        if (server != null) server.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    static Stream<Arguments> contracts() {
        return Stream.of(
                Arguments.of(Contract.EMAIL_ANALYSIS_CONTENT, fr.mas.email.TestData.analysisContent("fr", "REQUEST", List.of("REUNION"))),
                Arguments.of(Contract.ROUTE_DECISION_CONTENT, fr.mas.email.TestData.routeContent("ResearchWriter", List.of("Recherche"))),
                Arguments.of(Contract.RESPONSE_PLAN_CONTENT, fr.mas.email.TestData.planContent(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                        List.of(new ResponsePlan.Fact("La réunion est lundi.", "calendar")))),
                Arguments.of(Contract.DRAFT_CONTENT, new DraftContent("Re: réunion", "Bonjour à tous")),
                Arguments.of(Contract.CORRECTION_PLAN, new CorrectionPlan(CorrectionPlan.Target.SCRIBE_AGENT,
                        CorrectionPlan.Type.ADJUST_STYLE, false, "Change greeting", List.of(), "User feedback")));
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void sendsSchemaAndReturnsEachExistingDto(Contract<?> contract, Object payload) {
        reply = exchange -> send(exchange, 200, envelope(json.write(payload)));
        double temperature = contract.equals(Contract.CORRECTION_PLAN) ? 0.0 : 0.2;
        var request = new LlmRequest<>("Instructions de test", "Données à analyser", contract, Duration.ofSeconds(3),
                temperature);
        LlmGateway interchangeable = gateway;
        var result = assertInstanceOf(Success.class, interchangeable.generate(request, task, "Agent"));
        assertEquals(payload, result.value());

        JsonNode sent = received.get();
        assertEquals("POST", requestMethod.get());
        assertEquals("qwen3:4b", sent.path("model").asText());
        assertFalse(sent.path("stream").booleanValue());
        assertFalse(sent.path("think").booleanValue());
        assertEquals(temperature, sent.path("options").path("temperature").doubleValue());
        assertEquals("system", sent.path("messages").get(0).path("role").asText());
        assertTrue(sent.path("messages").get(0).path("content").asText().contains("Instructions de test"));
        assertEquals("user", sent.path("messages").get(1).path("role").asText());
        assertEquals("Données à analyser", sent.path("messages").get(1).path("content").asText());
        JsonNode schema = sent.path("format");
        assertEquals("object", schema.path("type").asText());
        assertFalse(schema.path("additionalProperties").booleanValue());
        assertEquals(contract.payloadType().getRecordComponents().length, schema.path("required").size());
        assertFalse(schema.path("properties").has("metadata"));
        assertEquals(ContractSchemas.llmSchema(contract), schema);
        if (contract.equals(Contract.CORRECTION_PLAN)) {
            assertFalse(schema.has("oneOf"));
            assertEquals(List.of("targetAgent", "correctionType", "requiresUserValidation", "instruction", "memoryProposals", "reason"),
                    java.util.stream.StreamSupport.stream(schema.path("required").spliterator(), false).map(JsonNode::asText).toList());
        }
        assertEquals(1, calls.get());
    }

    @Test
    void modelCanChangeWithoutChangingRequestOrBusinessTypes() {
        try (var other = new OllamaLlmGateway(address, "another-model:tag", json)) {
            assertInstanceOf(Success.class, other.generate(request(Duration.ofSeconds(3)), task, "Scribe"));
            assertEquals("another-model:tag", received.get().path("model").asText());
        }
    }

    @Test
    void parserSendsUnmodifiedPromptAndOrderedSchema(@TempDir Path world) throws IOException {
        var input = new ParserInput("java-email", "java-thread", "As-tu réservé une piste ?");
        var content = fr.mas.email.TestData.analysisContent("fr", "REQUEST_INFORMATION", List.of("BOWLING"));
        reply = exchange -> send(exchange, 200, envelope(json.write(content)));
        try (var ministral = new OllamaLlmGateway(address, "ministral:8b", json,
                new OllamaLlmGateway.ChatOptions(false, 1536))) {
            var parser = new AgentFactory(ministral, new FileWorldStore(world, json)).createParser(parserConfig());
            var success = assertInstanceOf(Success.class, parser.execute(input, task));
            assertEquals(content.enrich(input.emailId(), input.threadId()), success.value());
        }
        JsonNode sent = received.get();
        assertEquals("ministral:8b", sent.path("model").textValue());
        assertEquals(1536, sent.at("/options/num_predict").intValue());
        assertEquals(0, sent.at("/options/temperature").intValue());
        assertEquals(mapper.getNodeFactory().booleanNode(false), sent.path("think"));
        assertEquals("system", sent.at("/messages/0/role").textValue());
        assertEquals(Files.readString(Path.of("src/main/resources/prompts/parser-agent-v3.0.txt")),
                sent.at("/messages/0/content").textValue());
        assertEquals("user", sent.at("/messages/1/role").textValue());
        assertEquals(input.text(), sent.at("/messages/1/content").textValue());
        assertEquals(2, sent.path("messages").size());
        var validatedSchema = mapper.readTree(Files.readString(Path.of("docs/schemas/llm/email-analysis.schema.json")));
        assertEquals(validatedSchema.toString(), sent.path("format").toString());
        assertEquals(1, calls.get());
    }

    @Test
    void invalidOllamaContentReachesParserAsTechnicalFailure(@TempDir Path world) throws IOException {
        reply = exchange -> send(exchange, 200, envelope("{"));
        var parser = new AgentFactory(gateway, new FileWorldStore(world, json)).createParser(parserConfig());
        var result = parser.execute(new ParserInput("email", "thread", "As-tu réservé une piste ?"), task);
        var error = assertInstanceOf(ErrorPlan.class, result);
        assertEquals(ErrorCode.INVALID_JSON, error.code());
        assertEquals(ErrorPlan.Stage.PARSER, error.stage());
        assertEquals("Parser", error.agentId());
        assertEquals(task, error.task());
        assertEquals(1, calls.get());
    }

    @Test
    void decisionSendsThePromptRegistryAndRouteContentSchema(@TempDir Path world) throws IOException {
        var config = new AgentConfig("Decision", AgentRole.DECISION, "Routage", List.of(), List.of());
        var writer = new AgentConfig("Writer42", AgentRole.WRITER, "Projets scientifiques",
                List.of("Traiter les réunions de recherche"), List.of("Domaine recherche uniquement"));
        var analysis = fr.mas.email.TestData.analysis("fr", "REQUEST_INFORMATION", List.of("RESEARCH"));
        var input = new DecisionInput(analysis, List.of(writer));
        var content = fr.mas.email.TestData.routeContent(writer.agentId(), List.of("Projet de recherche"));
        reply = exchange -> send(exchange, 200, envelope(json.write(content)));
        var decision = new AgentFactory(gateway, new FileWorldStore(world, json))
                .createDecision(config, Duration.ofSeconds(3));
        var success = assertInstanceOf(Success.class, decision.execute(input, task));
        assertEquals(content.enrich(analysis.emailId()), success.value());

        JsonNode sent = received.get();
        assertEquals(2, sent.path("messages").size());
        assertEquals("system", sent.at("/messages/0/role").textValue());
        assertEquals(Files.readString(Path.of("src/main/resources/prompts/DecisionAgent.txt")),
                sent.at("/messages/0/content").textValue());
        assertEquals("user", sent.at("/messages/1/role").textValue());
        var userInput = mapper.readTree(sent.at("/messages/1/content").textValue());
        assertEquals(2, userInput.size());
        assertEquals(mapper.valueToTree(analysis), userInput.path("analysis"));
        assertEquals(mapper.valueToTree(List.of(writer)), userInput.path("availableWriters"));
        var schema = mapper.readTree(Files.readString(Path.of("docs/schemas/llm/route-decision.schema.json")));
        assertEquals(schema.toString(), sent.path("format").toString());
        assertFalse(sent.at("/format/properties").has("emailId"));
        assertEquals(1, calls.get());
    }

    @Test
    void writerSendsTheSharedPromptAndOrderedPlanSchema(@TempDir Path world) throws IOException {
        var profile = WriterProfiles.get("GenericWriter");
        var writer = new AgentFactory(gateway, new FileWorldStore(world, json)).createWriter(profile.toAgentConfig());
        var analysis = fr.mas.email.TestData.analysis("fr", "NOTIFY_INFORMATION", List.of());
        var content = fr.mas.email.TestData.planContent(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST, List.of());
        reply = exchange -> send(exchange, 200, envelope(json.write(content)));
        assertInstanceOf(Success.class, writer.execute(new WriterInput(analysis), task));

        JsonNode sent = received.get();
        assertEquals(2, sent.path("messages").size());
        assertEquals("system", sent.at("/messages/0/role").textValue());
        assertEquals(Files.readString(Path.of("src/main/resources/prompts/writer_agent_system.md")),
                sent.at("/messages/0/content").textValue());
        assertEquals("user", sent.at("/messages/1/role").textValue());
        var payload = mapper.readTree(sent.at("/messages/1/content").textValue());
        assertEquals(List.of("emailAnalysis", "writerProfile", "miniWorlds"),
                payload.properties().stream().map(Map.Entry::getKey).toList());
        assertEquals(profile.agentId(), payload.at("/writerProfile/agentId").textValue());

        var schema = sent.path("format");
        var order = List.of("responseIntent", "requiresClarification", "missingInformation", "factsToCommunicate");
        assertEquals(order, mapper.convertValue(schema.path("required"), List.class));
        assertEquals(order, schema.path("properties").properties().stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of("ANSWER_INFORMATION_REQUEST", "PROVIDE_DOCUMENT", "ACKNOWLEDGE_REQUEST", "REQUEST_CLARIFICATION"),
                mapper.convertValue(schema.at("/properties/responseIntent/enum"), List.class));
        assertFalse(schema.path("additionalProperties").booleanValue());
        var fact = schema.at("/properties/factsToCommunicate/items");
        var factOrder = List.of("sourceRef", "text");
        assertEquals(factOrder, mapper.convertValue(fact.path("required"), List.class));
        assertEquals(factOrder, fact.path("properties").properties().stream().map(Map.Entry::getKey).toList());
        assertFalse(fact.path("additionalProperties").booleanValue());
        assertEquals(1, calls.get());
    }

    @Test
    void scribeSendsTheProvidedPromptAndCompactStyledPlan(@TempDir Path world) throws IOException {
        var profile = WriterProfiles.get("PersonalWriter");
        var plan = fr.mas.email.TestData.planContent(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST, List.of())
                .enrich(profile.agentId(), "email", profile.replyStructure(), "fr", profile.tone());
        var config = new AgentConfig("Scribe", AgentRole.SCRIBE, "Rédaction", List.of(), List.of());
        var scribe = new AgentFactory(gateway, new FileWorldStore(world, json)).createScribe(config);
        assertInstanceOf(Success.class, scribe.execute(plan, task));
        var sent = received.get();
        assertEquals("system", sent.at("/messages/0/role").textValue());
        assertEquals(Files.readString(Path.of("src/main/resources/prompts/ScribePrompt.md")),
                sent.at("/messages/0/content").textValue());
        assertEquals("user", sent.at("/messages/1/role").textValue());
        assertEquals(json.write(plan), sent.at("/messages/1/content").textValue());
        var schema = sent.path("format");
        var fields = List.of("subject", "body");
        assertEquals(fields, mapper.convertValue(schema.path("required"), List.class));
        assertEquals(fields, schema.path("properties").properties().stream().map(Map.Entry::getKey).toList());
        assertFalse(schema.path("additionalProperties").booleanValue());
        for (String field : fields) assertEquals(1, schema.at("/properties/" + field + "/minLength").intValue());
        assertEquals(1, calls.get());
    }

    private static AgentConfig parserConfig() {
        return new AgentConfig("Parser", AgentRole.PARSER, "Email extraction", List.of(), List.of());
    }

    @Test
    void validJsonWithUnsupportedBusinessOutputProducesInvalidOutput() {
        String raw = "{\"responseIntent\":\"ANSWER_INFORMATION_REQUEST\",\n"
                + "\"factsToCommunicate\":[],\"missingInformation\":[],\"requiresClarification\":false}";
        reply = exchange -> send(exchange, 200, envelope(raw));
        var request = new LlmRequest<>("Test", "Synthetic", Contract.RESPONSE_PLAN_CONTENT, Duration.ofSeconds(3));
        var error = assertInstanceOf(ErrorPlan.class, gateway.generate(request, task, "Writer"));
        assertEquals(ErrorCode.INVALID_OUTPUT, error.code());
        assertEquals("Validation failed: This responseIntent requires factsToCommunicate | Raw LLM output: " + raw,
                error.observedProblem());
        assertEquals("Writer", error.agentId()); assertEquals(ErrorPlan.Stage.WRITER, error.stage());
        assertEquals("OllamaLlmGateway", error.component()); assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json", "null", "{}", "{\"subject\":\"Re\"}",
            "{\"subject\":\"Re\",\"body\":\" \"}",
            "{\"subject\":\"Re\",\"body\":\"Body\",\"metadata\":{\"messageId\":\"forged\"}}",
            "{\"subject\":\"Re\",\"body\":\"Body\"} {}"
    })
    void invalidGeneratedPayloadIsRejectedByExistingCodec(String content) {
        reply = exchange -> send(exchange, 200, envelope(content));
        assertError(ErrorCode.INVALID_JSON, gateway.generate(request(Duration.ofSeconds(3)), task, "Scribe"));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "{}", "{\"done\":\"true\"}",
            "{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":{}}}",
            "{\"done\":true,\"message\":{\"role\":\"user\",\"content\":\"{}\"}}"
    })
    void malformedTransportEnvelopeIsRejected(String body) {
        reply = exchange -> send(exchange, 200, body);
        assertError(ErrorCode.INVALID_JSON, gateway.generate(request(Duration.ofSeconds(3)), task, "Scribe"));
    }

    @Test
    void emptyAssistantContentHasItsSpecificErrorCode() {
        reply = exchange -> send(exchange, 200, envelope("  "));
        assertError(ErrorCode.EMPTY_RESULT, gateway.generate(request(Duration.ofSeconds(3)), task, "Scribe"));
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"done\":false,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}",
            "{\"done\":true,\"done_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}"
    })
    void incompleteGenerationIsNotAccepted(String body) {
        reply = exchange -> send(exchange, 200, body);
        assertError(ErrorCode.INVALID_OUTPUT, gateway.generate(request(Duration.ofSeconds(3)), task, "Scribe"));
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 404, 408, 429, 500, 503, 504})
    void httpFailuresAreMappedWithoutRetryOrExposingBody(int status) {
        reply = exchange -> {
            exchange.getResponseHeaders().add("Location", address.resolve("/api/chat").toString());
            send(exchange, status, "{\"error\":\"secret-token-and-prompt\"}");
        };
        ErrorCode expected = status == 408 || status == 504 ? ErrorCode.LLM_TIMEOUT : ErrorCode.LLM_UNAVAILABLE;
        var result = gateway.generate(request(Duration.ofSeconds(3)), task, "Scribe");
        assertError(expected, result);
        assertFalse(result.toString().contains("secret-token"));
        assertEquals(1, calls.get());
    }

    @Test
    void errorInSuccessfulHttpEnvelopeRemainsATechnicalFailure() {
        reply = exchange -> send(exchange, 200, "{\"error\":\"secret endpoint details\"}");
        var result = gateway.generate(request(Duration.ofSeconds(3)), task, "Scribe");
        assertError(ErrorCode.LLM_UNAVAILABLE, result);
        assertFalse(result.toString().contains("secret"));
    }

    @Test
    void timesOutWhileWaitingForHeadersWithoutRetry() {
        reply = exchange -> {
            Thread.sleep(1500);
            send(exchange, 200, envelope("{\"subject\":\"Re\",\"body\":\"Bonjour\"}"));
        };
        assertError(ErrorCode.LLM_TIMEOUT, gateway.generate(request(Duration.ofMillis(300)), task, "Scribe"));
        assertEquals(1, calls.get());
    }

    @Test
    void timesOutEvenWhenHeadersHaveAlreadyArrived() {
        reply = exchange -> {
            byte[] bytes = envelope("{\"subject\":\"Re\",\"body\":\"Bonjour\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes, 0, 1);
            exchange.getResponseBody().flush();
            Thread.sleep(1500);
            exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
        };
        assertError(ErrorCode.LLM_TIMEOUT, gateway.generate(request(Duration.ofMillis(300)), task, "Scribe"));
        assertEquals(1, calls.get());
    }

    @Test
    void stoppedServerBecomesUnavailable() {
        server.stop(0);
        assertError(ErrorCode.LLM_UNAVAILABLE,
                gateway.generate(request(Duration.ofSeconds(3)), task, "Scribe"));
        assertEquals(0, calls.get());
    }

    @Test
    void preservesInterruptionAndReturnsAnIncident() {
        Thread.currentThread().interrupt();
        try {
            assertError(ErrorCode.INTERNAL_ERROR, gateway.generate(request(Duration.ofSeconds(3)), task, "Scribe"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///tmp/ollama", "http://user:password@localhost:11434",
            "http://localhost:11434/api/chat", "http://localhost:11434/?token=secret",
            "http://localhost:11434/#fragment"
    })
    void invalidServerConfigurationIsRejected(String serverAddress) {
        assertThrows(IllegalArgumentException.class,
                () -> new OllamaLlmGateway(URI.create(serverAddress), "qwen3:4b", json));
    }

    @Test
    void blankModelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OllamaLlmGateway(address, " ", json));
    }

    private LlmRequest<DraftContent> request(Duration timeout) {
        return new LlmRequest<>("Retourne un brouillon JSON.", "Message fictif", Contract.DRAFT_CONTENT, timeout);
    }

    private void assertError(ErrorCode code, AgentResult<?> result) {
        var error = assertInstanceOf(ErrorPlan.class, result);
        assertEquals(code, error.code());
        assertEquals("Scribe", error.agentId());
        assertEquals(task, error.task());
        assertNotNull(error.errorId());
    }

    private String envelope(String content) {
        return json.write(Map.of(
                "model", "qwen3:4b", "done", true, "done_reason", "stop",
                "message", Map.of("role", "assistant", "content", content, "thinking", "ignored internal text"),
                "total_duration", 1234));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
