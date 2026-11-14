package fr.mas.email.orchestration;

import fr.mas.email.contract.*;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/** Current plan and a task-specific fidelity check are required; no automatic LLM critique. */
public final class DraftResponseValidator implements PlanValidationStrategy<DraftResponse> {
    private static final Pattern BRACKETED_PLACEHOLDER = Pattern.compile("\\[[^\\[\\]]*\\]");
    private final ResponsePlan currentPlan;
    private final BiPredicate<ResponsePlan, DraftResponse> faithfulToPlan;

    public DraftResponseValidator(ResponsePlan currentPlan, BiPredicate<ResponsePlan, DraftResponse> faithfulToPlan) {
        this.currentPlan = Checks.required(currentPlan, "currentPlan");
        Checks.rule(!currentPlan.requiresClarification());
        this.faithfulToPlan = Checks.required(faithfulToPlan, "faithfulToPlan");
    }

    @Override public void validate(DraftResponse draft) {
        validateStructure(currentPlan, draft);
        Checks.rule(faithfulToPlan.test(currentPlan, draft));
    }

    public static void validateStructure(ResponsePlan plan, DraftResponse draft) {
        Checks.required(plan, "plan");
        Checks.required(draft, "draft");
        Checks.rule(!plan.requiresClarification());
        Checks.rule(draft.emailId().equals(plan.emailId()) && draft.planId().equals(plan.planId()));
        Checks.rule(!draft.subject().isBlank() && !draft.body().isBlank());
        Checks.rule(!BRACKETED_PLACEHOLDER.matcher(draft.body()).find(), "Draft body contains a bracketed placeholder");
    }
}
