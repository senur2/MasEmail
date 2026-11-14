package fr.mas.email;

import fr.mas.email.contract.*;
import fr.mas.email.llm.*;
import fr.mas.email.result.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class LlmGatewayTest {
    @Test
    void emptyLlmContentProducesEmptyResultWithoutRetry() {
        var gateway = new FakeLlmGateway(new JsonCodec()).enqueueJson("  ");
        var error = assertInstanceOf(ErrorPlan.class, gateway.generate(request, task, "Scribe"));
        assertEquals(ErrorCode.EMPTY_RESULT, error.code()); assertEquals(1, gateway.callCount());
    }
    private final JsonCodec json = new JsonCodec();
    private final TaskContext task = TaskContext.start();
    private final LlmRequest<DraftContent> request =
            new LlmRequest<>("Return subject and body", "Plan data", Contract.DRAFT_CONTENT, Duration.ofSeconds(2));

    @Test
    void returnsExpectedStructuredType() {
        var gateway = new FakeLlmGateway(json).enqueueJson("{\"subject\":\"Re\",\"body\":\"Bonjour\"}");
        assertEquals(new DraftContent("Re", "Bonjour"),
                assertInstanceOf(Success.class, gateway.generate(request, task, "Scribe")).value());
    }

    @Test
    void simulatedTimeoutBecomesTraceableErrorWithoutRetry() {
        var gateway = new FakeLlmGateway(json).enqueueTimeout()
                .enqueueJson("{\"subject\":\"Next attempt\",\"body\":\"Bonjour\"}");
        var error = assertInstanceOf(ErrorPlan.class, gateway.generate(request, task, "Scribe"));
        assertEquals(ErrorCode.LLM_TIMEOUT, error.code());
        assertEquals("Scribe", error.agentId());
        assertEquals(task, error.task());
        assertEquals(1, gateway.callCount());
        assertInstanceOf(Success.class, gateway.generate(request, task.nextAttempt(), "Scribe"));
        assertEquals(2, gateway.callCount());
    }

    @Test
    void invalidJsonAndModelSuppliedMetadataCannotBecomeSuccess() {
        for (String response : new String[] {
                "not json", "{\"subject\":\"Re\"}",
                "{\"subject\":\"Re\",\"body\":\"Body\",\"metadata\":{}}",
                "{\"code\":\"LLM_TIMEOUT\",\"technicalMessage\":\"fake incident\"}"
        }) {
            var gateway = new FakeLlmGateway(json).enqueueJson(response);
            var error = assertInstanceOf(ErrorPlan.class, gateway.generate(request, task, "Scribe"));
            assertEquals(ErrorCode.INVALID_JSON, error.code());
        }
    }

    @Test
    void missingEngineAndUnexpectedFailureProduceApplicationErrors() {
        var gateway = new FakeLlmGateway(json);
        assertEquals(ErrorCode.LLM_UNAVAILABLE,
                ((ErrorPlan<?>) gateway.generate(request, task, "Scribe")).code());
        gateway.enqueueFailure(new RuntimeException("password=secret"));
        var error = assertInstanceOf(ErrorPlan.class, gateway.generate(request, task, "Scribe"));
        assertEquals(ErrorCode.INTERNAL_ERROR, error.code());
        assertFalse(error.toString().contains("secret"));
        gateway.enqueueFailure(new java.io.IOException("private endpoint"));
        assertEquals(ErrorCode.LLM_UNAVAILABLE,
                ((ErrorPlan<?>) gateway.generate(request, task, "Scribe")).code());
    }

    @Test
    void rejectsNonPositiveDeadlineAndNonBusinessOutput() {
        assertThrows(IllegalArgumentException.class, () ->
                new LlmRequest<>("Prompt", "Input", Contract.DRAFT_CONTENT, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () ->
                new LlmRequest<>("Prompt", "Input", Contract.DRAFT_CONTENT, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new LlmRequest<>("Prompt", "Input",
                new Contract<>("ERROR", "1.0", ErrorPlan.class), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new LlmRequest<>("Prompt", "Input",
                new Contract<>("METADATA", "1.0", MessageMetadata.class), Duration.ofSeconds(1)));
    }
}
