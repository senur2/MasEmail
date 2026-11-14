package fr.mas.email.mail;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real Jakarta adapter with in-memory protocol providers, never Google credentials. */
public class JakartaGmailServiceTest {
    static List<Message> messages;
    static MockFolder inbox;
    static int sends;
    static Message sent;
    static boolean failAuth;
    static int closes;
    private JakartaGmailService service;
    private Session session;
    private final MailAccount account = new MailAccount("test@gmail.com", "Élodie");
    private final char[] password = "abcd efgh ijkl mnop".toCharArray();

    @BeforeEach void setup() throws Exception {
        messages = new ArrayList<>(); sends = 0; sent = null; failAuth = false; closes = 0;
        session = Session.getInstance(JakartaGmailService.properties());
        session.setProvider(new Provider(Provider.Type.STORE, "imaps", MockStore.class.getName(), "test", "1"));
        session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", MockTransport.class.getName(), "test", "1"));
        service = new JakartaGmailService(session);
    }

    @Test void requiresTlsAndCertificateIdentityChecksAndBoundedTimeouts() {
        var properties = JakartaGmailService.properties();
        assertEquals("PLAIN LOGIN", properties.getProperty("mail.imaps.auth.mechanisms"));
        assertEquals("PLAIN LOGIN", properties.getProperty("mail.smtp.auth.mechanisms"));
        assertEquals("true", properties.getProperty("mail.smtp.starttls.required"));
        assertEquals("true", properties.getProperty("mail.imaps.peek"));
        assertEquals("true", properties.getProperty("mail.imaps.ssl.checkserveridentity"));
        assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertEquals("20000", properties.getProperty("mail.smtp.timeout"));
        assertNull(properties.getProperty("mail.imaps.ssl.trust"));
    }

    @Test void listsOnlyFiftyLatestHeadersInReadOnlyMode() throws Exception {
        for (int i = 1; i <= 55; i++) messages.add(message("Sujet " + i));
        var listed = service.listInbox(account, password);
        assertEquals(50, listed.size()); assertEquals("Sujet 55", listed.getFirst().subject());
        assertEquals("Sujet 6", listed.getLast().subject());
        assertEquals(Folder.READ_ONLY, inbox.openedMode); assertFalse(inbox.open);
        assertTrue(closes > 0); assertEquals(0, sends);
        assertFalse(messages.getFirst().isSet(Flags.Flag.SEEN));
    }

    @Test void readsStableUidReplyAddressAndTextThenClosesTheFolder() throws Exception {
        var message = message("Question");
        message.setReplyTo(new Address[]{new InternetAddress("reply@example.org")});
        message.setHeader("Message-ID", "<original@example.org>");
        messages.add(message);
        var received = service.read(account, password, new MailSummary(1, 99, "sender", "Question", Instant.EPOCH));
        assertEquals("reply@example.org", received.replyTo());
        assertEquals("Bonjour é", received.body());
        assertEquals("<original@example.org>", received.messageId());
        assertFalse(message.isSet(Flags.Flag.SEEN)); assertFalse(inbox.open);
    }

    @Test void rejectsStaleUidValidityMissingMessageAndAmbiguousReply() throws Exception {
        var message = message("Question"); messages.add(message);
        assertThrows(IOException.class, () -> service.read(account, password, new MailSummary(1, 20, "s", "q", Instant.EPOCH)));
        assertFalse(inbox.open);
        assertThrows(IOException.class, () -> service.read(account, password, new MailSummary(8, 99, "s", "q", Instant.EPOCH)));
        message.setReplyTo(new Address[]{new InternetAddress("a@example.org"), new InternetAddress("b@example.org")});
        assertThrows(IOException.class, () -> service.read(account, password, new MailSummary(1, 99, "s", "q", Instant.EPOCH)));
    }

    @Test void constructsOneUtf8ReplyWithoutCcBccOrAttachments() throws Exception {
        var id = service.send(account, password, new OutgoingMail("reply@example.org", "Réponse", "À bientôt", "<original@example.org>"));
        assertEquals(1, sends); assertNotNull(id);
        assertEquals("test@gmail.com", ((InternetAddress) sent.getFrom()[0]).getAddress());
        assertEquals("Élodie", ((InternetAddress) sent.getFrom()[0]).getPersonal());
        assertEquals(1, sent.getAllRecipients().length);
        assertEquals("reply@example.org", ((InternetAddress) sent.getAllRecipients()[0]).getAddress());
        assertEquals("Réponse", sent.getSubject()); assertEquals("À bientôt", sent.getContent());
        assertNull(sent.getRecipients(Message.RecipientType.CC)); assertNull(sent.getRecipients(Message.RecipientType.BCC));
        assertEquals("<original@example.org>", sent.getHeader("In-Reply-To")[0]);
        assertTrue(sent.isMimeType("text/plain"));
    }

    @Test void sanitizesAuthenticationErrorsAndDoesNotRetry() {
        failAuth = true;
        var failure = assertThrows(IOException.class, () -> service.listInbox(account, password));
        assertTrue(failure.getMessage().contains("mot de passe d’application"));
        assertFalse(failure.getMessage().contains("abcdefghijklmnop")); assertNull(failure.getCause());
        assertEquals(0, sends);
    }

    private MimeMessage message(String subject) throws Exception {
        var message = new MimeMessage(session);
        message.setFrom("sender@example.org"); message.setSubject(subject, "UTF-8");
        message.setText("Bonjour é", "UTF-8"); message.saveChanges(); return message;
    }

    public static class MockStore extends Store {
        public MockStore(Session session, URLName url) { super(session, url); }
        @Override protected boolean protocolConnect(String host, int port, String user, String password) throws MessagingException {
            assertEquals("imap.gmail.com", host); assertEquals(993, port); assertEquals("test@gmail.com", user);
            assertEquals("abcdefghijklmnop", password);
            if (failAuth) throw new AuthenticationFailedException(password);
            return true;
        }
        @Override public Folder getDefaultFolder() { return getFolder("INBOX"); }
        @Override public Folder getFolder(String name) { assertEquals("INBOX", name); return inbox = new MockFolder(this); }
        @Override public Folder getFolder(URLName url) { return getFolder("INBOX"); }
        @Override public synchronized void close() throws MessagingException { closes++; super.close(); }
    }

    public static class MockTransport extends Transport {
        public MockTransport(Session session, URLName url) { super(session, url); }
        @Override protected boolean protocolConnect(String host, int port, String user, String password) {
            assertEquals("smtp.gmail.com", host); assertEquals(587, port); assertEquals("test@gmail.com", user);
            assertEquals("abcdefghijklmnop", password); return true;
        }
        @Override public void sendMessage(Message message, Address[] recipients) {
            assertEquals(1, recipients.length); sends++; sent = message;
        }
    }

    static class MockFolder extends Folder implements UIDFolder {
        boolean open; int openedMode;
        MockFolder(Store store) { super(store); }
        public String getName() { return "INBOX"; }
        public String getFullName() { return "INBOX"; }
        public Folder getParent() { return null; }
        public boolean exists() { return true; }
        public Folder[] list(String pattern) { return new Folder[0]; }
        public char getSeparator() { return '/'; }
        public int getType() { return HOLDS_MESSAGES; }
        public boolean create(int type) { throw new AssertionError(); }
        public boolean hasNewMessages() { return false; }
        public Folder getFolder(String name) { throw new AssertionError(); }
        public boolean delete(boolean recurse) { throw new AssertionError(); }
        public boolean renameTo(Folder folder) { throw new AssertionError(); }
        public void open(int mode) { assertEquals(READ_ONLY, mode); openedMode = mode; open = true; }
        public void close(boolean expunge) { assertFalse(expunge); open = false; }
        public boolean isOpen() { return open; }
        public Flags getPermanentFlags() { return new Flags(); }
        public int getMessageCount() { return messages.size(); }
        public Message getMessage(int number) { return messages.get(number - 1); }
        public void appendMessages(Message[] messages) { throw new AssertionError(); }
        public Message[] expunge() { throw new AssertionError(); }
        public long getUIDValidity() { return 99; }
        public long getUIDNext() { return messages.size() + 1; }
        public Message getMessageByUID(long uid) { return uid > 0 && uid <= messages.size() ? messages.get((int) uid - 1) : null; }
        public Message[] getMessagesByUID(long start, long end) { throw new AssertionError(); }
        public Message[] getMessagesByUID(long[] uids) { throw new AssertionError(); }
        public long getUID(Message message) { return messages.indexOf(message) + 1; }
    }
}
