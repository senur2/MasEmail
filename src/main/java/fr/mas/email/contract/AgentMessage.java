package fr.mas.email.contract;

public record AgentMessage<T>(MessageMetadata metadata, T payload) {
    public AgentMessage {
        Checks.required(metadata, "metadata");
        Checks.required(payload, "payload");
    }
}
