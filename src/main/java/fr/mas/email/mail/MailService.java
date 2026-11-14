package fr.mas.email.mail;

import java.io.IOException;
import java.util.List;

/** Explicit operations only: no background polling, retries, or automatic sending. */
public interface MailService {
    List<MailSummary> listInbox(MailAccount account, char[] credential) throws IOException;
    ReceivedMail read(MailAccount account, char[] credential, MailSummary message) throws IOException;
    String send(MailAccount account, char[] credential, OutgoingMail mail) throws IOException;
}
