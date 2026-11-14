package fr.mas.email.llm;

import fr.mas.email.contract.Checks;
import fr.mas.email.contract.Contract;
import java.time.Duration;

/** instructions are application-controlled; input is untrusted task data. */
public record LlmRequest<T>(String instructions, String input, Contract<T> expectedContract, Duration timeout,
                            double temperature) {
    public LlmRequest(String instructions, String input, Contract<T> expectedContract, Duration timeout) {
        this(instructions, input, expectedContract, timeout, 0.2);
    }

    public LlmRequest {
        instructions = Checks.text(instructions, "instructions");
        input = Checks.text(input, "input");
        Checks.required(expectedContract, "expectedContract");
        Checks.required(timeout, "timeout");
        if (!Double.isFinite(temperature) || temperature < 0) {
            throw new IllegalArgumentException("temperature must be finite and non-negative");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (!expectedContract.isLlmOutput()) {
            throw new IllegalArgumentException("Only approved semantic payload contracts may be generated");
        }
    }
}
