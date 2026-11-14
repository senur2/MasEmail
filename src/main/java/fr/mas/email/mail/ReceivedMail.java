package fr.mas.email.mail;

import java.util.Objects;

/** Detached from the IMAP connection; reply routing never comes from the LLM. */
public record ReceivedMail(String accountEmail, MailSummary summary, String replyTo, String messageId, String body) {
    public ReceivedMail {
        accountEmail = MailAccount.address(accountEmail);
        Objects.requireNonNull(summary);
        replyTo = MailAccount.address(replyTo);
        messageId = MailAccount.header(messageId == null ? "" : messageId);
        Objects.requireNonNull(body);
    }

    public String parserText() {
        return "Compte : " + accountEmail + "\nDe : " + summary.from() + "\nRépondre à : " + replyTo
                + "\nObjet : " + summary.subject() + "\nMessage-ID : " + messageId
                + "\nUID : " + summary.uidValidity() + "/" + summary.uid() + "\n\n" + body;
    }
}
