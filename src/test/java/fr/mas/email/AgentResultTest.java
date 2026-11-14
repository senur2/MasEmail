package fr.mas.email;

import fr.mas.email.result.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AgentResultTest {
    @Test
    void onlySuccessInvokesThePayloadTransformation() {
        var task = TaskContext.start();
        var calls = new AtomicInteger();
        AgentResult<String> success = new Success<>("plan");
        AgentResult<String> discussion = new DiscussionPlan<>("Quelle date ?", "Date manquante", task);
        AgentResult<String> error = Errors.create(ErrorCode.LLM_TIMEOUT, "Writer", task);
        assertEquals(new Success<>(4), success.map(value -> { calls.incrementAndGet(); return value.length(); }));
        var mappedDiscussion = assertInstanceOf(DiscussionPlan.class,
                discussion.map(value -> { calls.incrementAndGet(); return value.length(); }));
        var mappedError = assertInstanceOf(ErrorPlan.class,
                error.map(value -> { calls.incrementAndGet(); return value.length(); }));
        assertEquals(1, calls.get());
        assertEquals(task, mappedDiscussion.task());
        assertEquals(task, mappedError.task());
        assertEquals(((ErrorPlan<String>) error).errorId(), mappedError.errorId());
    }

    @Test
    void invalidResultsCannotBeConstructed() {
        assertThrows(NullPointerException.class, () -> new Success<>(null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiscussionPlan<>("", "reason", TaskContext.start()));
        assertThrows(NullPointerException.class,
                () -> new DiscussionPlan<>("Question?", "reason", null));
    }

    @Test
    void errorsHaveTraceabilityWithoutRawExceptionSecrets() {
        var task = TaskContext.start();
        var result = Errors.from(new IllegalStateException("apiKey=secret"), "Writer", task);
        assertEquals(ErrorCode.INTERNAL_ERROR, result.code());
        assertEquals(task, result.task());
        assertNotNull(result.errorId());
        assertNotNull(result.occurredAt());
        assertFalse(result.toString().contains("secret"));
    }

    @Test
    void explicitRetryPreservesTaskButChangesAttempt() {
        var task = TaskContext.start();
        var retry = task.nextAttempt();
        assertEquals(task.taskId(), retry.taskId());
        assertEquals(task.correlationId(), retry.correlationId());
        assertNotEquals(task.attemptId(), retry.attemptId());
    }
}
