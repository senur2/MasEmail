package fr.mas.email;

import fr.mas.email.agent.*;
import fr.mas.email.contract.*;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.orchestration.*;
import fr.mas.email.result.*;
import fr.mas.email.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OrchestratorTest {
    @TempDir Path directory;
    private final MessageFactory messages = new MessageFactory(Clock.systemUTC());
    private final ResponsePlanValidator validation = new ResponsePlanValidator();
    private final ResponsePlan plan = TestData.plan(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST, List.of());

    private FileWorldStore store() throws IOException { return new FileWorldStore(directory, new JsonCodec()); }

    private AgentFactory factory() throws IOException {
        return new AgentFactory(new FakeLlmGateway(new JsonCodec()), store());
    }

    private WriterAgent writer(AgentBehavior<WriterInput, ResponsePlan> behavior) throws IOException {
        return factory().createWriter(AgentFactoryTest.config("Writer", AgentRole.WRITER), behavior);
    }

    @Test
    void successfulWriterProducesJavaEnvelopeAndScribeCanBeCalledExplicitly() throws Exception {
        var orchestrator = new OrchestratorAgent(store().sharedWorld(), messages);
        var task = TaskContext.start();
        var result = orchestrator.execute(writer((input, context) -> new Success<>(plan)),
                AgentFactoryTest.input(), task, Contract.RESPONSE_PLAN, validation);
        var success = assertInstanceOf(Success.class, result);
        var message = assertInstanceOf(AgentMessage.class, success.value());
        assertEquals(plan, message.payload());
        assertEquals("Writer", message.metadata().producerAgentId());
        assertEquals(task.correlationId(), message.metadata().correlationId());
        assertEquals(SharedWorld.Status.COMPLETED, orchestrator.state().orElseThrow().status());

        var scribe = factory().createScribe(AgentFactoryTest.config("Scribe", AgentRole.SCRIBE),
                (input, context) -> new Success<>(TestData.draft("Re", "Bonjour")));
        var nextTask = new TaskContext(task.correlationId(), UUID.randomUUID(), UUID.randomUUID());
        var draft = orchestrator.execute(scribe, (ResponsePlan) message.payload(), nextTask,
                Contract.DRAFT_RESPONSE, value -> assertFalse(value.body().isBlank()));
        assertInstanceOf(Success.class, draft);
    }

    @Test
    void clarificationBlocksScribeAndResumeRequiresMatchingUserAnswer() throws Exception {
        var orchestrator = new OrchestratorAgent(store().sharedWorld(), messages);
        var task = TaskContext.start();
        var writer = writer((input, context) ->
                new DiscussionPlan<>("Quelle date ?", "Date nécessaire", context.task()));
        assertInstanceOf(DiscussionPlan.class, orchestrator.execute(
                writer, AgentFactoryTest.input(), task, Contract.RESPONSE_PLAN, validation));
        var calls = new AtomicInteger();
        var scribe = factory().createScribe(AgentFactoryTest.config("Scribe", AgentRole.SCRIBE),
                (input, context) -> { calls.incrementAndGet(); return new Success<>(TestData.draft("Re", "Body")); });
        assertThrows(IllegalStateException.class, () ->
                orchestrator.execute(scribe, plan, TaskContext.start(), Contract.DRAFT_RESPONSE, value -> {}));
        assertEquals(0, calls.get());
        assertThrows(IllegalStateException.class, () -> orchestrator.authorizeRetry(task.taskId()));
        assertThrows(IllegalStateException.class, () -> orchestrator.resumeWithAnswer(UUID.randomUUID(), "Lundi"));
        assertThrows(IllegalArgumentException.class, () -> orchestrator.resumeWithAnswer(task.taskId(), " "));
        var resumed = orchestrator.resumeWithAnswer(task.taskId(), "Lundi");
        assertEquals("Lundi", resumed.userAnswer());
        assertEquals(task.taskId(), resumed.task().taskId());
        assertNotEquals(task.attemptId(), resumed.task().attemptId());
        assertThrows(IllegalStateException.class, () -> orchestrator.execute(
                scribe, plan, resumed.task(), Contract.DRAFT_RESPONSE, value -> {}));
        assertThrows(IllegalStateException.class, () -> orchestrator.execute(
                writer, AgentFactoryTest.input(), task, Contract.RESPONSE_PLAN, validation));
        var completedWriter = writer((input, context) -> new Success<>(plan));
        assertInstanceOf(Success.class, orchestrator.execute(completedWriter,
                AgentFactoryTest.input(), resumed.task(), Contract.RESPONSE_PLAN, validation));
        assertTrue(store().miniWorld("Writer", false).load().isEmpty());
    }

    @Test
    void timeoutStopsExecutionUntilExplicitRetryAndDoesNotReachScribe() throws Exception {
        var calls = new AtomicInteger();
        var orchestrator = new OrchestratorAgent(store().sharedWorld(), messages);
        var task = TaskContext.start();
        var writer = writer((input, context) -> {
            calls.incrementAndGet();
            return Errors.create(ErrorCode.LLM_TIMEOUT, "Writer", context.task());
        });
        var result = orchestrator.execute(writer, AgentFactoryTest.input(), task, Contract.RESPONSE_PLAN, validation);
        assertEquals(ErrorCode.LLM_TIMEOUT, ((ErrorPlan<?>) result).code());
        assertThrows(IllegalStateException.class, () -> orchestrator.execute(
                writer, AgentFactoryTest.input(), task.nextAttempt(), Contract.RESPONSE_PLAN, validation));
        assertEquals(1, calls.get());
        assertThrows(IllegalStateException.class, () -> orchestrator.resumeWithAnswer(task.taskId(), "Yes"));
        var resumed = orchestrator.authorizeRetry(task.taskId());
        assertEquals(1, calls.get());
        orchestrator.execute(writer, AgentFactoryTest.input(), resumed.task(), Contract.RESPONSE_PLAN, validation);
        assertEquals(2, calls.get());
    }

    @Test
    void pendingDiscussionAndErrorSurviveRestart() throws Exception {
        var task = TaskContext.start();
        var orchestrator = new OrchestratorAgent(store().sharedWorld(), messages);
        orchestrator.execute(writer((input, context) -> new DiscussionPlan<>("Question ?", "Reason", context.task())),
                AgentFactoryTest.input(), task, Contract.RESPONSE_PLAN, validation);
        var restarted = new OrchestratorAgent(store().sharedWorld(), messages);
        assertEquals(SharedWorld.Status.WAITING_USER, restarted.state().orElseThrow().status());
        assertEquals(List.of("Question ?"), restarted.suspendedDiscussion().orElseThrow().request().payload().questions());
        var resumed = restarted.resumeWithAnswer(task.taskId(), "Answer");
        restarted.execute(writer((input, context) -> Errors.create(ErrorCode.INTERNAL_ERROR, "Writer", context.task())),
                AgentFactoryTest.input(), resumed.task(), Contract.RESPONSE_PLAN, validation);
        var afterError = new OrchestratorAgent(store().sharedWorld(), messages);
        assertEquals(SharedWorld.Status.FAILED, afterError.state().orElseThrow().status());
        assertNotNull(afterError.authorizeRetry(task.taskId()));
    }

    @Test
    void interruptedRunningSnapshotIsNeverReplayedAutomatically() throws Exception {
        var task = TaskContext.start();
        store().sharedWorld().save(new SharedWorld(task, "Writer", SharedWorld.Status.RUNNING, ""));
        var orchestrator = new OrchestratorAgent(store().sharedWorld(), messages);
        assertEquals(SharedWorld.Status.FAILED, orchestrator.state().orElseThrow().status());
        assertThrows(IllegalStateException.class, () -> orchestrator.execute(writer((input, context) -> new Success<>(plan)),
                AgentFactoryTest.input(), TaskContext.start(), Contract.RESPONSE_PLAN, validation));
        assertNotNull(orchestrator.authorizeRetry(task.taskId()));
    }

    @Test
    void validationRejectsEmptyAnswerButAllowsAcknowledgement() throws Exception {
        validation.validate(plan);
        var orchestrator = new OrchestratorAgent(store().sharedWorld(), messages);
        var result = orchestrator.execute(writer((input, context) -> new Success<>(
                        TestData.plan(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST, List.of()))),
                AgentFactoryTest.input(), TaskContext.start(), Contract.RESPONSE_PLAN, validation);
        assertEquals(ErrorCode.INVALID_OUTPUT, ((ErrorPlan<?>) result).code());
        assertEquals(SharedWorld.Status.FAILED, orchestrator.state().orElseThrow().status());
    }

    @Test
    void rejectsUnrelatedTaskReferencesAndCompletedAttemptReplay() throws Exception {
        var orchestrator = new OrchestratorAgent(store().sharedWorld(), messages);
        var task = TaskContext.start();
        var result = orchestrator.execute(writer((input, context) ->
                        new DiscussionPlan<>("Question ?", "Reason", TaskContext.start())),
                AgentFactoryTest.input(), task, Contract.RESPONSE_PLAN, validation);
        assertEquals(ErrorCode.INVALID_OUTPUT, ((ErrorPlan<?>) result).code());
        var resumed = orchestrator.authorizeRetry(task.taskId());
        var successful = writer((input, context) -> new Success<>(plan));
        orchestrator.execute(successful, AgentFactoryTest.input(), resumed.task(), Contract.RESPONSE_PLAN, validation);
        assertThrows(IllegalStateException.class, () -> orchestrator.execute(successful,
                AgentFactoryTest.input(), resumed.task(), Contract.RESPONSE_PLAN, validation));
    }

    @Test
    void completedTaskRejectsNewAttemptAndAllowsNextTaskInSameCorrelation() throws Exception {
        var task = TaskContext.start();
        var calls = new AtomicInteger();
        var writer = writer((input, context) -> {
            calls.incrementAndGet();
            return new Success<>(plan);
        });
        var repository = store().sharedWorld();
        var orchestrator = new OrchestratorAgent(repository, messages);
        assertInstanceOf(Success.class, orchestrator.execute(writer, AgentFactoryTest.input(),
                task, Contract.RESPONSE_PLAN, validation));

        var restarted = new OrchestratorAgent(repository, messages);
        assertThrows(IllegalStateException.class, () -> restarted.execute(writer, AgentFactoryTest.input(),
                task.nextAttempt(), Contract.RESPONSE_PLAN, validation));
        assertEquals(1, calls.get());
        assertEquals(task, restarted.state().orElseThrow().task());

        var nextTask = new TaskContext(task.correlationId(), UUID.randomUUID(), UUID.randomUUID());
        assertInstanceOf(Success.class, restarted.execute(writer, AgentFactoryTest.input(),
                nextTask, Contract.RESPONSE_PLAN, validation));
        assertEquals(2, calls.get());
    }

    @Test
    void styleCorrectionProducesTypedPlanWithoutSuspension() throws Exception {
        var orchestrator = new OrchestratorAgent(store().sharedWorld(), messages);
        var correction = new CorrectionPlan(CorrectionPlan.Target.SCRIBE_AGENT, CorrectionPlan.Type.ADJUST_STYLE,
                false, "Raccourcir la salutation", List.of(), "Préférence proposée");
        var agent = factory().createCorrecting(AgentFactoryTest.config("Correcting", AgentRole.CORRECTING),
                (input, context) -> new Success<>(correction));
        AgentResult<AgentMessage<CorrectionPlan>> result = orchestrator.execute(agent,
                new CorrectingInput("USER", "Retour utilisateur", null, null, null, null),
                TaskContext.start(), Contract.CORRECTION_PLAN, value -> {});

        var success = assertInstanceOf(Success.class, result);
        var message = assertInstanceOf(AgentMessage.class, success.value());
        assertEquals(correction, message.payload());
        assertEquals(SharedWorld.Status.COMPLETED, orchestrator.state().orElseThrow().status());
        assertTrue(orchestrator.suspendedDiscussion().isEmpty());
        assertTrue(store().miniWorld("Correcting", false).load().isEmpty());
    }

    @Test
    void failedInitialPersistencePreventsAgentExecutionAndAllowsExplicitRecovery() throws Exception {
        var failing = new FailingRepository();
        var orchestrator = new OrchestratorAgent(failing, messages);
        var calls = new AtomicInteger();
        var writer = writer((input, context) -> { calls.incrementAndGet(); return new Success<>(plan); });
        var task = TaskContext.start();
        failing.failOnSave = 1;
        var result = orchestrator.execute(writer, AgentFactoryTest.input(), task, Contract.RESPONSE_PLAN, validation);
        assertEquals(ErrorCode.FILE_ACCESS_ERROR, ((ErrorPlan<?>) result).code());
        assertEquals(0, calls.get());
        assertThrows(IllegalStateException.class, () -> orchestrator.execute(
                writer, AgentFactoryTest.input(), task.nextAttempt(), Contract.RESPONSE_PLAN, validation));
        failing.failOnSave = -1;
        var resumed = orchestrator.authorizeRetry(task.taskId());
        assertInstanceOf(Success.class, orchestrator.execute(
                writer, AgentFactoryTest.input(), resumed.task(), Contract.RESPONSE_PLAN, validation));
    }

    @Test
    void failedFinalPersistenceDoesNotReturnASuccessfulResult() throws Exception {
        var failing = new FailingRepository();
        failing.failOnSave = 2;
        var orchestrator = new OrchestratorAgent(failing, messages);
        var result = orchestrator.execute(writer((input, context) -> new Success<>(plan)),
                AgentFactoryTest.input(), TaskContext.start(), Contract.RESPONSE_PLAN, validation);
        assertEquals(ErrorCode.FILE_ACCESS_ERROR, ((ErrorPlan<?>) result).code());
        assertEquals(SharedWorld.Status.FAILED, orchestrator.state().orElseThrow().status());
        assertEquals(SharedWorld.Status.RUNNING, failing.value.status());
    }

    private static final class FailingRepository implements WorldRepository<SharedWorld> {
        SharedWorld value;
        int saves;
        int failOnSave = -1;

        @Override public Optional<SharedWorld> load() { return Optional.ofNullable(value); }
        @Override public void save(SharedWorld world) throws IOException {
            if (++saves == failOnSave) throw new IOException("private filesystem details");
            value = world;
        }
    }
}
