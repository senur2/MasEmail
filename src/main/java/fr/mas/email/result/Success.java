package fr.mas.email.result;

import fr.mas.email.contract.Checks;

public record Success<T>(T value, @com.fasterxml.jackson.annotation.JsonIgnore String rawOutput) implements AgentResult<T> {
    public Success(T value) { this(value, null); }

    @Override public String toString() { return "Success[value=" + value + "]"; }
    public Success {
        Checks.required(value, "value");
    }
}
