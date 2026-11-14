package fr.mas.email.world;

import fr.mas.email.contract.Checks;

/** Extension point only: no automatic promotion of observations into shared facts. */
public record Belief(String claim, double confidence) {
    public Belief {
        claim = Checks.text(claim, "claim");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }
}
