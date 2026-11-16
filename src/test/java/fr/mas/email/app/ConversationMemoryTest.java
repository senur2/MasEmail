package fr.mas.email.app;

import fr.mas.email.contract.DraftResponse;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.mail.MailAccount;
import fr.mas.email.mail.MailService;
import fr.mas.email.mail.MailSummary;
import fr.mas.email.mail.OutgoingMail;
import fr.mas.email.mail.ReceivedMail;
import fr.mas.email.result.AgentResult;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.ConversationMemoryService;
import fr.mas.email.world.FileWorldStore;
import fr.mas.email.world.ThreadTurn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTest {
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();

    @Test void normalizesNestedPrefixesAndUsesFromInsteadOfReplyTo() throws Exception {
        var memory = memory();
        String original = memory.resolveThreadId(mail(1, "Notes du 4 novembre"));
        assertEquals("nina@example.org::notes du 4 novembre", original);
        assertEquals(original, memory.resolveThreadId(mail(2, " Re: Fwd: TR:  Notes du 4 NOVEMBRE ")));
        var unknown = new ReceivedMail("test@gmail.com", mail(2, "Re: Notes du 4 novembre").summary(),
                "reply@example.org", "", DemoScenario.EMAIL, "unknown-thread");
        assertEquals(original, memory.resolveThreadId(unknown));
        run(mail(1, "Notes du 4 novembre"), new ArrayList<>());
        var known = new ReceivedMail("test@gmail.com", mail(2, "Different subject").summary(),
                "reply@example.org", "", DemoScenario.EMAIL, original);
        assertEquals(original, memory.resolveThreadId(known));
    }

    @Test void sendsConfirmedHistoryToAllFourAgentsAndOmitsItOnFirstTurn() throws Exception {
        var first = mail(1, "Notes du 4 novembre");
        var firstRequests = new ArrayList<String>();
        var runner = run(first, firstRequests);
        assertEquals(4, firstRequests.size());
        assertTrue(firstRequests.stream().noneMatch(value -> value.contains("threadHistory")));
        assertFalse(firstRequests.getFirst().contains("Ancienne citation"));

        var memory = memory();
        String threadId = memory.resolveThreadId(first);
        var runPath = directory.resolve("run-1");
        DraftResponse draft = runner.review(runPath).draft();
        runner.approve(draft, runPath);
        assertEquals(ThreadTurn.DeliveryStatus.DRAFT_GENERATED, memory.loadDigest(threadId).recentTurns().getFirst().deliveryStatus());
        runner.send(new MailAccount("test@gmail.com", "Alex"), first, draft, new char[0], new MailService() {
            @Override public List<MailSummary> listInbox(MailAccount account, char[] credential) { throw new AssertionError(); }
            @Override public ReceivedMail read(MailAccount account, char[] credential, MailSummary mail) { throw new AssertionError(); }
            @Override public String send(MailAccount account, char[] credential, OutgoingMail mail) { return "<sent@example.org>"; }
        }, runPath);
        var secondRequests = new ArrayList<String>();
        run(mail(2, "Re: Notes du 4 novembre"), secondRequests);
        assertEquals(4, secondRequests.size());
        for (String request : secondRequests) {
            var history = json.read(request, com.fasterxml.jackson.databind.JsonNode.class).path("threadHistory");
            assertEquals(threadId, history.path("threadId").asText());
            var turns = history.path("recentTurns");
            assertEquals(1, turns.size());
            assertEquals("APPROVED_AND_SENT", turns.get(0).path("deliveryStatus").asText());
            assertTrue(turns.get(0).path("finalSentBodySnippet").asText().contains("18 novembre"));
        }
    }

    @Test void loadsOnlyTheThreePreviousTurnsAndDoesNotDuplicateReopenedEmail() throws Exception {
        for (int i = 1; i <= 6; i++) run(mail(i, "Notes"), new ArrayList<>());
        var memory = memory();
        String threadId = memory.resolveThreadId(mail(1, "Notes"));
        assertEquals(List.of(4, 5, 6), indices(memory.loadDigest(threadId).recentTurns()));
        assertEquals(List.of(3, 4, 5), indices(memory.loadDigest(threadId, mail(6, "Notes").emailId()).recentTurns()));
        var reopened = mail(6, "Notes");
        var trace = new FileWorldStore(directory.resolve("run-6"), json).executionTrace().load().orElseThrow();
        memory.recordDraftTurn(threadId, reopened, trace);
        assertEquals(List.of(4, 5, 6), indices(memory.loadDigest(threadId).recentTurns()));
    }

    @Test void removesQuotedLinesAndFrenchAndEnglishReplyDelimiters() {
        assertEquals("Et mardi ?\nMerci.", ReceivedMail.stripQuotes("Et mardi ?\r\n  > ancienne question\r\nMerci.\r\nLe 4 novembre, Nina a écrit :\r\nCité"));
        assertEquals("Et mardi ?", ReceivedMail.stripQuotes("Et mardi ?\nOn Monday Nina wrote:\nCité"));
        assertEquals("Et mardi ?", ReceivedMail.stripQuotes("Et mardi ?\n-----Original Message-----\nCité"));
    }

    private PipelineRunner run(ReceivedMail mail, List<String> requests) throws Exception {
        var delegate = DemoScenario.gateway(json);
        LlmGateway recording = new LlmGateway() {
            @Override public <T> AgentResult<T> generate(LlmRequest<T> request, TaskContext task, String component) {
                requests.add(request.input());
                return delegate.generate(request, task, component);
            }
        };
        var runner = new PipelineRunner(json, null, true, directory.resolve("threads"), mail);
        assertInstanceOf(Success.class, runner.run(mail.parserText(), recording, "simulation",
                directory.resolve("run-" + mail.summary().uid()), ignored -> {}));
        return runner;
    }

    private ConversationMemoryService memory() throws Exception {
        return new ConversationMemoryService(new FileWorldStore(directory.resolve("threads"), json));
    }

    private static ReceivedMail mail(int uid, String subject) {
        return new ReceivedMail("test@gmail.com", new MailSummary(uid, 10, "Nina <NINA@example.org>", subject, Instant.EPOCH),
                "reply@example.org", "", DemoScenario.EMAIL + "\n> Ancienne citation");
    }

    private static List<Integer> indices(List<ThreadTurn> turns) { return turns.stream().map(ThreadTurn::turnIndex).toList(); }
}
