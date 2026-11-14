package fr.mas.email;

import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.agent.DecisionAgent;
import fr.mas.email.agent.DecisionInput;
import fr.mas.email.contract.Contract;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.MessageFactory;
import fr.mas.email.contract.RouteDecision;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.orchestration.OrchestratorAgent;
import fr.mas.email.orchestration.RouteDecisionValidator;
import fr.mas.email.result.DiscussionPlan;
import fr.mas.email.result.ErrorCode;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.FileWorldStore;
import fr.mas.email.world.SharedWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecisionAgentTest {
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();
    private final FakeLlmGateway gateway = new FakeLlmGateway(json);
    private final TaskContext task = TaskContext.start();
    private final DecisionInput input = new DecisionInput(
            TestData.analysis("fr", "REQUEST_INFORMATION", List.of("RESEARCH")),
            List.of(AgentFactoryTest.config("ResearchWriter", AgentRole.WRITER)));
    private FileWorldStore worlds;
    private AgentFactory factory;

    @BeforeEach
    void setUp() throws IOException {
        worlds = new FileWorldStore(directory, json);
        factory = new AgentFactory(gateway, worlds);
    }

    @Test
    void publishesASelectionWithJavaOwnedIdentityAndMetadata() throws IOException {
        var content = TestData.routeContent("ResearchWriter", List.of("Projet de recherche"));
        gateway.enqueueJson(json.write(content));
        var orchestrator = new OrchestratorAgent(worlds.sharedWorld(), new MessageFactory(Clock.systemUTC()));
        var result = orchestrator.execute(decision(), input, task, Contract.ROUTE_DECISION,
                new RouteDecisionValidator(input.analysis().emailId(), input.availableWriters()));
        if (result instanceof Success(var message, var rawIgnored0)) {
            assertEquals(content.enrich(input.analysis().emailId()), message.payload());
            assertEquals("1.0", message.metadata().schemaVersion());
            assertEquals("Decision", message.metadata().producerAgentId());
            assertEquals(task.correlationId(), message.metadata().correlationId());
        } else {
            fail("Expected routing success: " + result);
        }
        assertEquals(1, gateway.callCount());
    }

    @Test
    void noSuitableWriterCanAskARoutingQuestionThroughOrchestrator() throws IOException {
        var question = "Quel domaine faut-il examiner pour cette demande ?";
        var content = new RouteDecision.Content(null, List.of(), RouteDecision.DecisionStatus.NEEDS_CLARIFICATION,
                List.of("Aucun Writer disponible pour ce domaine."), null, true, question);
        gateway.enqueueJson(json.write(content));
        var noWriters = new DecisionInput(input.analysis(), List.of());
        var orchestrator = new OrchestratorAgent(worlds.sharedWorld(), new MessageFactory(Clock.systemUTC()));
        var result = orchestrator.execute(decision(), noWriters, task, Contract.ROUTE_DECISION,
                new RouteDecisionValidator(input.analysis().emailId(), noWriters.availableWriters()));
        var discussion = assertInstanceOf(DiscussionPlan.class, result);
        assertEquals(List.of(question), discussion.questions());
        assertEquals(SharedWorld.Status.WAITING_USER, orchestrator.state().orElseThrow().status());
        assertEquals(1, gateway.callCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Intruder", "GenericWriter"})
    void rejectsWriterAbsentFromTheRegistry(String id) {
        gateway.enqueueJson(json.write(TestData.routeContent(id, List.of("Faux profil"))));
        assertError(ErrorCode.INVALID_OUTPUT);
    }

    @Test
    void rejectsUnlistedCandidateEvenWhenSelectedWriterIsAuthorized() {
        var content = new RouteDecision.Content("ResearchWriter", List.of("ResearchWriter", "Intruder"),
                RouteDecision.DecisionStatus.SELECTED, List.of("Projet de recherche"), null, false, null);
        gateway.enqueueJson(json.write(content));
        assertError(ErrorCode.INVALID_OUTPUT);
    }

    @Test
    void selectedWithConfirmationIsRejectedByTheRoutingBehavior() {
        var content = new RouteDecision.Content("ResearchWriter", List.of("ResearchWriter"),
                RouteDecision.DecisionStatus.SELECTED, List.of("Projet de recherche"), null, true, "Confirmer ?");
        gateway.enqueueJson(json.write(content));
        assertError(ErrorCode.INVALID_OUTPUT);
    }

    @Test
    void rejectsGeneratedMetadata() {
        gateway.enqueueJson(json.write(TestData.route("ResearchWriter", List.of("Projet de recherche"))));
        assertError(ErrorCode.INVALID_JSON);
    }

    @Test
    void propagatesTimeoutWithoutRetry() {
        gateway.enqueueTimeout();
        assertError(ErrorCode.LLM_TIMEOUT);
    }

    private DecisionAgent decision() {
        return factory.createDecision(AgentFactoryTest.config("Decision", AgentRole.DECISION));
    }

    private void assertError(ErrorCode code) {
        var error = assertInstanceOf(ErrorPlan.class, decision().execute(input, task));
        assertEquals(code, error.code());
        assertEquals(ErrorPlan.Stage.DECISION, error.stage());
        assertEquals(task, error.task());
        assertEquals(1, gateway.callCount());
    }
}
