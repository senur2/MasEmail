package fr.mas.email.orchestration;

import fr.mas.email.contract.Checks;
import fr.mas.email.contract.DraftResponse;
import java.time.Instant;
import java.util.UUID;

/** Local approval of an exact draft version by the user. */
public record DraftApproval(UUID executionId, int correctionNumber, DraftResponse draft, Instant approvedAt) {
    public DraftApproval {
        Checks.required(executionId, "executionId");
        Checks.required(draft, "draft");
        Checks.required(approvedAt, "approvedAt");
        Checks.rule(correctionNumber >= 0 && correctionNumber <= OrchestratorAgent.MAX_CORRECTIONS);
    }

    public boolean matches(ExecutionTrace trace) {
        return executionId.equals(trace.executionId()) && correctionNumber == trace.corrections()
                && draft.equals(trace.draftResponse());
    }
}
