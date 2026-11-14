package fr.mas.email.mail;

import java.time.Instant;
import java.util.UUID;

public record MailDelivery(UUID executionId, String recipient, Status status, String messageId, Instant updatedAt) {
    public enum Status { SENDING, SENT, UNKNOWN }
}
