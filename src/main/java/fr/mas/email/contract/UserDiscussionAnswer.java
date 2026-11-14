package fr.mas.email.contract;

import java.util.UUID;

/** Explicit user decision; a memory choice is not evidence of a successful write. */
public record UserDiscussionAnswer(UUID requestMessageId, Action action, String answer,
                                   MemoryChoice memoryChoice, String memoryAgentId, boolean storageConfirmed) {
    public enum Action { ANSWER_INFORMATION }
    public enum MemoryChoice { NONE, K_PERSO, K_COMMUN }
    public enum MemoryScope { SESSION_ONLY, AGENT_SPECIFIC, COMMON }

    public UserDiscussionAnswer(UUID requestMessageId, Action action, String answer,
                                MemoryChoice memoryChoice, String memoryAgentId) {
        this(requestMessageId, action, answer, memoryChoice, memoryAgentId, false);
    }

    public UserDiscussionAnswer {
        Checks.required(requestMessageId, "requestMessageId"); Checks.required(action, "action");
        answer = Checks.text(answer, "answer"); Checks.required(memoryChoice, "memoryChoice");
        memoryAgentId = Checks.nullableText(memoryAgentId, "memoryAgentId");
        Checks.rule((memoryChoice == MemoryChoice.K_PERSO) == (memoryAgentId != null));
        Checks.rule(!storageConfirmed || memoryChoice != MemoryChoice.NONE);
    }

    public MemoryScope scope() {
        return switch (memoryChoice) {
            case NONE -> MemoryScope.SESSION_ONLY;
            case K_PERSO -> MemoryScope.AGENT_SPECIFIC;
            case K_COMMUN -> MemoryScope.COMMON;
        };
    }

    /** Application default; JSON still requires every field, including the explicit null. */
    public static UserDiscussionAnswer withoutStorage(UUID requestMessageId, String answer) {
        return new UserDiscussionAnswer(requestMessageId, Action.ANSWER_INFORMATION, answer, MemoryChoice.NONE, null);
    }
}
