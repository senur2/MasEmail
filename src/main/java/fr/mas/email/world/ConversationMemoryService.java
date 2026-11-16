package fr.mas.email.world;

import fr.mas.email.mail.ReceivedMail;
import fr.mas.email.orchestration.ExecutionTrace;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Local, account-scoped conversation memory. Only the requested thread is read. */
public final class ConversationMemoryService {
    private static final Object WRITE_LOCK = new Object();
    private static final Pattern PREFIXES = Pattern.compile("(?iu)^(?:(?:re|fwd|tr)\\s*:\\s*)+");
    private final FileWorldStore store;

    public ConversationMemoryService(FileWorldStore store) { this.store = store; }

    public static String normalizeSubject(String subject) {
        String condensed = subject.replaceAll("(?U)\\s+", " ").strip();
        return PREFIXES.matcher(condensed).replaceFirst("").strip().toLowerCase(Locale.ROOT);
    }

    public String resolveThreadId(ReceivedMail mail) throws IOException {
        if (mail.threadId() != null && store.conversation(mail.threadId()).load().isPresent()) return mail.threadId();
        return mail.senderEmail() + "::" + normalizeSubject(mail.summary().subject());
    }

    public ThreadHistoryDigest loadDigest(String threadId) throws IOException { return loadDigest(threadId, null); }

    public ThreadHistoryDigest loadDigest(String threadId, String currentEmailId) throws IOException {
        var thread = store.conversation(threadId).load();
        if (thread.isEmpty()) return new ThreadHistoryDigest(threadId, null, List.of());
        var turns = thread.get().turns();
        int end = indexOf(turns, currentEmailId);
        if (end < 0) end = turns.size();
        return new ThreadHistoryDigest(threadId, end == 0 ? null : turns.get(end - 1).selectedAgentId(),
                turns.subList(0, end));
    }

    public void recordDraftTurn(String threadId, ReceivedMail mail, ExecutionTrace trace) throws IOException {
        var analysis = trace.emailAnalysis();
        var route = trace.routeDecision();
        var plan = trace.responsePlan();
        var draft = trace.draftResponse();
        synchronized (WRITE_LOCK) {
            var repository = store.conversation(threadId);
            var thread = repository.load().orElseGet(() -> new ConversationThread(threadId,
                    normalizeSubject(mail.summary().subject()), mail.senderEmail(), null, List.of()));
            var turns = new ArrayList<>(thread.turns());
            int index = indexOf(turns, analysis.emailId());
            var previous = index < 0 ? null : turns.get(index);
            var turn = new ThreadTurn(previous == null ? turns.size() + 1 : previous.turnIndex(), analysis.emailId(),
                    mail.summary().receivedAt().toString(), analysis.intent(), analysis.requestedInformation(),
                    analysis.senderClaims(), route.selectedAgentId(), plan.factsToCommunicate().stream()
                            .map(fr.mas.email.contract.ResponsePlan.Fact::text).toList(), draft.subject(), draft.body(),
                    previous != null && previous.deliveryStatus() == ThreadTurn.DeliveryStatus.APPROVED_AND_SENT
                            ? previous.deliveryStatus() : ThreadTurn.DeliveryStatus.DRAFT_GENERATED,
                    previous == null ? null : previous.finalSentBodySnippet());
            if (index < 0) turns.add(turn); else turns.set(index, turn);
            repository.save(new ConversationThread(threadId, thread.normalizedSubject(), thread.participantEmail(),
                    turns.getLast().selectedAgentId(), turns));
        }
    }

    public void recordApprovedReply(String threadId, String emailId, String finalSentBody) throws IOException {
        synchronized (WRITE_LOCK) {
            var repository = store.conversation(threadId);
            var thread = repository.load().orElseThrow(() -> new IOException("Conversation not found"));
            var turns = new ArrayList<>(thread.turns());
            int index = indexOf(turns, emailId);
            if (index < 0) throw new IOException("Conversation turn not found");
            var turn = turns.get(index);
            turns.set(index, new ThreadTurn(turn.turnIndex(), turn.emailId(), turn.receivedAt(), turn.incomingIntent(),
                    turn.requestedInformation(), turn.senderClaims(), turn.selectedAgentId(), turn.plannedFacts(),
                    turn.generatedDraftSubject(), turn.generatedDraftSnippet(),
                    ThreadTurn.DeliveryStatus.APPROVED_AND_SENT, finalSentBody));
            repository.save(new ConversationThread(threadId, thread.normalizedSubject(), thread.participantEmail(),
                    thread.lastSelectedAgentId(), turns));
        }
    }

    private static int indexOf(List<ThreadTurn> turns, String emailId) {
        for (int i = 0; i < turns.size(); i++) if (turns.get(i).emailId().equals(emailId)) return i;
        return -1;
    }
}
