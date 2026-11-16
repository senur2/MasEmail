package fr.mas.email.agent;

import fr.mas.email.contract.Checks;
import fr.mas.email.contract.CorrectionPlan;

/** Java-owned email identifiers and the exact message text to analyze. */
public record ParserInput(String emailId, String threadId, String text, CorrectionPlan correctionPlan,
                          fr.mas.email.world.ThreadHistoryDigest threadHistory) {
    public ParserInput(String emailId, String threadId, String text, CorrectionPlan correctionPlan) {
        this(emailId, threadId, text, correctionPlan, null);
    }
    public ParserInput(String emailId, String threadId, String text) {
        this(emailId, threadId, text, null);
    }

    public ParserInput {
        emailId = Checks.text(emailId, "emailId");
        threadId = Checks.text(threadId, "threadId");
        text = Checks.text(text, "text");
    }
}
