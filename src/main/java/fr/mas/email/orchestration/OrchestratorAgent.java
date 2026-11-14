package fr.mas.email.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import fr.mas.email.agent.Agent;
import fr.mas.email.agent.CorrectingInput;
import fr.mas.email.agent.DecisionInput;
import fr.mas.email.agent.ScribeAgent;
import fr.mas.email.agent.ScribeInput;
import fr.mas.email.agent.WriterAgent;
import fr.mas.email.agent.WriterInput;
import fr.mas.email.contract.*;
import fr.mas.email.result.*;
import fr.mas.email.world.*;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;

/**
 * Sequential mediator. Public transitions are serialized on this instance. No LLM dependency.
 * Coordinates one explicit step at a time, not a complete email workflow.
 */
public final class OrchestratorAgent {
    public static final int MAX_CORRECTIONS = 3;
    private static final String ID = "OrchestratorAgent";
    private final WorldRepository<SharedWorld> repository;
    private final MessageFactory messages;
    private final JsonCodec json = new JsonCodec();
    private final MiniWorldService knowledge;
    private final WorldRepository<ExecutionTrace> traceRepository;
    private final CorrectionWorkflow correctionWorkflow;
    private final WorldRepository<DraftApproval> approvalRepository;
    private ExecutionTrace trace;
    private SharedWorld state;
    private SuspendedDiscussion suspendedDiscussion;
    private boolean persistenceFailed;

    public OrchestratorAgent(WorldRepository<SharedWorld> repository, MessageFactory messages) throws IOException {
        this(repository, messages, new MiniWorldService());
    }

    public OrchestratorAgent(WorldRepository<SharedWorld> repository, MessageFactory messages,
                             MiniWorldService knowledge) throws IOException {
        this(repository, messages, knowledge, null, null);
    }

    public OrchestratorAgent(WorldRepository<SharedWorld> repository, MessageFactory messages,
                             MiniWorldService knowledge, WorldRepository<ExecutionTrace> traceRepository,
                             CorrectionWorkflow correctionWorkflow) throws IOException {
        this(repository, messages, knowledge, traceRepository, correctionWorkflow, null);
    }

    public OrchestratorAgent(WorldRepository<SharedWorld> repository, MessageFactory messages,
                             MiniWorldService knowledge, WorldRepository<ExecutionTrace> traceRepository,
                             CorrectionWorkflow correctionWorkflow, WorldRepository<DraftApproval> approvalRepository)
            throws IOException {
        this.repository = Checks.required(repository, "repository");
        this.messages = Checks.required(messages, "messages");
        this.knowledge = Checks.required(knowledge, "knowledge");
        this.traceRepository = traceRepository;
        this.correctionWorkflow = correctionWorkflow;
        this.approvalRepository = approvalRepository;
        this.trace = traceRepository == null ? null : traceRepository.load().orElse(null);
        this.state = repository.load().orElse(null);
        if (state != null && (state.status() == SharedWorld.Status.WAITING_USER
                || state.status() == SharedWorld.Status.READY) && !state.detail().isEmpty()) {
            try {
                var stored = (com.fasterxml.jackson.databind.node.ObjectNode) json.read(state.detail(), JsonNode.class);
                if (!stored.has("context")) stored.putNull("context");
                if (stored.get("context") instanceof com.fasterxml.jackson.databind.node.ObjectNode context
                        && !context.has("correctionPlan")) context.putNull("correctionPlan");
                suspendedDiscussion = json.read(stored.toString(), SuspendedDiscussion.class);
                var suspendedTask = suspendedDiscussion.request().payload().task();
                Checks.rule(suspendedTask.taskId().equals(state.task().taskId())
                        && suspendedTask.correlationId().equals(state.task().correlationId())
                        && (state.status() != SharedWorld.Status.WAITING_USER || suspendedTask.equals(state.task())));
                Checks.rule(state.status() == SharedWorld.Status.WAITING_USER
                        ? suspendedDiscussion.resume() == null
                        : suspendedDiscussion.resume() != null && suspendedDiscussion.resume().task().equals(state.task()));
            } catch (RuntimeException e) {
                throw new IOException("Invalid persisted discussion");
            }
        }
        if (state != null && state.status() == SharedWorld.Status.RUNNING) {
            // A process restart never implicitly replays an interrupted operation.
            persist(new SharedWorld(state.task(), state.activeAgentId(), SharedWorld.Status.FAILED,
                    "Previous execution was interrupted; explicit retry authorization required"));
        }
    }

    public synchronized void startExecution(UUID id, String email) throws IOException {
        if (trace != null || state != null) throw new IllegalStateException("Execution already exists");
        saveTrace(new ExecutionTrace(id, email, 0, null, null, null, null));
    }

    public synchronized UUID executionId() {
        return Checks.required(trace, "execution trace").executionId();
    }

    public synchronized boolean canReviewDraft() {
        return trace != null && trace.draftResponse() != null && state != null && !persistenceFailed
                && (state.status() == SharedWorld.Status.COMPLETED || state.status() == SharedWorld.Status.FAILED);
    }

    public synchronized boolean canCorrectDraft() {
        return canReviewDraft() && trace.corrections() < MAX_CORRECTIONS;
    }

    public synchronized Optional<DraftApproval> draftApproval() throws IOException {
        if (!canReviewDraft() || approvalRepository == null) return Optional.empty();
        return approvalRepository.load().filter(approval -> approval.matches(trace));
    }

    public synchronized DraftApproval approveDraft(DraftResponse displayedDraft) throws IOException {
        if (!canReviewDraft() || !trace.draftResponse().equals(displayedDraft)) {
            throw new IllegalStateException("Only the current completed draft can be approved");
        }
        DraftResponseValidator.validateStructure(trace.responsePlan(), displayedDraft);
        var existing = draftApproval();
        if (existing.isPresent()) return existing.get();
        var approval = new DraftApproval(trace.executionId(), trace.corrections(), displayedDraft,
                java.time.Instant.now());
        Checks.required(approvalRepository, "approval repository").save(approval);
        return approval;
    }

    /** Called only after the user explicitly confirms sending this exact approved draft to this recipient. */
    public synchronized fr.mas.email.mail.MailDelivery sendApprovedReply(
            fr.mas.email.mail.MailAccount account, fr.mas.email.mail.ReceivedMail original,
            DraftResponse displayedDraft, char[] credential, fr.mas.email.mail.MailService mail,
            WorldRepository<fr.mas.email.mail.MailDelivery> deliveryRepository) throws IOException {
        var approval = draftApproval().orElseThrow(() -> new IllegalStateException("Validez d’abord le brouillon actuel."));
        if (!approval.draft().equals(displayedDraft) || !trace.email().equals(original.parserText())
                || !account.email().equals(original.accountEmail())) {
            throw new IllegalStateException("Le brouillon ou l’email sélectionné a changé. Générez et validez à nouveau.");
        }
        if (deliveryRepository.load().isPresent()) {
            throw new IllegalStateException("Une tentative d’envoi existe déjà. Vérifiez les messages envoyés dans Gmail.");
        }
        var outgoing = new fr.mas.email.mail.OutgoingMail(original.replyTo(), displayedDraft.subject(),
                displayedDraft.body(), original.messageId());
        // Record intent before SMTP: interrupted/ambiguous sends must never be replayed automatically.
        var sending = new fr.mas.email.mail.MailDelivery(trace.executionId(), outgoing.recipient(),
                fr.mas.email.mail.MailDelivery.Status.SENDING, null, java.time.Instant.now());
        deliveryRepository.save(sending);
        try {
            String messageId = mail.send(account, credential, outgoing);
            var sent = new fr.mas.email.mail.MailDelivery(trace.executionId(), outgoing.recipient(),
                    fr.mas.email.mail.MailDelivery.Status.SENT, messageId, java.time.Instant.now());
            deliveryRepository.save(sent);
            return sent;
        } catch (IOException | RuntimeException e) {
            try {
                deliveryRepository.save(new fr.mas.email.mail.MailDelivery(trace.executionId(), outgoing.recipient(),
                        fr.mas.email.mail.MailDelivery.Status.UNKNOWN, null, java.time.Instant.now()));
            } catch (IOException ignored) { /* SENDING already prevents another send. */ }
            throw new IOException("Envoi non confirmé. Vérifiez les messages envoyés dans Gmail avant toute nouvelle tentative.");
        }
    }

    public synchronized AgentResult<DraftResponse> requestCorrection(String executionId, String feedbackSource,
                                                                     String feedbackText) throws IOException {
        if (trace == null || !trace.executionId().toString().equals(executionId)) {
            throw new IllegalArgumentException("Unknown execution");
        }
        boolean explicitRecovery = state != null && state.status() == SharedWorld.Status.FAILED
                && "USER".equals(feedbackSource);
        if (state == null || (state.status() != SharedWorld.Status.COMPLETED && !explicitRecovery) || persistenceFailed) {
            throw new IllegalStateException("Execution cannot be corrected in its current state");
        }
        if (!"USER".equals(feedbackSource) && !state.activeAgentId().equals(feedbackSource)) {
            throw new IllegalArgumentException("Unknown feedback source for the current step");
        }
        var task = new TaskContext(trace.executionId(), UUID.randomUUID(), UUID.randomUUID());
        if (trace.corrections() >= MAX_CORRECTIONS) {
            return Errors.create(ErrorCode.MAX_CORRECTIONS_REACHED, ID, task);
        }
        var input = new CorrectingInput(feedbackSource, feedbackText, trace.emailAnalysis(), trace.routeDecision(),
                trace.responsePlan(), trace.draftResponse());
        var workflow = Checks.required(correctionWorkflow, "correction workflow");
        saveTrace(trace.countCorrection());
        if (explicitRecovery) {
            persist(new SharedWorld(task, "CorrectingAgent", SharedWorld.Status.READY,
                    "User explicitly requested another correction"));
        }
        return switch (workflow.diagnose(this, input, task)) {
            case Success(var message, var raw) -> workflow.restart(this, switch (message.payload().targetAgent()) {
                case PARSER_AGENT -> ErrorPlan.Stage.PARSER;
                case DECISION_AGENT -> ErrorPlan.Stage.DECISION;
                case WRITER_AGENT -> ErrorPlan.Stage.WRITER;
                case SCRIBE_AGENT -> ErrorPlan.Stage.SCRIBE;
                case ORCHESTRATOR -> throw new IllegalStateException("Memory correction must suspend");
            }, trace, message.payload());
            case DiscussionPlan<?> discussion -> discussion.retype();
            case ErrorPlan<?> error -> error.retype();
            case CorrectionRequest<?> ignored -> Errors.create(ErrorCode.INVALID_OUTPUT, ID, task);
        };
    }

    private void saveTrace(ExecutionTrace next) throws IOException {
        Checks.required(traceRepository, "trace repository").save(next);
        trace = next;
    }

    private void validateCorrectionTarget(CorrectionPlan correction) {
        boolean routeSelected = trace.routeDecision() != null
                && trace.routeDecision().decisionStatus() == RouteDecision.DecisionStatus.SELECTED
                && !trace.routeDecision().requiresUserConfirmation();
        Checks.rule(switch (correction.targetAgent()) {
            case PARSER_AGENT -> true;
            case DECISION_AGENT -> trace.emailAnalysis() != null;
            case WRITER_AGENT, ORCHESTRATOR -> routeSelected;
            case SCRIBE_AGENT -> trace.responsePlan() != null && !trace.responsePlan().requiresClarification();
        }, "Correction target does not have the required input in the trace");
    }

    public synchronized Optional<SharedWorld> state() {
        return Optional.ofNullable(state);
    }

    /** Available through suspension and explicit resumption, including after a restart. */
    public synchronized Optional<SuspendedDiscussion> suspendedDiscussion() {
        return Optional.ofNullable(suspendedDiscussion);
    }

    /** Executes the Writer step for a validated route that is ready for assignment. */
    public synchronized AgentResult<AgentMessage<ResponsePlan>> executeWriter(
            RouteDecision decision, WriterAgent writer, WriterInput input, TaskContext task,
            ResponsePlanValidator validation) {
        Checks.required(decision, "decision");
        Checks.required(writer, "writer");
        Checks.required(input, "input");
        if (decision.decisionStatus() != RouteDecision.DecisionStatus.SELECTED
                || decision.requiresUserConfirmation()
                || !writer.id().equals(decision.selectedAgentId())
                || !input.analysis().emailId().equals(decision.emailId())) {
            throw new IllegalArgumentException("Route must authorize this Writer for the input email");
        }
        return execute(writer, input, task, Contract.RESPONSE_PLAN, validation, decision);
    }

    public MiniWorldService.Knowledge writerKnowledge(String agentId) throws IOException {
        return knowledge.readKnowledge(agentId);
    }

    public synchronized <I, O> AgentResult<AgentMessage<O>> execute(Agent<I, O> agent, I input, TaskContext task,
                                                     Contract<O> contract, PlanValidationStrategy<O> validation) {
        return execute(agent, input, task, contract, validation, null);
    }

    public synchronized AgentResult<AgentMessage<DraftResponse>> execute(ScribeAgent agent, ResponsePlan input,
            TaskContext task, Contract<DraftResponse> contract, PlanValidationStrategy<DraftResponse> validation) {
        return execute(agent, new ScribeInput(input), task, contract, validation);
    }

    private <I, O> AgentResult<AgentMessage<O>> execute(Agent<I, O> agent, I input, TaskContext task,
            Contract<O> contract, PlanValidationStrategy<O> validation, RouteDecision route) {
        Checks.required(agent, "agent");
        Checks.required(input, "input");
        Checks.required(task, "task");
        Checks.required(contract, "contract");
        Checks.required(validation, "validation");
        if (trace != null && !trace.executionId().equals(task.correlationId())) {
            throw new IllegalArgumentException("Task belongs to another execution");
        }
        requireExecutable(agent.id(), task);
        try {
            persist(new SharedWorld(task, agent.id(), SharedWorld.Status.RUNNING, ""));
            suspendedDiscussion = null;
            AgentResult<O> result;
            try {
                result = agent.execute(input, task);
                if (result == null) result = executionError(ErrorCode.NO_RESULT, agent.id(), task, contract);
            } catch (RuntimeException e) {
                result = Errors.execution(Errors.from(e, ID, task), agent.id(), contract.stage(), contract.messageType());
            }
            result = validateResult(result, task, agent.id(), contract, validation);
            String initialResultJson = null;
            AgentMessage<O> initialMessage = null;
            SuspendedDiscussion.Context discussionContext = null;
            if (result instanceof Success<O> success) {
                initialMessage = messages.create(contract, success.value(), task.correlationId(), agent.id());
                initialResultJson = json.write(initialMessage);
                if (trace != null) {
                    var updated = trace.record(success.value());
                    if (updated != trace) saveTrace(updated);
                }
                result = DiscussionPlan.normalize(success.value(), task);
                if (result instanceof DiscussionPlan<O> discussion) {
                    if (input instanceof DecisionInput decision && success.value() instanceof RouteDecision routing) {
                        discussionContext = new SuspendedDiscussion.Context(decision.analysis(), routing, null,
                                List.of(), decision.correctionPlan());
                    } else if (input instanceof WriterInput writing && route != null
                            && success.value() instanceof ResponsePlan plan) {
                        var keys = List.copyOf(new LinkedHashSet<>(plan.missingInformation()));
                        discussionContext = new SuspendedDiscussion.Context(writing.analysis(), route, plan, keys,
                                writing.correctionPlan());
                        var questions = keys.stream().map(key -> clarificationQuestion(key, plan.language())).toList();
                        var proposal = keys.stream().allMatch(OrchestratorAgent::isKnowledgeKey)
                                ? MemoryProposal.ASK_SAVE_SCOPE : MemoryProposal.NONE;
                        result = new DiscussionPlan<>(questions, discussion.reason(), proposal, task);
                    } else if (success.value() instanceof CorrectionPlan correction && trace != null) {
                        var keys = correction.memoryProposals().stream().map(KnowledgeProposal::key).toList();
                        discussionContext = new SuspendedDiscussion.Context(trace.emailAnalysis(),
                                trace.routeDecision(), null, keys, correction);
                    }
                }
            }
            if (result instanceof DiscussionPlan<O> discussion) {
                var request = messages.create(Contract.DISCUSSION_PLAN, discussion, task.correlationId(), ID);
                suspendedDiscussion = new SuspendedDiscussion(request,
                        initialResultJson == null ? json.write(request) : initialResultJson, null, discussionContext);
            }
            AgentMessage<O> completedMessage = initialMessage;
            AgentResult<AgentMessage<O>> messageResult = result.map(value -> completedMessage);
            SharedWorld next = switch (result) {
                case Success<O> ignored -> new SharedWorld(task, agent.id(), SharedWorld.Status.COMPLETED, "");
                case CorrectionRequest<O> correction -> new SharedWorld(task, agent.id(),
                        SharedWorld.Status.COMPLETED, correction.feedbackText());
                case DiscussionPlan<O> discussion -> new SharedWorld(task, agent.id(),
                        SharedWorld.Status.WAITING_USER, json.write(suspendedDiscussion));
                case ErrorPlan<O> error -> new SharedWorld(task, agent.id(), SharedWorld.Status.FAILED,
                        error.code() + ": " + error.observedProblem() + " [errorId=" + error.errorId() + "]");
            };
            persist(next);
            return messageResult;
        } catch (IOException e) {
            suspendedDiscussion = null;
            persistenceFailed = true;
            state = new SharedWorld(task, agent.id(), SharedWorld.Status.FAILED, "World persistence failed");
            return Errors.create(ErrorCode.FILE_ACCESS_ERROR, ID, task);
        } catch (RuntimeException e) {
            suspendedDiscussion = null;
            var error = Errors.<AgentMessage<O>>from(e, ID, task);
            try {
                persist(new SharedWorld(task, agent.id(), SharedWorld.Status.FAILED,
                        error.code() + ": " + error.observedProblem()));
            } catch (IOException | RuntimeException storageFailure) {
                persistenceFailed = true;
                state = new SharedWorld(task, agent.id(), SharedWorld.Status.FAILED, "World persistence failed");
                return Errors.create(ErrorCode.FILE_ACCESS_ERROR, ID, task);
            }
            return error;
        }
    }

    /** Applies an explicit answer to the active pipeline discussion. Values are scoped to the requested keys. */
    public synchronized Resumption resumeDiscussion(UserDiscussionAnswer answer) throws IOException {
        Checks.required(answer, "answer");
        if (suspendedDiscussion == null
                || !answer.requestMessageId().equals(suspendedDiscussion.request().metadata().messageId())) {
            throw new IllegalStateException("No matching discussion request");
        }
        var request = suspendedDiscussion.request().payload();
        requirePending(request.task().taskId(), SharedWorld.Status.WAITING_USER);
        var context = Checks.required(suspendedDiscussion.context(), "discussion context");
        var scope = answer.scope();
        if (scope != UserDiscussionAnswer.MemoryScope.SESSION_ONLY) {
            if (!answer.storageConfirmed() || request.memoryProposal() != MemoryProposal.ASK_SAVE_SCOPE
                    || (context.plan() == null && !context.isMemoryCorrection())) {
                throw new SecurityException("Knowledge storage requires explicit user confirmation");
            }
            if (scope == UserDiscussionAnswer.MemoryScope.AGENT_SPECIFIC
                    && !context.route().selectedAgentId().equals(answer.memoryAgentId())) {
                throw new SecurityException("Knowledge can only be stored for the selected Writer");
            }
        }
        var values = answerValues(context.keys(), answer.answer());
        var storageResult = switch (scope) {
            case SESSION_ONLY -> DiscussionResume.StorageResult.NOT_STORED;
            case COMMON -> {
                knowledge.persistToCommonKnowledge(values);
                yield DiscussionResume.StorageResult.STORED_K_COMMUN;
            }
            case AGENT_SPECIFIC -> {
                knowledge.persistToAgentKnowledge(answer.memoryAgentId(), values);
                yield DiscussionResume.StorageResult.STORED_K_PERSO;
            }
        };
        var nextTask = state.task().nextAttempt();
        String nextAgent = context.isMemoryCorrection() ? context.route().selectedAgentId() : state.activeAgentId();
        // Keep the reply in the active session. SharedWorld only needs the next attempt identity.
        persist(new SharedWorld(nextTask, nextAgent, SharedWorld.Status.READY, ""));
        suspendedDiscussion = null;
        return new Resumption(nextTask, nextAgent, answer.answer(), storageResult, values);
    }

    private Map<String, String> answerValues(List<String> keys, String answer) {
        if (keys.isEmpty()) return Map.of();
        if (keys.size() == 1) return Map.of(keys.getFirst(), answer);
        var node = json.read(answer, JsonNode.class);
        if (!node.isObject() || node.size() != keys.size()) throw new IllegalArgumentException("Answer each requested key");
        var values = new LinkedHashMap<String, String>();
        for (String key : keys) values.put(key, Checks.text(node.path(key).textValue(), key));
        return values;
    }

    private static boolean isKnowledgeKey(String value) {
        return value.matches("[a-z][a-z0-9_]*");
    }

    private static String clarificationQuestion(String key, String language) {
        if (!isKnowledgeKey(key)) return key;
        String words = key.replace('_', ' ');
        return switch (language) {
            case "fr" -> switch (key) {
                case "date_publication_notes" -> "À quelle date les notes seront-elles publiées ?";
                case "date_publication_corriges" -> "À quelle date les corrigés seront-ils publiés ?";
                case "possibilite_consultation_copies" -> "Les étudiants pourront-ils consulter leurs copies ?";
                default -> "Que dois-je indiquer au sujet de « " + words + " » ?";
            };
            case "en" -> switch (key) {
                case "date_publication_notes" -> "When will the grades be published?";
                case "date_publication_corriges" -> "When will the solutions be published?";
                case "possibilite_consultation_copies" -> "Will students be able to review their exam papers?";
                default -> "What should I say about “" + words + "”?";
            };
            case "es" -> "¿Qué debo indicar sobre «" + words + "»?";
            case "de" -> "Was soll ich zu „" + words + "“ angeben?";
            case "it" -> "Che cosa devo indicare riguardo a «" + words + "»?";
            default -> "What should I say about “" + words + "”?";
        };
    }

    /** Call only for an actual answer from the user; the answer is not saved as private knowledge. */
    public synchronized Resumption resumeWithAnswer(UUID taskId, String userAnswer) throws IOException {
        Checks.text(userAnswer, "userAnswer");
        requirePending(taskId, SharedWorld.Status.WAITING_USER);
        if (suspendedDiscussion != null && suspendedDiscussion.context() != null
                && suspendedDiscussion.context().isMemoryCorrection()) {
            throw new IllegalStateException("Use resumeDiscussion to validate the proposed facts");
        }
        return prepareResumption(userAnswer);
    }

    /** Compatibility entry point for an answer without knowledge storage. */
    public synchronized Resumption resumeWithAnswer(UserDiscussionAnswer answer) throws IOException {
        Checks.required(answer, "answer");
        if (suspendedDiscussion == null
                || !answer.requestMessageId().equals(suspendedDiscussion.request().metadata().messageId())) {
            throw new IllegalStateException("No matching discussion request");
        }
        if (answer.memoryChoice() != UserDiscussionAnswer.MemoryChoice.NONE) {
            throw new IllegalStateException("Use resumeDiscussion for explicit knowledge storage");
        }
        return resumeWithAnswer(suspendedDiscussion.request().payload().task().taskId(), answer.answer());
    }

    /** Explicit controller action following a user decision, not a scheduler or automatic callback. */
    public synchronized Resumption authorizeRetry(UUID taskId) throws IOException {
        requirePending(taskId, SharedWorld.Status.FAILED);
        return prepareResumption("");
    }

    private Resumption prepareResumption(String answer) throws IOException {
        var nextTask = state.task().nextAttempt();
        var nextDiscussion = suspendedDiscussion == null ? null : new SuspendedDiscussion(
                suspendedDiscussion.request(), suspendedDiscussion.initialResultJson(),
                DiscussionResume.withoutStorage(nextTask, answer), suspendedDiscussion.context());
        var ready = new SharedWorld(nextTask, state.activeAgentId(), SharedWorld.Status.READY,
                nextDiscussion == null ? "" : json.write(nextDiscussion));
        persist(ready);
        suspendedDiscussion = nextDiscussion;
        persistenceFailed = false;
        return new Resumption(nextTask, ready.activeAgentId(), answer);
    }

    private void requirePending(UUID taskId, SharedWorld.Status expected) {
        if (state == null || state.status() != expected || !state.task().taskId().equals(taskId)) {
            throw new IllegalStateException("No matching suspended task");
        }
    }

    private void requireExecutable(String agentId, TaskContext task) {
        if (persistenceFailed) throw new IllegalStateException("Persistence failure requires explicit recovery");
        if (state == null) return;
        switch (state.status()) {
            case WAITING_USER, FAILED, RUNNING ->
                    throw new IllegalStateException("Execution is blocked pending an explicit user decision");
            case READY -> {
                if (!state.task().equals(task) || !state.activeAgentId().equals(agentId)) {
                    throw new IllegalStateException("Only the explicitly resumed task and agent may execute");
                }
            }
            case COMPLETED -> {
                if (state.task().taskId().equals(task.taskId())) {
                    throw new IllegalStateException("A completed task cannot be executed again");
                }
            }
        }
    }

    private <O> AgentResult<O> validateResult(AgentResult<O> result, TaskContext task,
                                             String component, Contract<O> contract, PlanValidationStrategy<O> validation) {
        try {
            return switch (result) {
                case Success<O> success -> {
                    validation.validate(success.value());
                    if (success.value() instanceof CorrectionPlan correction && trace != null) {
                        validateCorrectionTarget(correction);
                    }
                    yield success;
                }
                case CorrectionRequest<O> correction -> trace != null && !contract.equals(Contract.CORRECTION_PLAN)
                        ? correction : executionError(ErrorCode.INVALID_OUTPUT, component, task, contract);
                case DiscussionPlan<O> discussion -> discussion.task().equals(task)
                        && (contract.equals(Contract.ROUTE_DECISION) || contract.equals(Contract.RESPONSE_PLAN)
                        || contract.equals(Contract.CORRECTION_PLAN))
                        ? discussion : executionError(ErrorCode.INVALID_OUTPUT, component, task, contract);
                case ErrorPlan<O> error -> error.task().equals(task)
                        && (error.agentId() == null || error.agentId().equals(component))
                        ? error : executionError(ErrorCode.INVALID_OUTPUT, component, task, contract);
            };
        } catch (RuntimeException e) {
            String raw = result instanceof Success<O> success ? success.rawOutput() : null;
            return Errors.execution(Errors.validation(ErrorCode.INVALID_OUTPUT, ID, task, e, raw),
                    component, contract.stage(), contract.messageType());
        }
    }

    private <O> ErrorPlan<O> executionError(ErrorCode code, String agentId, TaskContext task, Contract<O> contract) {
        return Errors.execution(Errors.create(code, ID, task), agentId, contract.stage(), contract.messageType());
    }

    private void persist(SharedWorld next) throws IOException {
        repository.save(next);
        state = next;
    }
}
