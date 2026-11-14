package fr.mas.email.mail;

import java.time.Instant;

public record MailSummary(long uid, long uidValidity, String from, String subject, Instant receivedAt) {
    public MailSummary {
        if (uid <= 0 || uidValidity <= 0) throw new IllegalArgumentException("Invalid IMAP message identity");
    }
    @Override public String toString() { return from + " — " + subject; }
}
