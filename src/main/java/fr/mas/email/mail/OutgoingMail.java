package fr.mas.email.mail;

import fr.mas.email.contract.Checks;

public record OutgoingMail(String recipient, String subject, String body, String inReplyTo) {
    public OutgoingMail {
        recipient = MailAccount.address(recipient);
        subject = MailAccount.header(Checks.text(subject, "subject"));
        body = Checks.text(body, "body");
        inReplyTo = MailAccount.header(inReplyTo == null ? "" : inReplyTo);
    }
}
