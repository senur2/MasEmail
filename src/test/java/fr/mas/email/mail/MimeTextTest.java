package fr.mas.email.mail;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class MimeTextTest {
    private final Session session = Session.getInstance(new Properties());

    @Test void readsUtf8WithoutChangingTheMessage() throws Exception {
        var mail = new MimeMessage(session);
        mail.setText("Bonjour, à quelle date ?", "UTF-8");
        mail.saveChanges();
        assertEquals("Bonjour, à quelle date ?", new MimeText().read(mail));
        assertFalse(mail.isSet(Flags.Flag.SEEN));
    }

    @Test void prefersPlainTextAndExcludesAttachmentsAndNestedEmails() throws Exception {
        var plain = new MimeBodyPart(); plain.setText("Texte réel", "UTF-8");
        var html = new MimeBodyPart(); html.setContent("<p>Duplicata HTML</p>", "text/html; charset=UTF-8");
        var alternative = new MimeMultipart("alternative"); alternative.addBodyPart(plain); alternative.addBodyPart(html);
        var content = new MimeBodyPart(); content.setContent(alternative);
        var attachment = new MimeBodyPart(); attachment.setText("Ne pas lire"); attachment.setFileName("secret.txt");
        var nested = new MimeBodyPart(); nested.setContent(new MimeMessage(session), "message/rfc822");
        var mixed = new MimeMultipart(); mixed.addBodyPart(content); mixed.addBodyPart(attachment); mixed.addBodyPart(nested);
        var mail = new MimeMessage(session); mail.setContent(mixed); mail.saveChanges();
        assertEquals("Texte réel", new MimeText().read(mail));
    }

    @Test void extractsHtmlAsTextWithoutScriptsOrImages() throws Exception {
        var mail = new MimeMessage(session);
        mail.setContent("<html><head><style>secret css</style></head><body><p>Bonjour &amp; merci</p>"
                + "<script>secret script</script><img src='https://example.org/tracker'><p>À bientôt</p></body></html>",
                "text/html; charset=UTF-8");
        mail.saveChanges();
        String text = new MimeText().read(mail);
        assertTrue(text.contains("Bonjour & merci")); assertTrue(text.contains("À bientôt"));
        assertFalse(text.contains("secret")); assertFalse(text.contains("tracker"));
    }

    @Test void refusesOversizedAndDeepMimeContent() throws Exception {
        var large = new MimeBodyPart(); large.setText("a".repeat(MimeText.MAX_BYTES + 1));
        assertThrows(IOException.class, () -> new MimeText().read(large));
        var part = new MimeBodyPart(); part.setText("hello");
        for (int i = 0; i < 14; i++) {
            var multi = new MimeMultipart(); multi.addBodyPart(part);
            part = new MimeBodyPart(); part.setContent(multi);
        }
        var message = new MimeMessage(session);
        var root = new MimeMultipart(); root.addBodyPart(part);
        message.setContent(root); message.saveChanges();
        assertThrows(IOException.class, () -> new MimeText().read(message));
    }

    @ParameterizedTest @ValueSource(strings = {"a@x.com,b@y.com", "Name <a@x.com>", "a@x.com\r\nBcc: b@y.com", "bad", ""})
    void rejectsAmbiguousAndInjectedAddresses(String address) {
        assertThrows(IllegalArgumentException.class, () -> new MailAccount(address, ""));
    }

    @Test void validatesOutboundHeadersAndPreservesBody() {
        assertThrows(IllegalArgumentException.class, () -> new OutgoingMail("a@example.org", "Hello\nBcc: x", "Body", ""));
        assertThrows(IllegalArgumentException.class, () -> new OutgoingMail("a@example.org", "Hello", "Body", "x\r\nBad: y"));
        var mail = new OutgoingMail("a@example.org", "Réponse", "À bientôt\nMerci", "<id@example.org>");
        assertEquals("À bientôt\nMerci", mail.body());
    }
}
