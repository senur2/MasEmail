package fr.mas.email.contract;

/** Scribe LLM output: no identifiers, approval or delivery status. */
public record DraftContent(String subject, String body) {
    public DraftContent {
        subject = Checks.text(subject, "subject"); body = Checks.text(body, "body");
    }

    public DraftResponse enrich(ResponsePlan validatedPlan) {
        Checks.required(validatedPlan, "validatedPlan");
        Checks.rule(!validatedPlan.requiresClarification());
        return new DraftResponse(validatedPlan.emailId(), validatedPlan.planId(), subject, body, DraftResponse.Status.DRAFT);
    }
}
