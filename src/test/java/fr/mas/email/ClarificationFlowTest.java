package fr.mas.email;

import fr.mas.email.agent.*;
import fr.mas.email.contract.*;
import fr.mas.email.llm.*;
import fr.mas.email.orchestration.*;
import fr.mas.email.result.*;
import fr.mas.email.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClarificationFlowTest {
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();
    private final MessageFactory messages = new MessageFactory(Clock.systemUTC());

    @Test
    void scribeRejectsAnIncompletePlanBeforeInvokingItsBehavior() throws Exception {
        var worlds = new FileWorldStore(directory, json);
        var gateway = new FakeLlmGateway(json);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var scribe = new AgentFactory(gateway, worlds).createScribe(AgentFactoryTest.config("Scribe", AgentRole.SCRIBE),
                (input, context) -> { calls.incrementAndGet(); return new Success<>(TestData.draft("Re", "Body")); });
        var plan = new ResponsePlan.Content(ResponsePlan.Intent.REQUEST_CLARIFICATION, List.of(), List.of("Date?"), true)
                .enrich("Writer", "email-001", List.of(), "fr", "formal");
        var error = assertInstanceOf(ErrorPlan.class, scribe.execute(plan, TaskContext.start()));
        assertEquals(ErrorCode.INVALID_OUTPUT, error.code()); assertEquals(ErrorPlan.Stage.SCRIBE, error.stage());
        assertEquals(0, calls.get()); assertEquals(0, gateway.callCount());
    }

    @Test
    void initialWriterJsonBecomesMultipleQuestionsAndSurvivesRestartWithoutAnotherLlmCall() throws Exception {
        var content = new ResponsePlan.Content(ResponsePlan.Intent.REQUEST_CLARIFICATION, List.of(),
                List.of("Quelle date ?", "Quelle salle ?"), true);
        var gateway = new FakeLlmGateway(json).enqueueJson(json.write(content));
        var worlds = new FileWorldStore(directory, json);
        var factory = new AgentFactory(gateway, worlds);
        var request = new LlmRequest<>("Test fixture", "Synthetic", Contract.RESPONSE_PLAN_CONTENT, Duration.ofSeconds(1));
        var writer = factory.createWriter(AgentFactoryTest.config("Writer", AgentRole.WRITER), (input, context) ->
                context.llm().generate(request, context.task(), context.configuration().agentId())
                        .map(value -> value.enrich("Writer", input.analysis().emailId(), List.of(), "fr", "formal")));
        var orchestrator = new OrchestratorAgent(worlds.sharedWorld(), messages);
        var task = TaskContext.start();
        var result = orchestrator.execute(writer, AgentFactoryTest.input(), task, Contract.RESPONSE_PLAN, new ResponsePlanValidator());
        var discussion = assertInstanceOf(DiscussionPlan.class, result);
        assertEquals(content.missingInformation(), discussion.questions());
        assertEquals(MemoryProposal.NONE, discussion.memoryProposal());
        assertEquals(task, discussion.task()); assertEquals(1, gateway.callCount());
        var suspended = orchestrator.suspendedDiscussion().orElseThrow();
        var initial = json.readMessage(suspended.initialResultJson(), Contract.RESPONSE_PLAN);
        assertTrue(initial.payload().requiresClarification());
        assertEquals(content.missingInformation(), initial.payload().missingInformation());
        assertEquals(discussion, suspended.request().payload());
        assertEquals(suspended.request(), json.readMessage(json.write(suspended.request()), Contract.DISCUSSION_PLAN));

        var restarted = new OrchestratorAgent(worlds.sharedWorld(), messages);
        assertEquals(suspended, restarted.suspendedDiscussion().orElseThrow());
        assertThrows(IllegalStateException.class, () -> restarted.execute(writer, AgentFactoryTest.input(),
                task.nextAttempt(), Contract.RESPONSE_PLAN, new ResponsePlanValidator()));
        assertThrows(IllegalStateException.class, () -> restarted.resumeWithAnswer(
                UserDiscussionAnswer.withoutStorage(UUID.randomUUID(), "Lundi, salle A")));
        var requestId = suspended.request().metadata().messageId();
        assertThrows(IllegalStateException.class, () -> restarted.resumeWithAnswer(new UserDiscussionAnswer(requestId,
                UserDiscussionAnswer.Action.ANSWER_INFORMATION, "Lundi, salle A",
                UserDiscussionAnswer.MemoryChoice.K_PERSO, "Writer")));
        assertEquals(SharedWorld.Status.WAITING_USER, restarted.state().orElseThrow().status());
        var resumed = restarted.resumeWithAnswer(UserDiscussionAnswer.withoutStorage(requestId, "Lundi, salle A"));
        assertEquals("Lundi, salle A", resumed.userAnswer());
        assertEquals(task.taskId(), resumed.task().taskId());
        assertEquals(task.correlationId(), resumed.task().correlationId());
        assertNotEquals(task.attemptId(), resumed.task().attemptId());
        assertEquals(DiscussionResume.StorageResult.NOT_STORED, resumed.discussionResume().storageResult());
        assertEquals(1, gateway.callCount());
        var readyAfterRestart = new OrchestratorAgent(worlds.sharedWorld(), messages);
        var persistedResume = readyAfterRestart.suspendedDiscussion().orElseThrow();
        assertEquals(suspended.request(), persistedResume.request());
        assertEquals(suspended.initialResultJson(), persistedResume.initialResultJson());
        assertEquals(resumed.discussionResume(), persistedResume.resume());
        assertTrue(worlds.miniWorld("Writer", false).load().isEmpty());
        gateway.enqueueJson(json.write(TestData.planContent(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST, List.of())));
        assertInstanceOf(Success.class, readyAfterRestart.execute(writer, AgentFactoryTest.input(), resumed.task(),
                Contract.RESPONSE_PLAN, new ResponsePlanValidator()));
        assertEquals(2, gateway.callCount());
        assertTrue(readyAfterRestart.suspendedDiscussion().isEmpty());
    }

    @Test
    void routingAndCorrectionNormalizeTheirOwnQuestionsAndMemoryProposal() {
        var task = TaskContext.start();
        var route = new RouteDecision.Content("Writer", List.of("Writer"), RouteDecision.DecisionStatus.SELECTED,
                List.of("Topic match"), null, true, "Utiliser Writer ?").enrich("email-001");
        var routeDiscussion = assertInstanceOf(DiscussionPlan.class, DiscussionPlan.normalize(route, task));
        assertEquals(List.of("Utiliser Writer ?"), routeDiscussion.questions());
        assertEquals(MemoryProposal.NONE, routeDiscussion.memoryProposal());
        var correction = new CorrectionPlan(CorrectionPlan.Target.ORCHESTRATOR, CorrectionPlan.Type.UPDATE_KNOWLEDGE,
                true, "Confirmer la date", List.of(new KnowledgeProposal(KnowledgeProposal.Scope.COMMON, "date", "Lundi")),
                "Date fournie par l’utilisateur");
        DiscussionPlan<?> discussion = assertInstanceOf(DiscussionPlan.class, DiscussionPlan.normalize(correction, task));
        assertEquals(List.of("date : Lundi"), discussion.questions());
        assertEquals(correction.reason(), discussion.reason());
        assertEquals(MemoryProposal.ASK_SAVE_SCOPE, discussion.memoryProposal());
        assertEquals(discussion, discussion.map(value -> { fail("Clarification must not become a success"); return value; }));
    }

    @Test
    void parserRequestedInformationDoesNotSuspend() throws Exception {
        var content = new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of(), List.of(), List.of("date"),
                List.of(), List.of(), List.of("Date incertaine"), List.of());
        var gateway = new FakeLlmGateway(json).enqueueJson(json.write(content));
        var worlds = new FileWorldStore(directory, json);
        var parser = new AgentFactory(gateway, worlds).createParser(AgentFactoryTest.config("Parser", AgentRole.PARSER));
        var orchestrator = new OrchestratorAgent(worlds.sharedWorld(), messages);
        assertInstanceOf(Success.class, orchestrator.execute(parser,
                new ParserInput("email-001", "thread-001", "Quelle est la date ?"), TaskContext.start(),
                Contract.EMAIL_ANALYSIS, value -> {}));
        assertEquals(SharedWorld.Status.COMPLETED, orchestrator.state().orElseThrow().status());
        assertTrue(orchestrator.suspendedDiscussion().isEmpty()); assertEquals(1, gateway.callCount());
    }

    @Test
    void invalidLlmOutputStopsTheAttemptWithoutCreatingQuestionsOrRetrying() throws Exception {
        var gateway = new FakeLlmGateway(json).enqueueJson("{}");
        var worlds = new FileWorldStore(directory, json);
        var writer = new AgentFactory(gateway, worlds).createWriter(AgentFactoryTest.config("Writer", AgentRole.WRITER),
                (input, context) -> context.llm().generate(new LlmRequest<>("Test", "Synthetic",
                                Contract.RESPONSE_PLAN_CONTENT, Duration.ofSeconds(1)), context.task(), "Writer")
                        .map(value -> value.enrich("Writer", "email-001", List.of(), "fr", "formal")));
        var orchestrator = new OrchestratorAgent(worlds.sharedWorld(), messages);
        var task = TaskContext.start();
        var error = assertInstanceOf(ErrorPlan.class, orchestrator.execute(writer, AgentFactoryTest.input(), task,
                Contract.RESPONSE_PLAN, new ResponsePlanValidator()));
        assertEquals(ErrorCode.INVALID_JSON, error.code());
        assertEquals(SharedWorld.Status.FAILED, orchestrator.state().orElseThrow().status());
        assertTrue(orchestrator.suspendedDiscussion().isEmpty());
        assertThrows(IllegalStateException.class, () -> orchestrator.resumeWithAnswer(task.taskId(), "Answer"));
        assertThrows(IllegalStateException.class, () -> orchestrator.execute(writer, AgentFactoryTest.input(), task.nextAttempt(),
                Contract.RESPONSE_PLAN, new ResponsePlanValidator()));
        assertEquals(1, gateway.callCount());
    }

    @Test
    void parserCannotBypassItsContractWithADirectQuestion() throws Exception {
        var worlds = new FileWorldStore(directory, json);
        var parser = new AgentFactory(new FakeLlmGateway(json), worlds).createParser(
                AgentFactoryTest.config("Parser", AgentRole.PARSER),
                (input, context) -> new DiscussionPlan<>("Question?", "Reason", context.task()));
        var orchestrator = new OrchestratorAgent(worlds.sharedWorld(), messages);
        var error = assertInstanceOf(ErrorPlan.class, orchestrator.execute(parser, "Synthetic", TaskContext.start(),
                Contract.EMAIL_ANALYSIS, value -> {}));
        assertEquals(ErrorCode.INVALID_OUTPUT, error.code());
        assertEquals(ErrorPlan.Stage.PARSER, error.stage());
        assertEquals(ErrorPlan.ReportedBy.EXECUTION_CONTROL, error.reportedBy());
        assertTrue(orchestrator.suspendedDiscussion().isEmpty());
    }
}
