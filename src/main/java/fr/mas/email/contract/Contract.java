package fr.mas.email.contract;

import fr.mas.email.result.*;

/** Version belongs to this contract, independently of every other contract. */
public record Contract<T>(String messageType, String schemaVersion, Class<T> payloadType) {
    public static final Contract<EmailAnalysis> EMAIL_ANALYSIS =
            new Contract<>("EMAIL_ANALYSIS", "3.0", EmailAnalysis.class);
    public static final Contract<RouteDecision> ROUTE_DECISION =
            new Contract<>("ROUTE_DECISION", "1.0", RouteDecision.class);
    public static final Contract<ResponsePlan> RESPONSE_PLAN =
            new Contract<>("RESPONSE_PLAN", "1.0", ResponsePlan.class);
    public static final Contract<DraftResponse> DRAFT_RESPONSE =
            new Contract<>("DRAFT_RESPONSE", "1.0", DraftResponse.class);
    public static final Contract<CorrectionPlan> CORRECTION_PLAN =
            new Contract<>("CORRECTION_PLAN", "2.0", CorrectionPlan.class);
    public static final Contract<DiscussionPlan<?>> DISCUSSION_PLAN =
            new Contract<>("DISCUSSION_REQUEST", "1.0", erased(DiscussionPlan.class));
    public static final Contract<ErrorPlan<?>> ERROR_PLAN =
            new Contract<>("ERROR_PLAN", "1.0", erased(ErrorPlan.class));
    public static final Contract<EmailAnalysis.Content> EMAIL_ANALYSIS_CONTENT =
            new Contract<>("EMAIL_ANALYSIS", "3.0", EmailAnalysis.Content.class);
    public static final Contract<RouteDecision.Content> ROUTE_DECISION_CONTENT =
            new Contract<>("ROUTE_DECISION", "1.0", RouteDecision.Content.class);
    public static final Contract<ResponsePlan.Content> RESPONSE_PLAN_CONTENT =
            new Contract<>("RESPONSE_PLAN", "1.0", ResponsePlan.Content.class);
    public static final Contract<DraftContent> DRAFT_CONTENT =
            new Contract<>("DRAFT_RESPONSE", "1.0", DraftContent.class);

    // The result type parameter is phantom: it is never part of a discussion/error payload.
    @SuppressWarnings("unchecked")
    private static <T> Class<T> erased(Class<?> type) { return (Class<T>) type; }

    public boolean isLlmOutput() {
        return equals(EMAIL_ANALYSIS_CONTENT) || equals(ROUTE_DECISION_CONTENT)
                || equals(RESPONSE_PLAN_CONTENT) || equals(DRAFT_CONTENT) || equals(CORRECTION_PLAN);
    }

    public ErrorPlan.Stage stage() {
        return switch (messageType) {
            case "EMAIL_ANALYSIS" -> ErrorPlan.Stage.PARSER;
            case "ROUTE_DECISION" -> ErrorPlan.Stage.DECISION;
            case "RESPONSE_PLAN" -> ErrorPlan.Stage.WRITER;
            case "DRAFT_RESPONSE" -> ErrorPlan.Stage.SCRIBE;
            case "CORRECTION_PLAN" -> ErrorPlan.Stage.CORRECTING;
            default -> ErrorPlan.Stage.ORCHESTRATION;
        };
    }

    public Contract {
        messageType = Checks.text(messageType, "messageType");
        schemaVersion = Checks.text(schemaVersion, "schemaVersion");
        Checks.required(payloadType, "payloadType");
    }

    public void validate(AgentMessage<T> message) {
        Checks.required(message, "message");
        if (!messageType.equals(message.metadata().messageType())
                || !schemaVersion.equals(message.metadata().schemaVersion())
                || !payloadType.isInstance(message.payload())) {
            throw new IllegalArgumentException("Unexpected contract type or version");
        }
    }
}
