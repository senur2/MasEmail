package fr.mas.email.result;

import fr.mas.email.contract.Checks;
import java.time.Instant;
import java.util.UUID;

/** Application-generated incident, never an allowed LLM payload. */
public record ErrorPlan<T>(UUID errorId, TaskContext task, String agentId, String component, Stage stage,
                           ReportedBy reportedBy, ErrorCode code, String expectedResult, String observedProblem,
                           String traceRef, Status status, boolean requiresUserDecision, Instant occurredAt)
        implements AgentResult<T> {
    public enum Stage { PARSER, DECISION, WRITER, SCRIBE, CORRECTING, ORCHESTRATION, KNOWLEDGE }
    public enum ReportedBy { AGENT, EXECUTION_CONTROL }
    public enum Status { STOPPED }

    public ErrorPlan {
        Checks.required(errorId, "errorId"); Checks.required(task, "task");
        agentId = Checks.nullableText(agentId, "agentId"); component = Checks.text(component, "component");
        Checks.required(stage, "stage"); Checks.required(reportedBy, "reportedBy"); Checks.required(code, "code");
        expectedResult = Checks.text(expectedResult, "expectedResult");
        observedProblem = Checks.text(observedProblem, "observedProblem");
        traceRef = Checks.nullableText(traceRef, "traceRef"); Checks.required(status, "status");
        Checks.rule(requiresUserDecision); Checks.required(occurredAt, "occurredAt");
    }

    public <R> ErrorPlan<R> retype() {
        return new ErrorPlan<>(errorId, task, agentId, component, stage, reportedBy, code, expectedResult,
                observedProblem, traceRef, status, requiresUserDecision, occurredAt);
    }
}