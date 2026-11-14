package fr.mas.email.orchestration;

/** Deterministic application checks, replaceable independently of agent reasoning. */
@FunctionalInterface
public interface PlanValidationStrategy<T> {
    void validate(T value);
}
