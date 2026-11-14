package fr.mas.email;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fr.mas.email.agent.*;
import fr.mas.email.contract.*;
import fr.mas.email.llm.*;
import fr.mas.email.orchestration.*;
import fr.mas.email.result.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class BusinessValidationTest {
    private final JsonCodec json = new JsonCodec();
    private final JsonMapper tree = new JsonMapper();

    private ObjectNode payload(String name) throws Exception {
        return (ObjectNode) tree.readTree(Files.readString(Path.of("docs/examples/valid", name + ".json"))).get("payload");
    }

    @TestFactory
    Stream<DynamicTest> correctionTargetActionPairsAreExhaustive() {
        Map<CorrectionPlan.Target, CorrectionPlan.Type> allowed = Map.of(
                CorrectionPlan.Target.PARSER_AGENT, CorrectionPlan.Type.REPARSE_EMAIL,
                CorrectionPlan.Target.DECISION_AGENT, CorrectionPlan.Type.REROUTE_DOMAIN,
                CorrectionPlan.Target.WRITER_AGENT, CorrectionPlan.Type.REVISE_PLAN,
                CorrectionPlan.Target.SCRIBE_AGENT, CorrectionPlan.Type.ADJUST_STYLE,
                CorrectionPlan.Target.ORCHESTRATOR, CorrectionPlan.Type.UPDATE_KNOWLEDGE);
        var tests = new ArrayList<DynamicTest>();
        for (var target : CorrectionPlan.Target.values()) for (var action : CorrectionPlan.Type.values()) {
            tests.add(DynamicTest.dynamicTest(target + "/" + action, () -> {
                boolean memory = target == CorrectionPlan.Target.ORCHESTRATOR;
                var data = payload(memory ? "correction-plan.clarification" : "correction-plan.ready")
                        .put("targetAgent", target.name()).put("correctionType", action.name());
                if (allowed.get(target) == action) assertDoesNotThrow(() -> json.read(data.toString(), CorrectionPlan.class));
                else assertThrows(InvalidOutputException.class, () -> json.read(data.toString(), CorrectionPlan.class));
            }));
        }
        return tests.stream();
    }

    @Test
    void onlyMemoryCorrectionsRequireValidationAndProposals() throws Exception {
        var memory = payload("correction-plan.clarification");
        var style = payload("correction-plan.ready");
        var unconfirmed = memory.deepCopy().put("requiresUserValidation", false);
        var empty = memory.deepCopy();
        empty.putArray("memoryProposals");
        var unexpected = style.deepCopy().set("memoryProposals", memory.get("memoryProposals"));
        for (var invalid : List.of(unconfirmed, empty, unexpected)) {
            assertThrows(InvalidOutputException.class, () -> json.read(invalid.toString(), CorrectionPlan.class));
        }
    }

    @Test
    void selectedWriterMustBeACandidateAndEveryCandidateMustBeAuthorizedAndAvailable() throws Exception {
        var data = payload("route-decision.selected");
        data.put("selectedAgentId", "Intruder");
        assertThrows(InvalidOutputException.class, () -> json.read(data.toString(), RouteDecision.class));
        var route = TestData.route("Writer", List.of("Topic match"));
        var validator = new RouteDecisionValidator(route.emailId(), List.of(AgentFactoryTest.config("Writer", AgentRole.WRITER)));
        assertDoesNotThrow(() -> validator.validate(route));
        assertThrows(InvalidOutputException.class, () -> new RouteDecisionValidator("other-email", List.of()).validate(route));
        assertThrows(InvalidOutputException.class, () -> new RouteDecisionValidator(route.emailId(), List.of()).validate(route));
        var extraCandidate = new RouteDecision.Content("Writer", List.of("Writer", "Intruder"),
                RouteDecision.DecisionStatus.SELECTED, List.of("Topic"), null, false, null).enrich(route.emailId());
        assertThrows(InvalidOutputException.class, () -> validator.validate(extraCandidate));
        assertThrows(InvalidOutputException.class, () -> new RouteDecisionValidator(route.emailId(),
                List.of(AgentFactoryTest.config("Parser", AgentRole.PARSER))));
    }

    @Test
    void routingConfirmationCannotBeConfusedWithAutomaticAttribution() throws Exception {
        var selected = payload("route-decision.selected");
        selected.put("requiresUserConfirmation", true).put("questionToUser", "Use this Writer?");
        var route = json.read(selected.toString(), RouteDecision.class);
        assertInstanceOf(DiscussionPlan.class, DiscussionPlan.normalize(route, TaskContext.start()));
        selected.putNull("questionToUser");
        assertThrows(InvalidOutputException.class, () -> json.read(selected.toString(), RouteDecision.class));
        var clarification = payload("route-decision.clarification");
        clarification.putArray("candidateAgents");
        assertDoesNotThrow(() -> json.read(clarification.toString(), RouteDecision.class));
        clarification.put("requiresUserConfirmation", false);
        assertThrows(InvalidOutputException.class, () -> json.read(clarification.toString(), RouteDecision.class));
        var confidence = payload("route-decision.selected").put("confidence", 0.95);
        assertThrows(InvalidOutputException.class, () -> json.read(confidence.toString(), RouteDecision.class));
    }

    @Test
    void writerIntentFactsAndClarificationsAreConsistent() throws Exception {
        var ready = payload("response-plan.ready");
        ready.putArray("factsToCommunicate");
        assertThrows(InvalidOutputException.class, () -> json.read(ready.toString(), ResponsePlan.class));
        ready.put("responseIntent", "PROVIDE_DOCUMENT");
        assertThrows(InvalidOutputException.class, () -> json.read(ready.toString(), ResponsePlan.class));
        ready.put("responseIntent", "ACKNOWLEDGE_REQUEST");
        assertDoesNotThrow(() -> json.read(ready.toString(), ResponsePlan.class));
        ready.putArray("missingInformation").add("Which date?");
        assertThrows(InvalidOutputException.class, () -> json.read(ready.toString(), ResponsePlan.class));
        ready.put("requiresClarification", true);
        assertThrows(InvalidOutputException.class, () -> json.read(ready.toString(), ResponsePlan.class));
        ready.put("responseIntent", "REQUEST_CLARIFICATION");
        var clarification = json.read(ready.toString(), ResponsePlan.class);
        assertInstanceOf(DiscussionPlan.class, DiscussionPlan.normalize(clarification, TaskContext.start()));
        assertThrows(InvalidOutputException.class, () -> new DraftContent("Re", "Body").enrich(clarification));
    }

    @Test
    void factsNeedExistingAuthorizedSourcesThatActuallySupportTheirText() {
        var fact = new ResponsePlan.Fact("Meeting Monday", "calendar:authorized");
        var plan = TestData.plan(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST, List.of(fact));
        var evidence = Map.of("calendar:authorized", "Meeting Monday");
        var validator = new ResponsePlanValidator(item -> item.text().equals(evidence.get(item.sourceRef())));
        assertDoesNotThrow(() -> validator.validate(plan));
        assertThrows(InvalidOutputException.class, () -> new ResponsePlanValidator().validate(plan));
        for (var unsupported : List.of(new ResponsePlan.Fact("Meeting Monday", "unknown"),
                new ResponsePlan.Fact("Meeting Monday", "calendar:private-other-agent"),
                new ResponsePlan.Fact("Meeting Tuesday", "calendar:authorized"))) {
            assertThrows(InvalidOutputException.class, () -> validator.validate(
                    TestData.plan(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST, List.of(unsupported))));
        }
    }

    @Test
    void draftMustMatchCurrentReadyPlanAndPassTheProvidedFidelityCheck() {
        var plan = TestData.plan(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST, List.of());
        var draft = new DraftContent("Re", "Received").enrich(plan);
        var validator = new DraftResponseValidator(plan, (p, d) -> d.body().equals("Received"));
        assertDoesNotThrow(() -> validator.validate(draft));
        for (var invalid : List.of(new DraftResponse("other-email", plan.planId(), "Re", "Received", DraftResponse.Status.DRAFT),
                new DraftResponse(plan.emailId(), "old-plan", "Re", "Received", DraftResponse.Status.DRAFT),
                new DraftContent("Re", "Invented fact").enrich(plan))) {
            assertThrows(InvalidOutputException.class, () -> validator.validate(invalid));
        }
        var changed = TestData.plan(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST, List.of());
        assertThrows(InvalidOutputException.class, () -> new DraftResponseValidator(changed, (p, d) -> true).validate(draft));
    }

    @Test
    void malformedJsonAndBusinessErrorsAreSeparateFromClarificationsWithOneCallOnly() {
        var request = new LlmRequest<>("Test", "Synthetic", Contract.RESPONSE_PLAN_CONTENT, Duration.ofSeconds(1));
        String emptyAnswer = "{\"responseIntent\":\"ANSWER_INFORMATION_REQUEST\",\"factsToCommunicate\":[],\"missingInformation\":[],\"requiresClarification\":false}";
        var gateway = new FakeLlmGateway(json).enqueueJson(emptyAnswer);
        var invalid = assertInstanceOf(ErrorPlan.class, gateway.generate(request, TaskContext.start(), "Writer"));
        assertEquals(ErrorCode.INVALID_OUTPUT, invalid.code()); assertEquals(1, gateway.callCount());
        assertEquals(ErrorPlan.Stage.WRITER, invalid.stage()); assertEquals("Writer", invalid.agentId());
        assertEquals("FakeLlmGateway", invalid.component()); assertNull(invalid.traceRef());
        assertEquals(ErrorPlan.Status.STOPPED, invalid.status()); assertTrue(invalid.requiresUserDecision());
        gateway.enqueueJson("{\"responseIntent\":\"UNKNOWN\"}");
        var malformed = assertInstanceOf(ErrorPlan.class, gateway.generate(request, TaskContext.start(), "Writer"));
        assertEquals(ErrorCode.INVALID_JSON, malformed.code());
    }

    @Test
    void everyErrorCodeSerializesWithoutAllowingModelOrUserToResumeImplicitly() {
        var task = TaskContext.start();
        var messages = new MessageFactory(Clock.systemUTC());
        for (var code : ErrorCode.values()) {
            var error = Errors.create(code, "ExecutionControl", task);
            var message = messages.create(Contract.ERROR_PLAN, error, task.correlationId(), "OrchestratorAgent");
            assertEquals(message, json.readMessage(json.write(message), Contract.ERROR_PLAN));
        }
        assertEquals(ErrorCode.LLM_TIMEOUT, Errors.from(new java.util.concurrent.TimeoutException(), "Gateway", task).code());
    }

    @Test
    void memoryChoiceAndStorageResultRemainDistinctAndTyped() {
        var request = UUID.randomUUID();
        assertEquals(UserDiscussionAnswer.MemoryChoice.NONE, UserDiscussionAnswer.withoutStorage(request, "Answer").memoryChoice());
        for (var choice : UserDiscussionAnswer.MemoryChoice.values()) {
            var answer = new UserDiscussionAnswer(request, UserDiscussionAnswer.Action.ANSWER_INFORMATION,
                    "Answer", choice, choice == UserDiscussionAnswer.MemoryChoice.K_PERSO ? "Writer" : null);
            assertEquals(answer, json.read(json.write(answer), UserDiscussionAnswer.class));
        }
        assertThrows(InvalidOutputException.class, () -> new UserDiscussionAnswer(request,
                UserDiscussionAnswer.Action.ANSWER_INFORMATION, "Answer", UserDiscussionAnswer.MemoryChoice.K_PERSO, null));
        assertThrows(InvalidOutputException.class, () -> new UserDiscussionAnswer(request,
                UserDiscussionAnswer.Action.ANSWER_INFORMATION, "Answer", UserDiscussionAnswer.MemoryChoice.K_COMMUN, "Writer"));
        var resume = DiscussionResume.withoutStorage(TaskContext.start(), "Answer");
        assertEquals(DiscussionResume.StorageResult.NOT_STORED, resume.storageResult());
    }
}
