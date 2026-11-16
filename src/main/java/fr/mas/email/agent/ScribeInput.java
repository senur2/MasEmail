package fr.mas.email.agent;

import fr.mas.email.contract.Checks;
import fr.mas.email.contract.CorrectionPlan;
import fr.mas.email.contract.DraftResponse;
import fr.mas.email.contract.ResponsePlan;

public record ScribeInput(ResponsePlan responsePlan, CorrectionPlan correctionPlan, DraftResponse previousDraft,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                          fr.mas.email.world.ThreadHistoryDigest threadHistory) {
    public ScribeInput(ResponsePlan responsePlan, CorrectionPlan correctionPlan, DraftResponse previousDraft) {
        this(responsePlan, correctionPlan, previousDraft, null);
    }
    public ScribeInput(ResponsePlan responsePlan) {
        this(responsePlan, null, null);
    }

    public ScribeInput(ResponsePlan responsePlan, CorrectionPlan correctionPlan) {
        this(responsePlan, correctionPlan, null);
    }

    public ScribeInput {
        Checks.required(responsePlan, "responsePlan");
        if (previousDraft != null) {
            Checks.rule(previousDraft.planId().equals(responsePlan.planId())
                    && previousDraft.emailId().equals(responsePlan.emailId()), "Previous draft belongs to another plan");
        }
    }
}
