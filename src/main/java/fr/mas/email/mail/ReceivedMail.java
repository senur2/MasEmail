package fr.mas.email.mail;

import java.util.Objects;

/** Detached from the IMAP connection; reply routing never comes from the LLM. */
public record ReceivedMail(String accountEmail, MailSummary summary, String replyTo, String messageId, String body,
                           String threadId) {
    private static final java.util.regex.Pattern QUOTE_SEPARATOR = java.util.regex.Pattern.compile(
            "(?iu)(?:On\\s+.+\\s+wrote\\s*:|Le\\s+.+\\s+a écrit\\s*:|-{2,}\\s*Original Message\\s*-{2,})");

    public ReceivedMail(String accountEmail, MailSummary summary, String replyTo, String messageId, String body) {
        this(accountEmail, summary, replyTo, messageId, body, null);
    }

    public ReceivedMail {
        accountEmail = MailAccount.address(accountEmail);
        Objects.requireNonNull(summary);
        replyTo = MailAccount.address(replyTo);
        messageId = MailAccount.header(messageId == null ? "" : messageId);
        Objects.requireNonNull(body);
    }

    public String parserText() {
        return textWithBody(body);
    }

    public String cleanedParserText() { return textWithBody(stripQuotes(body)); }

    public String emailId() { return "imap-" + summary.uidValidity() + "-" + summary.uid(); }

    public String senderEmail() {
        try {
            var addresses = jakarta.mail.internet.InternetAddress.parse(summary.from());
            if (addresses.length == 1) return MailAccount.address(addresses[0].getAddress()).toLowerCase(java.util.Locale.ROOT);
        } catch (jakarta.mail.internet.AddressException | IllegalArgumentException ignored) {
            // Use the validated reply address when IMAP did not supply a usable From address.
        }
        return replyTo.toLowerCase(java.util.Locale.ROOT);
    }

    public static String stripQuotes(String text) {
        var result = new StringBuilder();
        for (String line : text.lines().toList()) {
            String trimmed = line.stripLeading();
            if (QUOTE_SEPARATOR.matcher(trimmed.stripTrailing()).matches()) break;
            if (!trimmed.startsWith(">")) result.append(line).append('\n');
        }
        return result.toString().stripTrailing();
    }

    private String textWithBody(String body) {
        return "Compte : " + accountEmail + "\nDe : " + summary.from() + "\nRépondre à : " + replyTo
                + "\nObjet : " + summary.subject() + "\nMessage-ID : " + messageId
                + "\nUID : " + summary.uidValidity() + "/" + summary.uid() + "\n\n" + body;
    }
}
