package fr.mas.email.result;

import fr.mas.email.contract.Checks;

/** An agent reports a blocking inconsistency. The mediator supplies the authenticated source. */
public record CorrectionRequest<T>(String feedbackText) implements AgentResult<T> {
    public CorrectionRequest {
        feedbackText = Checks.text(feedbackText, "feedbackText");
    }

    public <R> CorrectionRequest<R> retype() {
        return new CorrectionRequest<>(feedbackText);
    }
}
