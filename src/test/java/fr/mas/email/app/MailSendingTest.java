package fr.mas.email.app;

import fr.mas.email.contract.*;
import fr.mas.email.mail.*;
import fr.mas.email.result.*;
import fr.mas.email.world.FileWorldStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MailSendingTest {
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();
    private final MailAccount account = new MailAccount("test@gmail.com", "Alex");
    private final ReceivedMail original = new ReceivedMail(account.email(),
            new MailSummary(42, 10, "Nina <nina@example.org>", "INF312", Instant.now()),
            "nina@example.org", "<original@example.org>", DemoScenario.EMAIL);
    private final RecordingMail service = new RecordingMail();

    @Test void requiresApprovalAndSendsTheExactDraftToTheOriginalReplyAddressOnce() throws Exception {
        var runner = started();
        var draft = runner.review(directory).draft();
        assertThrows(IllegalStateException.class, () -> runner.send(account, original, draft, secret(), service, directory));
        assertEquals(0, service.sends);
        runner.approve(draft, directory);
        var sent = runner.send(account, original, draft, secret(), service, directory);
        assertEquals(MailDelivery.Status.SENT, sent.status());
        assertEquals(1, service.sends);
        assertEquals(new OutgoingMail(original.replyTo(), draft.subject(), draft.body(), original.messageId()), service.last);
        assertThrows(IllegalStateException.class, () -> new PipelineRunner(json).send(account, original, draft, secret(), service, directory));
        assertEquals(1, service.sends);
        assertFalse(Files.readString(directory.resolve("agents.log")).contains("secret-token"));
        assertEquals(MailDelivery.Status.SENT, new FileWorldStore(directory, json).mailDelivery().load().orElseThrow().status());
    }

    @Test void refusesChangedDraftEmailRecipientAndAccount() throws Exception {
        var runner = started(); var draft = runner.review(directory).draft(); runner.approve(draft, directory);
        var edited = new DraftResponse(draft.emailId(), draft.planId(), draft.subject(), "Changed body", draft.status());
        assertThrows(IllegalStateException.class, () -> runner.send(account, original, edited, secret(), service, directory));
        var changed = new ReceivedMail(account.email(), original.summary(), "someone@example.org", original.messageId(), original.body());
        assertThrows(IllegalStateException.class, () -> runner.send(account, changed, draft, secret(), service, directory));
        assertThrows(IllegalStateException.class, () -> runner.send(new MailAccount("other@gmail.com", ""), original, draft, secret(), service, directory));
        assertEquals(0, service.sends);
    }

    @Test void recordsAmbiguousFailureAndNeverRetriesEvenAfterRestart() throws Exception {
        var runner = started(); var draft = runner.review(directory).draft(); runner.approve(draft, directory);
        service.fail = true;
        assertThrows(IOException.class, () -> runner.send(account, original, draft, secret(), service, directory));
        assertEquals(MailDelivery.Status.UNKNOWN, new FileWorldStore(directory, json).mailDelivery().load().orElseThrow().status());
        assertThrows(IllegalStateException.class, () -> new PipelineRunner(json).send(account, original, draft, secret(), service, directory));
        assertEquals(1, service.sends);
        assertFalse(Files.readString(directory.resolve("agents.log")).contains("secret-token"));
    }

    @Test void correctionRevokesSendAuthorization() throws Exception {
        var runner = started(); var draft = runner.review(directory).draft(); runner.approve(draft, directory);
        assertInstanceOf(Success.class, runner.correct("Tutoie-le", DemoScenario.correction(json), directory, p -> {}));
        assertThrows(IllegalStateException.class, () -> runner.send(account, original, runner.review(directory).draft(), secret(), service, directory));
        assertEquals(0, service.sends);
    }

    private PipelineRunner started() throws Exception {
        var runner = new PipelineRunner(json);
        assertInstanceOf(Success.class, runner.run(original.parserText(), DemoScenario.gateway(json), "simulation", directory, p -> {}));
        return runner;
    }
    private char[] secret() { return "secret-token".toCharArray(); }
    private static class RecordingMail implements MailService {
        int sends; boolean fail; OutgoingMail last;
        public List<MailSummary> listInbox(MailAccount a, char[] p) { throw new AssertionError(); }
        public ReceivedMail read(MailAccount a, char[] p, MailSummary m) { throw new AssertionError(); }
        public String send(MailAccount a, char[] p, OutgoingMail mail) throws IOException {
            sends++; last = mail;
            if (fail) throw new IOException("secret-token echoed by server");
            return "<sent@example.org>";
        }
    }
}
