package fr.mas.email.contract;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Called by application code after successful payload validation, never by the LLM. */
public final class MessageFactory {
    private final Clock clock;

    public MessageFactory(Clock clock) {
        this.clock = Checks.required(clock, "clock");
    }

    public <T> AgentMessage<T> create(Contract<T> contract, T payload,
                                      UUID correlationId, String producerAgentId) {
        var metadata = new MessageMetadata(UUID.randomUUID(), correlationId,
                contract.messageType(), contract.schemaVersion(), producerAgentId, Instant.now(clock));
        var message = new AgentMessage<>(metadata, payload);
        contract.validate(message);
        switch (payload) {
            case ResponsePlan plan -> Checks.rule(plan.agentId().equals(producerAgentId));
            case fr.mas.email.result.DiscussionPlan<?> discussion ->
                    Checks.rule(discussion.task().correlationId().equals(correlationId));
            case fr.mas.email.result.ErrorPlan<?> error ->
                    Checks.rule(error.task().correlationId().equals(correlationId));
            default -> { }
        }
        return message;
    }
}
