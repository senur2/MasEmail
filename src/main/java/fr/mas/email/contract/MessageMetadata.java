package fr.mas.email.contract;

import java.time.Instant;
import java.util.UUID;

public record MessageMetadata(UUID messageId, UUID correlationId, String messageType,
                              String schemaVersion, String producerAgentId, Instant createdAt) {
    public MessageMetadata {
        Checks.required(messageId, "messageId");
        Checks.required(correlationId, "correlationId");
        messageType = Checks.text(messageType, "messageType");
        schemaVersion = Checks.text(schemaVersion, "schemaVersion");
        producerAgentId = Checks.text(producerAgentId, "producerAgentId");
        Checks.required(createdAt, "createdAt");
    }
}
