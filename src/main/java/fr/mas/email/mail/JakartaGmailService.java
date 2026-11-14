package fr.mas.email.mail;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Date;

/** Gmail IMAPS/SMTP via Jakarta Mail (Eclipse Angus). TLS identity checking is mandatory. */
public final class JakartaGmailService implements MailService {
    private final Session session;

    public JakartaGmailService() { this(Session.getInstance(properties())); }
    JakartaGmailService(Session session) { this.session = session; }

    static Properties properties() {
        var properties = new Properties();
        for (String protocol : List.of("imaps", "smtp")) {
            properties.setProperty("mail." + protocol + ".auth.mechanisms", "PLAIN LOGIN");
            properties.setProperty("mail." + protocol + ".connectiontimeout", "15000");
            properties.setProperty("mail." + protocol + ".timeout", "20000");
            properties.setProperty("mail." + protocol + ".writetimeout", "20000");
            properties.setProperty("mail." + protocol + ".ssl.checkserveridentity", "true");
        }
        properties.setProperty("mail.imaps.peek", "true");
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.starttls.enable", "true");
        properties.setProperty("mail.smtp.starttls.required", "true");
        properties.setProperty("mail.mime.address.strict", "true");
        return properties;
    }

    @Override public List<MailSummary> listInbox(MailAccount account, char[] credential) throws IOException {
        try (var store = connect(account, credential)) {
            var inbox = store.getFolder("INBOX");
            try {
                inbox.open(Folder.READ_ONLY);
                var uids = (UIDFolder) inbox;
                int count = inbox.getMessageCount();
                if (count == 0) return List.of();
                var messages = inbox.getMessages(Math.max(1, count - 49), count);
                var fetch = new FetchProfile();
                fetch.add(FetchProfile.Item.ENVELOPE);
                fetch.add(UIDFolder.FetchProfileItem.UID);
                inbox.fetch(messages, fetch);
                var result = new ArrayList<MailSummary>();
                for (int i = messages.length - 1; i >= 0; i--) {
                    var message = messages[i];
                    if (message.isExpunged()) continue;
                    result.add(summary(message, uids));
                }
                return List.copyOf(result);
            } finally { if (inbox.isOpen()) inbox.close(false); }
        } catch (MessagingException | ClassCastException e) { throw failure(e); }
    }

    @Override public ReceivedMail read(MailAccount account, char[] credential, MailSummary selected) throws IOException {
        try (var store = connect(account, credential)) {
            var inbox = store.getFolder("INBOX");
            try {
                inbox.open(Folder.READ_ONLY);
                var uids = (UIDFolder) inbox;
                if (uids.getUIDValidity() != selected.uidValidity()) throw new IOException("Boîte modifiée. Actualisez les emails.");
                var message = uids.getMessageByUID(selected.uid());
                if (message == null || message.isExpunged()) throw new IOException("Email supprimé ou déplacé. Actualisez la boîte.");
                String body = new MimeText().read(message);
                if (body.isBlank()) throw new IOException("Cet email ne contient pas de texte exploitable (pièces jointes exclues).");
                var reply = message.getReplyTo();
                if (reply == null || reply.length != 1 || !(reply[0] instanceof InternetAddress address) || address.isGroup()) {
                    throw new IOException("Cet email ne possède pas une adresse de réponse unique.");
                }
                var ids = message.getHeader("Message-ID");
                String id = ids == null || ids.length != 1 ? "" : ids[0];
                // Ignore malformed thread identifiers instead of copying arbitrary header data.
                if (!id.matches("<[^<>\\s]+>")) id = "";
                return new ReceivedMail(account.email(), summary(message, uids), address.getAddress(), id, body);
            } finally { if (inbox.isOpen()) inbox.close(false); }
        } catch (MessagingException | ClassCastException e) { throw failure(e); }
    }

    @Override public String send(MailAccount account, char[] credential, OutgoingMail mail) throws IOException {
        try (var transport = session.getTransport("smtp")) {
            var message = compose(account, mail);
            transport.connect("smtp.gmail.com", 587, account.email(), password(credential));
            transport.sendMessage(message, message.getAllRecipients());
            return message.getMessageID();
        } catch (MessagingException e) { throw failure(e); }
    }

    MimeMessage compose(MailAccount account, OutgoingMail mail) throws MessagingException, IOException {
        var message = new MimeMessage(session);
        message.setFrom(new InternetAddress(account.email(), account.displayName(), "UTF-8"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(mail.recipient(), true));
        message.setSubject(mail.subject(), "UTF-8");
        message.setText(mail.body(), "UTF-8");
        message.setSentDate(new Date());
        if (!mail.inReplyTo().isEmpty()) {
            message.setHeader("In-Reply-To", mail.inReplyTo());
            message.setHeader("References", mail.inReplyTo());
        }
        message.saveChanges();
        return message;
    }

    private Store connect(MailAccount account, char[] credential) throws MessagingException {
        var store = session.getStore("imaps");
        try {
            store.connect("imap.gmail.com", 993, account.email(), password(credential));
            return store;
        } catch (MessagingException | RuntimeException e) {
            try { store.close(); } catch (MessagingException ignored) { }
            throw e;
        }
    }

    private static MailSummary summary(Message message, UIDFolder folder) throws MessagingException {
        var from = message.getFrom();
        String sender = from == null || from.length == 0 ? "Expéditeur inconnu" : from[0].toString();
        var date = message.getReceivedDate();
        return new MailSummary(folder.getUID(message), folder.getUIDValidity(), sender,
                message.getSubject() == null ? "(sans objet)" : message.getSubject(), date == null ? Instant.EPOCH : date.toInstant());
    }

    private static String password(char[] credential) {
        if (credential == null || credential.length == 0) throw new IllegalArgumentException("Mot de passe d’application requis.");
        return new String(credential).replace(" ", "");
    }

    private static IOException failure(Exception cause) {
        // Never put server responses (which may echo authentication material) in user logs.
        return new IOException(cause instanceof AuthenticationFailedException
                ? "Gmail refuse la connexion. Vérifiez le mot de passe d’application Google."
                : "Connexion Gmail impossible ou interrompue. Vérifiez le réseau et l’accès IMAP/SMTP.");
    }
}
