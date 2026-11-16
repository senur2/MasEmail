package fr.mas.email.app;

import com.fasterxml.jackson.databind.JsonNode;
import fr.mas.email.agent.AgentConfig;
import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.agent.CorrectingInput;
import fr.mas.email.agent.DecisionInput;
import fr.mas.email.agent.ParserInput;
import fr.mas.email.agent.ScribeInput;
import fr.mas.email.agent.WriterInput;
import fr.mas.email.agent.WriterProfiles;
import fr.mas.email.contract.AgentMessage;
import fr.mas.email.contract.Checks;
import fr.mas.email.contract.Contract;
import fr.mas.email.contract.CorrectionPlan;
import fr.mas.email.contract.DraftResponse;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.MessageFactory;
import fr.mas.email.contract.RouteDecision;
import fr.mas.email.contract.ResponsePlan;
import fr.mas.email.contract.UserDiscussionAnswer;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.orchestration.DraftResponseValidator;
import fr.mas.email.orchestration.CorrectionWorkflow;
import fr.mas.email.orchestration.ExecutionTrace;
import fr.mas.email.orchestration.DraftApproval;
import fr.mas.email.orchestration.OrchestratorAgent;
import fr.mas.email.orchestration.Resumption;
import fr.mas.email.orchestration.ResponsePlanValidator;
import fr.mas.email.orchestration.RouteDecisionValidator;
import fr.mas.email.orchestration.SuspendedDiscussion;
import fr.mas.email.result.AgentResult;
import fr.mas.email.result.CorrectionRequest;
import fr.mas.email.result.DiscussionPlan;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.FileWorldStore;
import fr.mas.email.world.ConversationMemoryService;
import fr.mas.email.world.ThreadHistoryDigest;
import fr.mas.email.mail.ReceivedMail;
import fr.mas.email.world.MiniWorldService;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.StandardOpenOption.APPEND;

/** Runs the existing mediator one step at a time in an isolated test directory. */
final class PipelineRunner {
    static final String LOG_NAME = "agents.log";
    private static final List<String> AUTHORIZED_WRITERS = List.of(
            "AcademicWriter", "ResearchWriter", "FinanceWriter", "PersonalWriter", "GenericWriter");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    enum Status { RUNNING, COMPLETE, WAITING_USER, FAILED }
    record Progress(ErrorPlan.Stage stage, String agentId, Status status) {}
    record DraftReview(DraftResponse draft, boolean canApprove, boolean canCorrect, boolean approved) {}

    private final JsonCodec json;
    private Path knowledgeDirectory;
    private final boolean packagedDefaults;
    private final Map<String, Map<String, String>> sessionKnowledge = new HashMap<>();
    private Path sessionDirectory;
    private final Path threadDirectory;
    private final ReceivedMail incomingMail;
    private ThreadHistoryDigest threadHistory;

    PipelineRunner(JsonCodec json) {
        this(json, null);
    }

    PipelineRunner(JsonCodec json, Path knowledgeDirectory) {
        this(json, knowledgeDirectory, true);
    }

    PipelineRunner(JsonCodec json, Path knowledgeDirectory, boolean packagedDefaults) {
        this(json, knowledgeDirectory, packagedDefaults, null, null);
    }

    PipelineRunner(JsonCodec json, Path knowledgeDirectory, boolean packagedDefaults, Path threadDirectory, ReceivedMail incomingMail) {
        this.json = json;
        this.knowledgeDirectory = knowledgeDirectory;
        this.packagedDefaults = packagedDefaults;
        this.threadDirectory = threadDirectory;
        this.incomingMail = incomingMail;
    }

    private ConversationMemoryService conversations() throws IOException {
        return new ConversationMemoryService(new FileWorldStore(threadDirectory, json));
    }

    private ThreadHistoryDigest previousTurns() {
        return threadHistory == null || threadHistory.recentTurns().isEmpty() ? null : threadHistory;
    }

    private AgentFactory factory(LlmGateway gateway, FileWorldStore worlds) {
        return new AgentFactory(gateway, worlds, new MiniWorldService(null, packagedDefaults));
    }

    synchronized void useKnowledgeDirectory(Path directory) { knowledgeDirectory = Checks.required(directory, "knowledge directory"); }

    synchronized AgentResult<DraftResponse> run(String email, LlmGateway gateway, String engine, Path directory,
                                   Consumer<Progress> onProgress) throws IOException {
        Checks.text(email, "email");
        useSession(directory);
        Files.createDirectories(directory);
        UUID correlationId = UUID.randomUUID();
        try (var output = Files.newBufferedWriter(directory.resolve(LOG_NAME), UTF_8, CREATE_NEW, WRITE)) {
            var log = new RunLog(output, correlationId, onProgress);
            log.write("MAS Email — journal de test\n"
                    + "Démarrage  " + now() + "\n"
                    + "Moteur     " + engine + "\n"
                    + "Exécution  " + correlationId + "\n");
            var worlds = new FileWorldStore(directory, json);
            var factory = factory(gateway, worlds);
            var mediator = mediator(worlds, workflow(gateway, worlds, log));
            try {
                if (incomingMail != null) {
                    var memory = conversations();
                    threadHistory = memory.loadDigest(memory.resolveThreadId(incomingMail), incomingMail.emailId());
                }
                mediator.startExecution(correlationId, email);
                return resolveAgentCorrections(execute(email, null, factory, mediator, log), mediator, log);
            } catch (RuntimeException e) {
                log.write("\nIncident du banc de test  " + e.getClass().getSimpleName() + "\n");
                throw e;
            }
        }
    }

    private OrchestratorAgent mediator(FileWorldStore worlds) throws IOException {
        return mediator(worlds, null);
    }

    private OrchestratorAgent mediator(FileWorldStore worlds, CorrectionWorkflow workflow) throws IOException {
        var service = new MiniWorldService(knowledgeDirectory == null ? null
                : new FileWorldStore(knowledgeDirectory, json), packagedDefaults);
        return new OrchestratorAgent(worlds.sharedWorld(), new MessageFactory(Clock.systemUTC()), service,
                worlds.executionTrace(), workflow, worlds.draftApproval());
    }

    synchronized DraftReview review(Path directory) throws IOException {
        var worlds = new FileWorldStore(directory, json);
        var mediator = mediator(worlds);
        var trace = worlds.executionTrace().load().orElseThrow();
        boolean approved = mediator.draftApproval().isPresent();
        return new DraftReview(trace.draftResponse(), mediator.canReviewDraft() && !approved,
                mediator.canCorrectDraft(), approved);
    }

    synchronized DraftApproval approve(DraftResponse displayedDraft, Path directory) throws IOException {
        var worlds = new FileWorldStore(directory, json);
        try (var output = Files.newBufferedWriter(directory.resolve(LOG_NAME), UTF_8, APPEND)) {
            var approval = mediator(worlds).approveDraft(displayedDraft);
            output.write("\n=== OrchestratorAgent / VALIDATION DU BROUILLON ===\n"
                    + json.writePretty(approval) + "\n");
            return approval;
        }
    }

    synchronized void simulateSend(DraftResponse draft, Path directory) throws IOException {
        if (threadHistory == null) throw new IllegalStateException("A conversation is required");
        var worlds = new FileWorldStore(directory, json);
        var approval = mediator(worlds).draftApproval()
                .filter(item -> item.draft().equals(draft))
                .orElseThrow(() -> new IllegalStateException("A current draft approval is required"));
        conversations().recordApprovedReply(threadHistory.threadId(), draft.emailId(), approval.draft().body());
        Files.writeString(directory.resolve(LOG_NAME), "\n=== Envoi simulé · historique du banc de test ===\n", UTF_8, APPEND);
    }

    synchronized fr.mas.email.mail.MailDelivery send(fr.mas.email.mail.MailAccount account,
            fr.mas.email.mail.ReceivedMail original, DraftResponse draft, char[] credential,
            fr.mas.email.mail.MailService mail, Path directory) throws IOException {
        var worlds = new FileWorldStore(directory, json);
        try (var output = Files.newBufferedWriter(directory.resolve(LOG_NAME), UTF_8, APPEND)) {
            output.write("\n=== OrchestratorAgent / ENVOI VALIDÉ PAR L’UTILISATEUR ===\n");
            output.flush();
            fr.mas.email.mail.MailDelivery delivery;
            try {
                delivery = mediator(worlds).sendApprovedReply(account, original, draft, credential, mail, worlds.mailDelivery());
            } catch (IOException | RuntimeException e) {
                output.write("Envoi arrêté ; consulter mail-delivery.json et vérifier Gmail.\n");
                throw e;
            }
            output.write(json.writePretty(delivery) + "\n");
            if (threadHistory != null) {
                try {
                    conversations().recordApprovedReply(threadHistory.threadId(), draft.emailId(), draft.body());
                } catch (IOException | RuntimeException e) {
                    output.write("Envoi confirmé, mais mise à jour de l’historique impossible.\n");
                }
            }
            return delivery;
        }
    }

    SuspendedDiscussion pending(Path directory) throws IOException {
        var worlds = new FileWorldStore(directory, json);
        return mediator(worlds).suspendedDiscussion().orElseThrow();
    }

    synchronized AgentResult<DraftResponse> resume(UserDiscussionAnswer answer, LlmGateway gateway, Path directory,
                                      Consumer<Progress> onProgress) throws IOException {
        useSession(directory);
        var worlds = new FileWorldStore(directory, json);
        var suspended = mediator(worlds).suspendedDiscussion().orElseThrow();
        var context = Checks.required(suspended.context(), "discussion context");
        var factory = factory(gateway, worlds);
        try (var output = Files.newBufferedWriter(directory.resolve(LOG_NAME), UTF_8, APPEND)) {
            var log = new RunLog(output, suspended.request().payload().task().correlationId(), onProgress);
            var mediator = mediator(worlds, workflow(gateway, worlds, log));
            Resumption resumed;
            try {
                resumed = mediator.resumeDiscussion(answer);
            } catch (IOException | RuntimeException e) {
                log.write("\n=== OrchestratorAgent / REPRISE ===\nReprise refusée ou impossible ("
                        + e.getClass().getSimpleName() + ")\n");
                throw e;
            }
            String scope = switch (resumed.storageResult()) {
                case NOT_STORED -> "Réponse utilisée pour cette exécution";
                case STORED_K_COMMUN -> "Connaissance enregistrée dans K-Commun";
                case STORED_K_PERSO -> "Connaissance enregistrée dans K-Particulier (" + resumed.agentId() + ")";
            };
            log.write("\n=== OrchestratorAgent / REPRISE ===\nMémoire    " + resumed.storageResult() + " · " + scope + "\n");
            var analysis = context.analysis();
            if (context.plan() == null && !context.isMemoryCorrection()) {
                return resolveAgentCorrections(route(analysis, resumed.userAnswer(), resumed.task(),
                        context.correctionPlan(), factory, mediator, log), mediator, log);
            }
            sessionKnowledge.computeIfAbsent(context.route().selectedAgentId(), ignored -> new HashMap<>())
                    .putAll(resumed.knowledge());
            var correction = context.isMemoryCorrection() ? null : context.correctionPlan();
            return resolveAgentCorrections(writeDraft(analysis, context.route(), resumed.task(), correction,
                    factory, mediator, log), mediator, log);
        }
    }

    synchronized AgentResult<DraftResponse> correct(String feedback, LlmGateway gateway, Path directory,
                                       Consumer<Progress> onProgress) throws IOException {
        useSession(directory);
        var worlds = new FileWorldStore(directory, json);
        try (var output = Files.newBufferedWriter(directory.resolve(LOG_NAME), UTF_8, APPEND)) {
            var executionId = worlds.executionTrace().load().orElseThrow().executionId();
            var log = new RunLog(output, executionId, onProgress);
            var mediator = mediator(worlds, workflow(gateway, worlds, log));
            return resolveAgentCorrections(mediator.requestCorrection(executionId.toString(), "USER", feedback),
                    mediator, log);
        }
    }

    private void useSession(Path directory) {
        Path current = directory.toAbsolutePath().normalize();
        if (!current.equals(sessionDirectory)) {
            sessionKnowledge.clear();
            sessionDirectory = current;
        }
    }

    private CorrectionWorkflow workflow(LlmGateway gateway, FileWorldStore worlds, RunLog log) {
        return new CorrectionWorkflow() {
            @Override
            public AgentResult<AgentMessage<CorrectionPlan>> diagnose(OrchestratorAgent mediator,
                    CorrectingInput input, TaskContext task) throws IOException {
                var correcting = factory(gateway, worlds)
                        .createCorrecting(config("CorrectingAgent", AgentRole.CORRECTING, "Diagnostic de correction"));
                return log.step(correcting.id(), Contract.CORRECTION_PLAN, mediator, task,
                        attempt -> mediator.execute(correcting, input, attempt, Contract.CORRECTION_PLAN, value -> {}));
            }

            @Override
            public AgentResult<DraftResponse> restart(OrchestratorAgent mediator, ErrorPlan.Stage stage,
                    ExecutionTrace trace, CorrectionPlan correction) throws IOException {
                var factory = factory(gateway, worlds);
                return switch (stage) {
                    case PARSER -> execute(trace.email(), correction, factory, mediator, log);
                    case DECISION -> route(trace.emailAnalysis(), null, null, correction, factory, mediator, log);
                    case WRITER -> writeDraft(trace.emailAnalysis(), trace.routeDecision(), null, correction,
                            factory, mediator, log);
                    case SCRIBE -> scribe(new ScribeInput(trace.responsePlan(), correction, trace.draftResponse()),
                            factory, mediator, log);
                    default -> throw new IllegalArgumentException("Invalid correction restart stage");
                };
            }
        };
    }

    private AgentResult<DraftResponse> resolveAgentCorrections(AgentResult<DraftResponse> result,
            OrchestratorAgent mediator, RunLog log) throws IOException {
        while (result instanceof CorrectionRequest<DraftResponse> request) {
            result = mediator.requestCorrection(mediator.executionId().toString(),
                    mediator.state().orElseThrow().activeAgentId(), request.feedbackText());
        }
        if (result instanceof ErrorPlan<DraftResponse> error
                && error.code() == fr.mas.email.result.ErrorCode.MAX_CORRECTIONS_REACHED) {
            log.write("\n=== OrchestratorAgent / CORRECTION ===\n" + json.writePretty(error) + "\n");
        }
        return result;
    }

    private AgentResult<DraftResponse> execute(String email, CorrectionPlan correction, AgentFactory factory, OrchestratorAgent mediator,
                                               RunLog log) throws IOException {
        String emailId = incomingMail == null ? "email-" + log.correlationId : incomingMail.emailId();
        String threadId = threadHistory == null ? "thread-" + log.correlationId : threadHistory.threadId();
        String current = incomingMail == null ? ReceivedMail.stripQuotes(email) : incomingMail.cleanedParserText();
        if (current.isBlank()) {
            return fr.mas.email.result.Errors.create(fr.mas.email.result.ErrorCode.EMPTY_RESULT, "ParserAgent",
                    new TaskContext(log.correlationId, UUID.randomUUID(), UUID.randomUUID()));
        }
        var parser = factory.createParser(config("ParserAgent", AgentRole.PARSER, "Extraction du message"));
        var parsed = log.step(parser.id(), Contract.EMAIL_ANALYSIS, mediator,
                task -> mediator.execute(parser, new ParserInput(emailId, threadId, current, correction, previousTurns()),
                        task, Contract.EMAIL_ANALYSIS, value -> Checks.rule(emailId.equals(value.emailId()))));
        if (!(parsed instanceof Success(var analysisMessage, var rawIgnored0))) return stopped(parsed);

        return route(analysisMessage.payload(), null, null, null, factory, mediator, log);
    }

    private AgentResult<DraftResponse> route(EmailAnalysis analysis, String hint, TaskContext attempt,
            CorrectionPlan correction, AgentFactory factory, OrchestratorAgent mediator, RunLog log) throws IOException {
        var decision = factory.createDecision(config("DecisionAgent", AgentRole.DECISION, "Routage"));
        var candidates = DecisionInput.forAuthorizedWriters(analysis, AUTHORIZED_WRITERS).availableWriters();
        var decisionInput = new DecisionInput(analysis, candidates, hint, correction, previousTurns());
        var routed = log.step(decision.id(), Contract.ROUTE_DECISION, mediator, attempt,
                task -> mediator.execute(decision, decisionInput, task, Contract.ROUTE_DECISION,
                        new RouteDecisionValidator(analysis.emailId(), candidates)));
        if (!(routed instanceof Success(var routeMessage, var rawIgnored1))) return stopped(routed);

        return writeDraft(analysis, routeMessage.payload(), null, null, factory, mediator, log);
    }

    private AgentResult<DraftResponse> writeDraft(EmailAnalysis analysis, RouteDecision route,
            TaskContext attempt, CorrectionPlan correction, AgentFactory factory, OrchestratorAgent mediator,
            RunLog log) throws IOException {
        var writer = factory.createWriter(WriterProfiles.get(route.selectedAgentId()).toAgentConfig());
        var knowledge = mediator.writerKnowledge(writer.id());
        var sessionValues = sessionKnowledge.get(writer.id());
        if (sessionValues != null) knowledge = knowledge.withSession(sessionValues);
        var input = new WriterInput(analysis).withKnowledge(knowledge).withCorrection(correction).withThreadHistory(previousTurns());
        // The built-in Writer checks references against its scoped MiniWorld. Semantic review
        // remains manual in the test bench.
        var planValidator = new ResponsePlanValidator(fact -> hasDeclaredOrigin(fact.sourceRef(), analysis));
        var planned = log.step(writer.id(), Contract.RESPONSE_PLAN, mediator, attempt,
                task -> mediator.executeWriter(route, writer, input, task, planValidator));
        if (!(planned instanceof Success(var planMessage, var rawIgnored2))) return stopped(planned);

        return scribe(new ScribeInput(planMessage.payload()), factory, mediator, log);
    }

    private AgentResult<DraftResponse> scribe(ScribeInput input, AgentFactory factory,
                                              OrchestratorAgent mediator, RunLog log) throws IOException {
        var scribe = factory.createScribe(config("ScribeAgent", AgentRole.SCRIBE, "Rédaction du brouillon"));
        var withHistory = new ScribeInput(input.responsePlan(), input.correctionPlan(), input.previousDraft(), previousTurns());
        var result = log.step(scribe.id(), Contract.DRAFT_RESPONSE, mediator,
                task -> mediator.execute(scribe, withHistory, task, Contract.DRAFT_RESPONSE,
                        draft -> DraftResponseValidator.validateStructure(input.responsePlan(), draft)))
                .map(AgentMessage::payload);
        if (threadHistory != null && result instanceof Success<?>) {
            try {
                var trace = new FileWorldStore(sessionDirectory, json).executionTrace().load().orElseThrow();
                conversations().recordDraftTurn(threadHistory.threadId(), incomingMail, trace);
            } catch (IOException | RuntimeException e) {
                log.write("Historique du fil indisponible : brouillon conservé dans cette exécution.\n");
            }
        }
        return result;
    }

    private static AgentConfig config(String id, AgentRole role, String specialization) {
        return new AgentConfig(id, role, specialization, List.of(), List.of());
    }

    private static boolean hasDeclaredOrigin(String reference, EmailAnalysis analysis) {
        return switch (reference) {
            case "commonKnowledge", "domainKnowledge" -> true;
            case "emailAnalysis.senderClaims" -> !analysis.senderClaims().isEmpty();
            case "emailAnalysis.requestedActions" -> !analysis.requestedActions().isEmpty();
            case "emailAnalysis.requestedInformation" -> !analysis.requestedInformation().isEmpty();
            default -> false;
        };
    }

    private static AgentResult<DraftResponse> stopped(AgentResult<?> result) {
        return switch (result) {
            case DiscussionPlan<?> discussion -> discussion.retype();
            case ErrorPlan<?> error -> error.retype();
            case CorrectionRequest<?> correction -> correction.retype();
            case Success<?> ignored -> throw new IllegalStateException("Expected a stopped stage");
        };
    }

    private static String now() {
        return ZonedDateTime.now().format(TIMESTAMP);
    }

    private final class RunLog {
        private final BufferedWriter output;
        private final UUID correlationId;
        private final Consumer<Progress> onProgress;

        RunLog(BufferedWriter output, UUID correlationId, Consumer<Progress> onProgress) {
            this.output = output;
            this.correlationId = correlationId;
            this.onProgress = onProgress;
        }

        <T> AgentResult<AgentMessage<T>> step(String agent, Contract<T> contract, OrchestratorAgent mediator,
                                             Function<TaskContext, AgentResult<AgentMessage<T>>> action) throws IOException {
            return step(agent, contract, mediator, null, action);
        }

        <T> AgentResult<AgentMessage<T>> step(String agent, Contract<T> contract, OrchestratorAgent mediator,
                TaskContext attempt, Function<TaskContext, AgentResult<AgentMessage<T>>> action) throws IOException {
            var task = attempt == null ? new TaskContext(correlationId, UUID.randomUUID(), UUID.randomUUID()) : attempt;
            var stage = contract.stage();
            write("\n=== " + agent + " / " + stage + " ===\n"
                    + "Début      " + now() + "\n"
                    + "Tâche      " + task.taskId() + "\nTentative  " + task.attemptId() + "\n");
            onProgress.accept(new Progress(stage, agent, Status.RUNNING));
            long startedAt = System.nanoTime();
            var result = action.apply(task);
            var duration = Duration.ofNanos(System.nanoTime() - startedAt);
            var status = switch (result) {
                case Success<?> ignored -> Status.COMPLETE;
                case DiscussionPlan<?> ignored -> Status.WAITING_USER;
                case ErrorPlan<?> ignored -> Status.FAILED;
                case CorrectionRequest<?> ignored -> Status.COMPLETE;
            };
            write("Fin        " + now() + "\n"
                    + "Durée      " + duration.toMillis() + " ms\n"
                    + "Résultat   " + status + "\n");
            switch (result) {
                case Success(var message, var rawIgnored3) -> write("Sortie de l’agent\n" + json.writePretty(message) + "\n");
                case DiscussionPlan<?> discussion -> {
                    var initial = mediator.suspendedDiscussion().orElseThrow().initialResultJson();
                    write("Sortie de l’agent\n" + json.writePretty(json.read(initial, JsonNode.class)) + "\n"
                            + "\nClarification\n" + json.writePretty(discussion) + "\n");
                }
                case ErrorPlan<?> error -> write("Erreur de l’étape\n" + json.writePretty(error) + "\n");
                case CorrectionRequest<?> correction -> write("Correction demandée\n" + correction.feedbackText() + "\n");
            }
            onProgress.accept(new Progress(stage, agent, status));
            return result;
        }

        void write(String text) throws IOException {
            output.write(text);
            output.flush();
        }
    }
}
