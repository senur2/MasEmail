package fr.mas.email.world;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

@JsonPropertyOrder({"threadId", "normalizedSubject", "participantEmail", "lastSelectedAgentId", "turns"})
public record ConversationThread(String threadId, String normalizedSubject, String participantEmail,
                                 String lastSelectedAgentId, List<ThreadTurn> turns) {
    public ConversationThread { turns = List.copyOf(turns); }
}
