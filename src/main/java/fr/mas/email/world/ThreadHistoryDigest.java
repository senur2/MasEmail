package fr.mas.email.world;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

@JsonPropertyOrder({"threadId", "previousSelectedAgentId", "recentTurns"})
public record ThreadHistoryDigest(String threadId, String previousSelectedAgentId, List<ThreadTurn> recentTurns) {
    public static final int MAX_HISTORY_TURNS = 3;

    public ThreadHistoryDigest {
        recentTurns = List.copyOf(recentTurns.subList(Math.max(0, recentTurns.size() - MAX_HISTORY_TURNS), recentTurns.size()));
    }
}
