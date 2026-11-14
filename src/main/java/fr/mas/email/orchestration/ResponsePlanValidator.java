package fr.mas.email.orchestration;

import fr.mas.email.contract.*;
import java.util.function.Predicate;

/** Verifies each fact against task-scoped, authorized evidence supplied by Java. */
public final class ResponsePlanValidator implements PlanValidationStrategy<ResponsePlan> {
    private final Predicate<ResponsePlan.Fact> supportedByAuthorizedSource;

    /** Fail closed: without an evidence resolver, no sourced fact is trusted. */
    public ResponsePlanValidator() { this(fact -> false); }

    /** The predicate must check source existence, permission and support for the stated fact. */
    public ResponsePlanValidator(Predicate<ResponsePlan.Fact> supportedByAuthorizedSource) {
        this.supportedByAuthorizedSource = Checks.required(supportedByAuthorizedSource, "supportedByAuthorizedSource");
    }

    @Override public void validate(ResponsePlan plan) {
        Checks.required(plan, "plan");
        Checks.rule(plan.factsToCommunicate().stream().allMatch(supportedByAuthorizedSource));
    }
}