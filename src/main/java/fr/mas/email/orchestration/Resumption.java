package fr.mas.email.orchestration;

import fr.mas.email.contract.Checks;
import fr.mas.email.contract.DiscussionResume;
import fr.mas.email.result.TaskContext;
import java.util.Map;

/** Returned to the future application controller; never automatically executes a retry. */
public record Resumption(TaskContext task, String agentId, String userAnswer, DiscussionResume.StorageResult storageResult,
                         Map<String, String> knowledge) {
    public Resumption(TaskContext task, String agentId, String userAnswer) {
        this(task, agentId, userAnswer, DiscussionResume.StorageResult.NOT_STORED, Map.of());
    }

    public DiscussionResume discussionResume() {
        return new DiscussionResume(DiscussionResume.Action.RESUME_WITH_INFORMATION, task, userAnswer, storageResult);
    }
    public Resumption {
        Checks.required(task, "task");
        agentId = Checks.text(agentId, "agentId");
        Checks.required(userAnswer, "userAnswer");
        Checks.required(storageResult, "storageResult");
        knowledge = Map.copyOf(knowledge);
    }
}
