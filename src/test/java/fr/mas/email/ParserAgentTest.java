package fr.mas.email;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.agent.ParserInput;
import fr.mas.email.contract.Contract;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.MessageFactory;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.AgentResult;
import fr.mas.email.result.ErrorCode;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.FileWorldStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserAgentTest {
    private final JsonCodec json = new JsonCodec();
    private final FakeLlmGateway gateway = new FakeLlmGateway(json);
    private final TaskContext task = TaskContext.start();
    private final ParserInput input = new ParserInput("email-java", "thread-java",
            "Peux-tu envoyer le dossier ? Quel est le numéro du dossier ? La copie semble illisible.");
    @TempDir Path directory;
    private AgentFactory factory;

    @BeforeEach
    void setUp() throws IOException {
        factory = new AgentFactory(gateway, new FileWorldStore(directory, json));
    }

    @Test
    void acceptsEmptyActions() {
        var content = TestData.analysisContent("fr", "REQUEST_INFORMATION", List.of("DOSSIER"));
        var success = assertInstanceOf(Success.class, execute(content));
        assertEquals(List.of(), ((EmailAnalysis) success.value()).requestedActions());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "vendredi")
    void preservesAllCognitiveFieldsAndAddsJavaIds(String deadline) {
        String request = deadline == null ? "Peux-tu envoyer le dossier ?"
                : "Peux-tu envoyer le dossier avant vendredi ?";
        var source = new ParserInput(input.emailId(), input.threadId(),
                input.text().replace("Peux-tu envoyer le dossier ?", request));
        var action = new EmailAnalysis.RequestedAction("SEND_DOCUMENT", "Envoyer le dossier", deadline);
        var content = new EmailAnalysis.Content("fr", "REQUEST_ACTION", List.of("DOSSIER"),
                List.of(new EmailAnalysis.Entity("DOCUMENT", "dossier")), List.of("numero_dossier"),
                List.of(action), List.of("L'expéditeur indique que la copie semble illisible."),
                List.of("La lisibilité de la copie est incertaine."),
                List.of(new EmailAnalysis.Evidence("requestedActions", request)));
        gateway.enqueueJson(json.write(content));
        var result = assertInstanceOf(Success.class, run(source));
        var analysis = assertInstanceOf(EmailAnalysis.class, result.value());
        assertEquals(input.emailId(), analysis.emailId());
        assertEquals(input.threadId(), analysis.threadId());
        assertEquals(List.of(action), analysis.requestedActions());
        assertEquals(List.of("numero_dossier"), analysis.requestedInformation());
        var tree = new JsonMapper();
        ObjectNode cognitive = tree.valueToTree(analysis);
        cognitive.remove(List.of("emailId", "threadId"));
        assertEquals(tree.valueToTree(content), cognitive);
        assertEquals(analysis, json.read(json.write(analysis), EmailAnalysis.class));
        var message = new MessageFactory(Clock.systemUTC()).create(
                Contract.EMAIL_ANALYSIS, analysis, task.correlationId(), "Parser");
        assertEquals(message, json.readMessage(json.write(message), Contract.EMAIL_ANALYSIS));
        assertEquals(1, gateway.callCount());
    }

    @Test
    void removedFieldIsAbsentFromBothRecordsAndTheirJson() {
        var content = TestData.analysisContent("fr", "REQUEST_INFORMATION", List.of());
        var analysis = content.enrich(input.emailId(), input.threadId());
        for (Object value : List.of(content, analysis)) {
            assertFalse(new JsonMapper().valueToTree(value).has("missingInformation"));
            assertFalse(Arrays.stream(value.getClass().getRecordComponents())
                    .anyMatch(component -> component.getName().equals("missingInformation")));
        }
        assertEquals(9, EmailAnalysis.Content.class.getRecordComponents().length);
    }

    @Test
    void rejectsFormerMissingInformationProperty() {
        var valid = json.write(TestData.analysisContent("fr", "REQUEST_INFORMATION", List.of()));
        gateway.enqueueJson(valid.replace("\"evidence\":[]", "\"evidence\":[],\"missingInformation\":[]"));
        assertError(ErrorCode.INVALID_JSON, run(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MISSING", "null", "{}", "[null]", "[\"SEND_DOCUMENT\"]",
            "[{\"action\":\"SEND_DOCUMENT\",\"description\":\"Envoyer\"}]",
            "[{\"action\":\"SEND_DÔCUMENT\",\"description\":\"Envoyer\",\"deadline\":null}]",
            "[{\"action\":\"SEND_DOCUMENT\",\"description\":\" \",\"deadline\":null}]",
            "[{\"action\":\"SEND_DOCUMENT\",\"description\":\"Envoyer\",\"deadline\":123}]",
            "[{\"action\":\"SEND_DOCUMENT\",\"description\":\"Envoyer\",\"deadline\":null,\"extra\":true}]"
    })
    void rejectsMalformedActions(String actions) {
        var valid = json.write(TestData.analysisContent("fr", "REQUEST_ACTION", List.of()));
        var generated = actions.equals("MISSING") ? valid.replace("\"requestedActions\":[],", "")
                : valid.replace("\"requestedActions\":[]", "\"requestedActions\":" + actions);
        gateway.enqueueJson(generated);
        assertError(ErrorCode.INVALID_JSON, run(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"emailId", "missingInformation"})
    void rejectsUnknownEvidenceField(String field) {
        var valid = json.write(TestData.analysisContent("fr", "REQUEST_ACTION", List.of()));
        gateway.enqueueJson(valid.replace("\"evidence\":[]",
                "\"evidence\":[{\"field\":\"" + field + "\",\"text\":\"Peux-tu envoyer le dossier ?\"}]"));
        assertError(ErrorCode.INVALID_JSON, run(input));
    }

    @Test
    void rejectsEvidenceThatIsNotInTheNormalizedSource() {
        var valid = json.write(TestData.analysisContent("fr", "REQUEST_ACTION", List.of()));
        gateway.enqueueJson(valid.replace("\"evidence\":[]",
                "\"evidence\":[{\"field\":\"requestedActions\",\"text\":\"Peux-tu envoyer le dossier!\"}]"));
        assertError(ErrorCode.INVALID_OUTPUT, run(input));
    }

    @Test
    void acceptsTypographicDifferencesWithoutChangingTheReturnedEvidence() {
        String quote = "”PEUX-TU   M'ENVOYER le dossier ?“";
        var valid = json.write(TestData.analysisContent("fr", "REQUEST_ACTION", List.of()));
        var evidence = new EmailAnalysis.Evidence("requestedActions", quote);
        gateway.enqueueJson(valid.replace("\"evidence\":[]", "\"evidence\":" + json.write(List.of(evidence))));
        var source = new ParserInput("email-1", "thread-1", "Bonjour,\r\n«Peux-tu m’envoyer\r\n\tle dossier\u00a0?»");
        var success = assertInstanceOf(Success.class, run(source));
        assertEquals(List.of(evidence), ((EmailAnalysis) success.value()).evidence());
    }

    @Test
    void rejectsInvalidJson() {
        gateway.enqueueJson("{");
        assertError(ErrorCode.INVALID_JSON, run(input));
    }

    @Test
    void propagatesTimeoutWithoutRetry() {
        gateway.enqueueTimeout();
        assertError(ErrorCode.LLM_TIMEOUT, run(input));
    }

    @Test
    void propagatesUnavailableEngineWithoutRetry() {
        gateway.enqueueFailure(new IOException("Sensitive transport details"));
        assertError(ErrorCode.LLM_UNAVAILABLE, run(input));
    }

    @Test
    void preservesInterruptionWithoutRetry() {
        gateway.enqueueFailure(new InterruptedException());
        try {
            assertError(ErrorCode.INTERNAL_ERROR, run(input));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void forwardsDefaultAndExplicitTimeouts() throws IOException {
        var timeouts = new java.util.ArrayList<Duration>();
        LlmGateway recordingGateway = new LlmGateway() {
            @Override
            public <T> AgentResult<T> generate(LlmRequest<T> request, TaskContext task, String component) {
                timeouts.add(request.timeout());
                return gateway.generate(request, task, component);
            }
        };
        var recordingFactory = new AgentFactory(recordingGateway, new FileWorldStore(directory, json));
        String response = json.write(TestData.analysisContent("fr", "REQUEST_INFORMATION", List.of()));
        gateway.enqueueJson(response).enqueueJson(response);
        var defaultParser = recordingFactory.createParser(AgentFactoryTest.config("Parser", AgentRole.PARSER));
        var customParser = recordingFactory.createParser(AgentFactoryTest.config("CustomParser", AgentRole.PARSER),
                Duration.ofSeconds(42));
        assertInstanceOf(Success.class, defaultParser.execute(input, task));
        assertInstanceOf(Success.class, customParser.execute(input, task));
        assertEquals(List.of(Duration.ofSeconds(300), Duration.ofSeconds(42)), timeouts);
    }

    private AgentResult<EmailAnalysis> execute(EmailAnalysis.Content content) {
        gateway.enqueueJson(json.write(content));
        return run(input);
    }

    private AgentResult<EmailAnalysis> run(ParserInput source) {
        var parser = factory.createParser(AgentFactoryTest.config("Parser", AgentRole.PARSER));
        return parser.execute(source, task);
    }

    private void assertError(ErrorCode code, AgentResult<EmailAnalysis> result) {
        var error = assertInstanceOf(ErrorPlan.class, result);
        assertEquals(code, error.code());
        assertEquals(ErrorPlan.Stage.PARSER, error.stage());
        assertEquals("Parser", error.agentId());
        assertEquals(task, error.task());
        assertEquals(1, gateway.callCount());
    }
}
