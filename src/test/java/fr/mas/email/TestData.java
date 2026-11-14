package fr.mas.email;

import fr.mas.email.contract.*;
import java.util.List;

/** Explicit application enrichment for regression fixtures, never part of production DTO defaults. */
public final class TestData {
    private TestData() {}
    public static EmailAnalysis.Content analysisContent(String language, String intent, List<String> topics) {
        return new EmailAnalysis.Content(language, intent, topics, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }
    public static EmailAnalysis analysis(String language, String intent, List<String> topics) {
        return analysisContent(language, intent, topics).enrich("email-001", "thread-001");
    }
    public static RouteDecision.Content routeContent(String agent, List<String> reasons) {
        return new RouteDecision.Content(agent, List.of(agent), RouteDecision.DecisionStatus.SELECTED,
                reasons, null, false, null);
    }
    public static RouteDecision route(String agent, List<String> reasons) {
        return routeContent(agent, reasons).enrich("email-001");
    }
    public static ResponsePlan.Content planContent(ResponsePlan.Intent intent, List<ResponsePlan.Fact> facts) {
        return new ResponsePlan.Content(intent, facts, List.of(), false);
    }
    public static ResponsePlan plan(ResponsePlan.Intent intent, List<ResponsePlan.Fact> facts) {
        return planContent(intent, facts).enrich("Writer", "email-001", List.of(), "fr", "professional");
    }
    public static DraftResponse draft(String subject, String body) {
        return new DraftResponse("email-001", "plan-001", subject, body, DraftResponse.Status.DRAFT);
    }
}
