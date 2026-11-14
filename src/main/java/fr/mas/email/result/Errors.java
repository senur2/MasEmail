package fr.mas.email.result;

import fr.mas.email.contract.ContractException;
import fr.mas.email.contract.InvalidOutputException;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/** Controlled messages avoid leaking prompts, exception messages, paths or credentials. */
public final class Errors {
    private Errors() {}

    public static <T> ErrorPlan<T> validation(ErrorCode code, String component, TaskContext task,
                                               Exception cause, String rawOutput) {
        var error = Errors.<T>create(code, component, task);
        var detail = "Validation failed: " + java.util.Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
        if (rawOutput != null) detail += " | Raw LLM output: " + rawOutput;
        return new ErrorPlan<>(error.errorId(), task, null, component, error.stage(), error.reportedBy(),
                code, error.expectedResult(), detail, null, error.status(), true, error.occurredAt());
    }

    public static <T> ErrorPlan<T> create(ErrorCode code, String component, TaskContext task) {
        String message = switch (code) {
            case LLM_TIMEOUT -> "LLM request exceeded its deadline";
            case INVALID_JSON -> "LLM output did not match the expected JSON contract";
            case LLM_UNAVAILABLE -> "LLM engine is unavailable";
            case FILE_ACCESS_ERROR -> "World file could not be read or atomically written";
            case PERMISSION_DENIED -> "World operation denied by the scoped repository";
            case INTERNAL_ERROR -> "Component execution failed unexpectedly";
            case NOT_IMPLEMENTED -> "No processing strategy has been configured";
            case INVALID_OUTPUT -> "Component output failed application validation";
            case EMPTY_RESULT -> "Component returned an empty result";
            case NO_RESULT -> "Component returned no result";
            case AGENT_FAILURE -> "Agent execution failed";
            case TASK_NOT_COMPLETED -> "Task ended without the required result";
            case MAX_CORRECTIONS_REACHED -> "The maximum of three corrections for this email has been reached";
        };
        return new ErrorPlan<>(UUID.randomUUID(), task, null, component, ErrorPlan.Stage.ORCHESTRATION,
                ErrorPlan.ReportedBy.EXECUTION_CONTROL, code, "Completed component result", message,
                null, ErrorPlan.Status.STOPPED, true, Instant.now());
    }

    public static <T> ErrorPlan<T> forAgent(ErrorPlan<T> error, String agentId, ErrorPlan.Stage stage,
                                           String component, String expectedResult) {
        return new ErrorPlan<>(error.errorId(), error.task(), agentId, component, stage, ErrorPlan.ReportedBy.AGENT,
                error.code(), expectedResult, error.observedProblem(), error.traceRef(),
                error.status(), error.requiresUserDecision(), error.occurredAt());
    }

    public static <T> ErrorPlan<T> execution(ErrorPlan<T> error, String agentId, ErrorPlan.Stage stage,
                                             String expectedResult) {
        return new ErrorPlan<>(error.errorId(), error.task(), agentId, error.component(), stage,
                ErrorPlan.ReportedBy.EXECUTION_CONTROL, error.code(), expectedResult, error.observedProblem(),
                error.traceRef(), error.status(), error.requiresUserDecision(), error.occurredAt());
    }

    public static <T> ErrorPlan<T> from(Exception failure, String component, TaskContext task) {
        ErrorCode code = switch (failure) {
            case TimeoutException ignored -> ErrorCode.LLM_TIMEOUT;
            case ContractException ignored -> ErrorCode.INVALID_JSON;
            case InvalidOutputException ignored -> ErrorCode.INVALID_OUTPUT;
            case SecurityException ignored -> ErrorCode.PERMISSION_DENIED;
            case IOException ignored -> ErrorCode.FILE_ACCESS_ERROR;
            default -> ErrorCode.INTERNAL_ERROR;
        };
        return create(code, component, task);
    }
}
