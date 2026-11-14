package fr.mas.email.contract;

import fr.mas.email.result.TaskContext;

/** Storage outcomes must originate from an actual Java storage operation. */
public record DiscussionResume(Action action, TaskContext task, String answer, StorageResult storageResult) {
    public enum Action { RESUME_WITH_INFORMATION }
    public enum StorageResult { NOT_STORED, STORED_K_PERSO, STORED_K_COMMUN }

    public DiscussionResume {
        Checks.required(action, "action"); Checks.required(task, "task");
        answer = Checks.text(answer, "answer"); Checks.required(storageResult, "storageResult");
    }

    public static DiscussionResume withoutStorage(TaskContext task, String answer) {
        return new DiscussionResume(Action.RESUME_WITH_INFORMATION, task, answer, StorageResult.NOT_STORED);
    }
}