package fr.mas.email.agent;

import fr.mas.email.contract.*;

public record CorrectingInput(String feedbackSource, String feedbackText, EmailAnalysis emailAnalysis,
                              RouteDecision routeDecision, ResponsePlan responsePlan, DraftResponse draftResponse) {
    public CorrectingInput {
        feedbackSource = Checks.text(feedbackSource, "feedbackSource");
        feedbackText = Checks.text(feedbackText, "feedbackText");
    }
}
