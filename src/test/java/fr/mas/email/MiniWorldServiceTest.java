package fr.mas.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.agent.DecisionInput;
import fr.mas.email.agent.ParserInput;
import fr.mas.email.agent.WriterInput;
import fr.mas.email.agent.WriterProfiles;
import fr.mas.email.contract.Contract;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.MessageFactory;
import fr.mas.email.contract.ResponsePlan;
import fr.mas.email.contract.RouteDecision;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.orchestration.OrchestratorAgent;
import fr.mas.email.orchestration.ResponsePlanValidator;
import fr.mas.email.orchestration.RouteDecisionValidator;
import fr.mas.email.result.AgentResult;
import fr.mas.email.result.DiscussionPlan;
import fr.mas.email.result.ErrorCode;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.FileWorldStore;
import fr.mas.email.world.MiniWorldService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MiniWorldServiceTest {
    private final MiniWorldService service = new MiniWorldService();
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonCodec json = new JsonCodec();
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"AcademicWriter", "ResearchWriter", "FinanceWriter", "PersonalWriter", "GenericWriter"})
    void returnsCommonAndSelectedDomainAsCompactJson(String writerId) throws IOException {
        String compact = service.buildCompactKnowledgeContext(writerId);
        var knowledge = mapper.readTree(compact);
        assertEquals(2, knowledge.size());
        assertEquals("COMMON_WORLD", knowledge.at("/commonKnowledge/worldId").textValue());
        assertEquals(writerId, knowledge.at("/domainKnowledge/worldId").textValue());
        assertTrue(knowledge.at("/domainKnowledge/state").isObject());
        assertTrue(knowledge.at("/domainKnowledge/informationLookup").isObject());
        assertTrue(knowledge.at("/domainKnowledge/policies").isArray());
        assertTrue(knowledge.at("/domainKnowledge/unknownPolicy").isTextual());
        assertEquals(mapper.writeValueAsString(knowledge), compact);
        assertEquals(1, compact.lines().count());
        assertEquals(compact, service.buildCompactKnowledgeContext(writerId));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"UnknownWriter", "../common_miniworld.json", ""})
    void rejectsIdsWithoutAnAssociatedWorld(String writerId) {
        var error = assertThrows(IllegalArgumentException.class, () -> service.buildCompactKnowledgeContext(writerId));
        assertTrue(error.getMessage().contains(writerId == null ? "selectedAgentId" : "No MiniWorld"));
    }

    @Test
    void selectedRouteSendsAnalysisProfileAndOnlyTheSelectedKnowledge() throws Exception {
        var profile = WriterProfiles.get("AcademicWriter");
        String email = "Bonjour, quand les notes du cours INF312 seront-elles publiées ?";
        var parserInput = new ParserInput("email-1", "thread-1", email);
        var content = new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of("NOTES"),
                List.of(new EmailAnalysis.Entity("COURSE", "INF312")), List.of("date_publication_notes"),
                List.of(), List.of(), List.of(), List.of());
        var fact = new ResponsePlan.Fact("2026-11-18", "domainKnowledge.informationLookup.date_publication_notes");
        var fake = new FakeLlmGateway(json)
                .enqueueJson(json.write(content))
                .enqueueJson(json.write(TestData.routeContent(profile.agentId(), List.of("Évaluation universitaire"))))
                .enqueueJson(json.write(TestData.planContent(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST, List.of(fact))));
        var requests = new ArrayList<LlmRequest<?>>();
        LlmGateway gateway = new LlmGateway() {
            @Override
            public <T> AgentResult<T> generate(LlmRequest<T> request, TaskContext task, String component) {
                requests.add(request);
                return fake.generate(request, task, component);
            }
        };
        var worlds = new FileWorldStore(directory, json);
        var factory = new AgentFactory(gateway, worlds);
        var parser = factory.createParser(AgentFactoryTest.config("Parser", AgentRole.PARSER));
        var decision = factory.createDecision(AgentFactoryTest.config("Decision", AgentRole.DECISION));
        var orchestrator = new OrchestratorAgent(worlds.sharedWorld(), new MessageFactory(Clock.systemUTC()));
        var parseTask = TaskContext.start();
        var parsed = orchestrator.execute(parser, parserInput, parseTask, Contract.EMAIL_ANALYSIS, value -> {});
        if (!(parsed instanceof Success(var analysisMessage, var rawIgnored0))) throw new AssertionError("Expected an analysis: " + parsed);
        var analysis = analysisMessage.payload();
        var decisionInput = DecisionInput.forAuthorizedWriters(analysis, List.of(profile.agentId(), "FinanceWriter"));
        var writerInput = new WriterInput(analysis);
        var routeTask = new TaskContext(parseTask.correlationId(), UUID.randomUUID(), UUID.randomUUID());
        var result = orchestrator.execute(decision, decisionInput, routeTask, Contract.ROUTE_DECISION,
                new RouteDecisionValidator(analysis.emailId(), decisionInput.availableWriters()));
        if (!(result instanceof Success(var routeMessage, var rawIgnored1))) throw new AssertionError("Expected a route: " + result);
        var route = routeMessage.payload();
        var writer = factory.createWriter(decisionInput.availableWriters().getFirst());
        var other = factory.createWriter(decisionInput.availableWriters().getLast());
        var writerTask = new TaskContext(routeTask.correlationId(), UUID.randomUUID(), UUID.randomUUID());
        var knowledge = mapper.readTree(service.buildCompactKnowledgeContext(profile.agentId()));
        var validation = new ResponsePlanValidator(item -> item.sourceRef().equals("domainKnowledge")
                && item.text().equals(knowledge.at("/domainKnowledge/informationLookup/date_publication_notes").textValue()));

        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.executeWriter(route, other, writerInput, writerTask, validation));
        var pending = new RouteDecision.Content(profile.agentId(), List.of(profile.agentId()),
                RouteDecision.DecisionStatus.SELECTED, List.of("Évaluation"), null, true, "Confirmer ?")
                .enrich(analysis.emailId());
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.executeWriter(pending, writer, writerInput, writerTask, validation));
        assertInstanceOf(Success.class, orchestrator.executeWriter(route, writer, writerInput, writerTask, validation));
        assertEquals(3, fake.callCount());
        assertEquals(email, requests.getFirst().input());
        var decisionPayload = mapper.readTree(requests.get(1).input());
        assertEquals(2, decisionPayload.size());
        assertEquals(mapper.valueToTree(decisionInput.availableWriters()), decisionPayload.path("availableWriters"));
        var writerRequest = requests.getLast();
        assertEquals(Contract.RESPONSE_PLAN_CONTENT, writerRequest.expectedContract());
        assertTrue(writerRequest.instructions().startsWith("# SYSTEM PROMPT — WriterAgent V1.0"));
        assertEquals(1, writerRequest.input().lines().count());
        JsonNode payload = mapper.readTree(writerRequest.input());
        assertEquals(List.of("emailAnalysis", "writerProfile", "miniWorlds"),
                payload.properties().stream().map(Map.Entry::getKey).toList());
        assertEquals(mapper.valueToTree(analysis), payload.path("emailAnalysis"));
        assertEquals(mapper.valueToTree(profile), payload.path("writerProfile"));
        assertEquals(knowledge, payload.path("miniWorlds"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"otherWriterKnowledge", "emailAnalysis.senderClaims",
            "domainKnowledge.state.projects.ORION.meetingRoom", "domainKnowledge.informationLookup.unknown_key"})
    void writerRejectsUnknownOrEmptySources(String sourceRef) throws IOException {
        var fact = new ResponsePlan.Fact("Une salle a été réservée.", sourceRef);
        var fake = new FakeLlmGateway(json)
                .enqueueJson(json.write(TestData.planContent(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST, List.of(fact))));
        var factory = new AgentFactory(fake, new FileWorldStore(directory, json));
        var writer = factory.createWriter(WriterProfiles.get("ResearchWriter").toAgentConfig());
        var input = new WriterInput(TestData.analysis("fr", "REQUEST", List.of()));
        var error = assertInstanceOf(ErrorPlan.class, writer.execute(input, TaskContext.start()));
        assertEquals(ErrorCode.INVALID_OUTPUT, error.code());
        assertEquals(1, fake.callCount());
    }

    @Test
    void writerCanAcknowledgeAnAnnouncedFutureDeadline() throws IOException {
        var action = new EmailAnalysis.RequestedAction("SUBMIT_CAMERA_READY", "Submit the camera-ready version", "December 18");
        var analysis = new EmailAnalysis.Content("en", "NOTIFY_ACCEPTANCE", List.of("CONFERENCE"),
                List.of(), List.of(), List.of(action), List.of("The paper was accepted."), List.of(), List.of())
                .enrich("email-1", "thread-1");
        var fact = new ResponsePlan.Fact("Acknowledge the December 18 camera-ready deadline.",
                "emailAnalysis.requestedActions");
        var fake = new FakeLlmGateway(json)
                .enqueueJson(json.write(TestData.planContent(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST, List.of(fact))));
        var writer = new AgentFactory(fake, new FileWorldStore(directory, json))
                .createWriter(WriterProfiles.get("ResearchWriter").toAgentConfig());
        var result = writer.execute(new WriterInput(analysis), TaskContext.start());
        if (!(result instanceof Success(var plan, var rawIgnored2))) throw new AssertionError("Expected an acknowledgement: " + result);
        assertEquals(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST, plan.responseIntent());
        assertFalse(plan.requiresClarification());
        assertEquals(List.of(fact), plan.factsToCommunicate());
        assertEquals(1, fake.callCount());
    }

    @Test
    void writerClarificationKeepsTheMissingKeyForTheOrchestrator() throws IOException {
        var key = "salle_reunion_orion";
        var analysis = new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of("MEETING"),
                List.of(), List.of(key), List.of(), List.of(), List.of(), List.of())
                .enrich("email-1", "thread-1");
        var content = new ResponsePlan.Content(ResponsePlan.Intent.REQUEST_CLARIFICATION, List.of(), List.of(key), true);
        var fake = new FakeLlmGateway(json).enqueueJson(json.write(content));
        var worlds = new FileWorldStore(directory, json);
        var writer = new AgentFactory(fake, worlds).createWriter(WriterProfiles.get("ResearchWriter").toAgentConfig());
        var route = TestData.routeContent(writer.id(), List.of("ORION")).enrich(analysis.emailId());
        var orchestrator = new OrchestratorAgent(worlds.sharedWorld(), new MessageFactory(Clock.systemUTC()));
        var result = orchestrator.executeWriter(route, writer, new WriterInput(analysis),
                TaskContext.start(), new ResponsePlanValidator());
        var discussion = assertInstanceOf(DiscussionPlan.class, result);
        assertEquals(List.of("Que dois-je indiquer au sujet de « salle reunion orion » ?"), discussion.questions());
        assertEquals(1, fake.callCount());
    }
}
