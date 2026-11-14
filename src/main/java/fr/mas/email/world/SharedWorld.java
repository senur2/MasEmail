package fr.mas.email.world;

import fr.mas.email.contract.Checks;
import fr.mas.email.result.TaskContext;

/** Minimal coordination snapshot; deliberately contains no automatically learned facts. */
public record SharedWorld(TaskContext task, String activeAgentId, Status status, String detail) {
    public SharedWorld {
        Checks.required(task, "task");
        activeAgentId = Checks.text(activeAgentId, "activeAgentId");
        Checks.required(status, "status");
        Checks.required(detail, "detail");
    }

    public enum Status { RUNNING, COMPLETED, WAITING_USER, FAILED, READY }
}
