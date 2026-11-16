package fr.mas.email.world;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@JsonPropertyOrder({"turnIndex", "emailId", "receivedAt", "incomingIntent", "requestedInformation", "senderClaims",
        "selectedAgentId", "plannedFacts", "generatedDraftSubject", "generatedDraftSnippet", "deliveryStatus", "finalSentBodySnippet"})
public record ThreadTurn(int turnIndex, String emailId, String receivedAt, String incomingIntent,
                         List<String> requestedInformation, List<String> senderClaims, String selectedAgentId,
                         List<String> plannedFacts, String generatedDraftSubject, String generatedDraftSnippet,
                         DeliveryStatus deliveryStatus, String finalSentBodySnippet) {
    public enum DeliveryStatus { DRAFT_GENERATED, APPROVED_AND_SENT, REJECTED }

    public ThreadTurn {
        if (turnIndex < 1) throw new IllegalArgumentException("Invalid turn index");
        Objects.requireNonNull(emailId);
        Instant.parse(receivedAt);
        requestedInformation = compact(requestedInformation);
        senderClaims = compact(senderClaims);
        plannedFacts = compact(plannedFacts);
        generatedDraftSubject = excerpt(generatedDraftSubject, 250);
        generatedDraftSnippet = excerpt(generatedDraftSnippet, 250);
        finalSentBodySnippet = excerpt(finalSentBodySnippet, 300);
        Objects.requireNonNull(deliveryStatus);
        if ((deliveryStatus == DeliveryStatus.APPROVED_AND_SENT) != (finalSentBodySnippet != null)) {
            throw new IllegalArgumentException("Sent body requires confirmed delivery");
        }
    }

    static String excerpt(String text, int limit) {
        if (text == null) return null;
        String compact = text.replaceAll("(?U)\\s+", " ").strip();
        int end = Math.min(limit, compact.length());
        if (end > 0 && Character.isHighSurrogate(compact.charAt(end - 1))) end--;
        return compact.substring(0, end);
    }

    private static List<String> compact(List<String> values) {
        return values.stream().limit(12).map(value -> excerpt(Objects.requireNonNull(value), 250)).toList();
    }
}
