package fr.mas.email.result;

import fr.mas.email.contract.Checks;
import java.util.UUID;

/** All identifiers are allocated by Java; retries retain task and correlation identity. */
public record TaskContext(UUID correlationId, UUID taskId, UUID attemptId) {
    public TaskContext {
        Checks.required(correlationId, "correlationId");
        Checks.required(taskId, "taskId");
        Checks.required(attemptId, "attemptId");
    }

    public static TaskContext start() {
        return new TaskContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    public TaskContext nextAttempt() {
        return new TaskContext(correlationId, taskId, UUID.randomUUID());
    }
}
