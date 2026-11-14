package fr.mas.email.app;

import com.fasterxml.jackson.databind.JsonNode;
import fr.mas.email.agent.CorrectingInput;
import fr.mas.email.contract.*;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.*;
import fr.mas.email.world.FileWorldStore;
import fr.mas.email.world.MiniWorldService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static fr.mas.email.contract.CorrectionPlan.Target.*;
import static org.junit.jupiter.api.Assertions.*;

class CorrectingAgentTest {
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();
    private static final String KEY = "date_publication_notes";
    private static final String NEW_DATE = "Les notes d’INF312 seront publiées le 25 novembre.";

    @ParameterizedTest
    @EnumSource(value = CorrectionPlan.Target.class, names = "ORCHESTRATOR", mode = EnumSource.Mode.EXCLUDE)
    void restartsAtTheTargetAndSendsTheInstructionOnlyThere(CorrectionPlan.Target target) throws Exception {
        var runner = started();
        var plan = correction(target);
        var gateway = new RecordingGateway().enqueue(plan);
        if (target == PARSER_AGENT) gateway.enqueue(new EmailAnalysis.Content("fr", "REQUEST_INFORMATION",
                List.of("EVALUATION"), List.of(new EmailAnalysis.Entity("COURSE", "INF312")), List.of(KEY),
                List.of(), List.of(), List.of(), List.of()));
        if (target == PARSER_AGENT || target == DECISION_AGENT) gateway.enqueue(new RouteDecision.Content(
                "AcademicWriter", List.of("AcademicWriter"), RouteDecision.DecisionStatus.SELECTED,
                List.of("Cours INF312"), null, false, null));
        if (target != SCRIBE_AGENT) gateway.enqueue(replyPlan());
        gateway.enqueue(new DraftContent("Re: INF312", "Salut,\n\n" + NEW_DATE));

        var initial = new FileWorldStore(directory, json).executionTrace().load().orElseThrow();
        var revised = assertInstanceOf(DraftResponse.class, assertInstanceOf(Success.class,
                runner.correct(plan.instruction(), gateway, directory, progress -> {})).value());
        assertTrue(revised.body().startsWith("Salut,"));
        assertEquals(initial.draftResponse().emailId(), revised.emailId());
        if (target == SCRIBE_AGENT) assertEquals(initial.responsePlan().planId(), revised.planId());
        else assertNotEquals(initial.responsePlan().planId(), revised.planId());
        assertEquals(switch (target) {
            case SCRIBE_AGENT -> List.of("CorrectingAgent", "ScribeAgent");
            case WRITER_AGENT -> List.of("CorrectingAgent", "AcademicWriter", "ScribeAgent");
            case DECISION_AGENT -> List.of("CorrectingAgent", "DecisionAgent", "AcademicWriter", "ScribeAgent");
            case PARSER_AGENT -> List.of("CorrectingAgent", "ParserAgent", "DecisionAgent", "AcademicWriter", "ScribeAgent");
            case ORCHESTRATOR -> throw new AssertionError();
        }, gateway.agents);
        var diagnosis = gateway.requests.getFirst();
        assertEquals(0.0, diagnosis.temperature());
        assertEquals(Contract.CORRECTION_PLAN, diagnosis.expectedContract());
        assertTrue(diagnosis.instructions().contains("CorrectingAgent"));
        var feedback = json.read(diagnosis.input(), CorrectingInput.class);
        assertEquals("USER", feedback.feedbackSource());
        assertEquals(plan.instruction(), feedback.feedbackText());
        assertEquals(initial.draftResponse(), feedback.draftResponse());
        var correctionInput = json.read(gateway.requests.get(1).input(), JsonNode.class);
        assertEquals(plan, json.read(correctionInput.path("correctionPlan").toString(), CorrectionPlan.class));
        if (target == SCRIBE_AGENT) {
            assertEquals(initial.draftResponse(), json.read(correctionInput.path("previousDraft").toString(),
                    DraftResponse.class));
        }
        for (var request : gateway.requests.subList(2, gateway.requests.size())) {
            assertFalse(request.input().contains("\"correctionPlan\""));
        }
        assertTrue(Files.readString(directory.resolve(PipelineRunner.LOG_NAME)).contains(target.name()));
    }

    @ParameterizedTest
    @EnumSource(UserDiscussionAnswer.MemoryChoice.class)
    void pausesBeforeAnyWriteAndReplansUsingTheUserApprovedValue(UserDiscussionAnswer.MemoryChoice choice) throws Exception {
        var runner = started();
        var plan = new CorrectionPlan(ORCHESTRATOR, CorrectionPlan.Type.UPDATE_KNOWLEDGE, true, "Valider la date",
                List.of(new KnowledgeProposal(KnowledgeProposal.Scope.AGENT_SPECIFIC, KEY, NEW_DATE)), "Date corrigée");
        var gateway = new RecordingGateway().enqueue(plan);
        assertInstanceOf(DiscussionPlan.class, runner.correct(NEW_DATE, gateway, directory, progress -> {}));
        assertEquals(List.of("CorrectingAgent"), gateway.agents);
        var pending = runner.pending(directory);
        assertEquals(plan, pending.context().correctionPlan());
        var storage = new MiniWorldService(new FileWorldStore(directory.resolve("memory"), json));
        assertTrue(storage.readKnowledge("AcademicWriter").common().isEmpty());
        assertTrue(storage.readKnowledge("AcademicWriter").particular().isEmpty());
        var request = pending.request().metadata().messageId();
        String writer = choice == UserDiscussionAnswer.MemoryChoice.K_PERSO ? "AcademicWriter" : null;
        if (choice != UserDiscussionAnswer.MemoryChoice.NONE) {
            var unconfirmed = new UserDiscussionAnswer(request, UserDiscussionAnswer.Action.ANSWER_INFORMATION,
                    NEW_DATE, choice, writer, false);
            assertThrows(SecurityException.class, () -> runner.resume(unconfirmed, gateway, directory, progress -> {}));
            assertEquals(request, runner.pending(directory).request().metadata().messageId());
        }
        String approved = NEW_DATE.replace("25", "26");
        var confirmation = new UserDiscussionAnswer(request, UserDiscussionAnswer.Action.ANSWER_INFORMATION,
                approved, choice, writer, choice != UserDiscussionAnswer.MemoryChoice.NONE);
        var resumed = new RecordingGateway().enqueue(replyPlan(approved)).enqueue(new DraftContent("Re: INF312", approved));
        assertInstanceOf(Success.class, runner.resume(confirmation, resumed, directory, progress -> {}));
        assertEquals(List.of("AcademicWriter", "ScribeAgent"), resumed.agents);
        assertEquals(approved, json.read(resumed.requests.getFirst().input(), JsonNode.class)
                .at("/miniWorlds/domainKnowledge/informationLookup/" + KEY).asText());
        var knowledge = storage.readKnowledge("AcademicWriter");
        assertEquals(choice == UserDiscussionAnswer.MemoryChoice.K_COMMUN ? approved : null, knowledge.common().get(KEY));
        assertEquals(choice == UserDiscussionAnswer.MemoryChoice.K_PERSO ? approved : null, knowledge.particular().get(KEY));
        assertEquals(1, new FileWorldStore(directory, json).executionTrace().load().orElseThrow().corrections());
    }

    @Test
    void stopsAfterThreeCorrectionsEvenWhenTheControllerIsRecreated() throws Exception {
        var runner = started();
        for (int i = 0; i < 3; i++) {
            var gateway = new RecordingGateway().enqueue(correction(SCRIBE_AGENT))
                    .enqueue(new DraftContent("Re: INF312", "Version " + i));
            assertInstanceOf(Success.class, runner.correct("Tutoie-le", gateway, directory, progress -> {}));
        }
        var gateway = new RecordingGateway();
        var restarted = new PipelineRunner(json, directory.resolve("memory"));
        var error = assertInstanceOf(ErrorPlan.class,
                restarted.correct("Encore une correction", gateway, directory, progress -> {}));
        assertEquals(ErrorCode.MAX_CORRECTIONS_REACHED, error.code());
        assertTrue(gateway.agents.isEmpty());
    }

    @Test
    void aBlockingAgentRequestCarriesTheAgentIdentityAndReusesTheSameBudget() throws Exception {
        var runner = started();
        var gateway = new RecordingGateway().enqueue(correction(SCRIBE_AGENT))
                .enqueue(new CorrectionRequest<DraftContent>("Le plan omet la date demandée."))
                .enqueue(correction(WRITER_AGENT)).enqueue(replyPlan())
                .enqueue(new DraftContent("Re: INF312", NEW_DATE));
        assertInstanceOf(Success.class, runner.correct("Tutoie-le", gateway, directory, progress -> {}));
        var escalated = json.read(gateway.requests.get(2).input(), CorrectingInput.class);
        assertEquals("ScribeAgent", escalated.feedbackSource());
        assertEquals("Le plan omet la date demandée.", escalated.feedbackText());
        assertEquals(List.of("CorrectingAgent", "ScribeAgent", "CorrectingAgent", "AcademicWriter", "ScribeAgent"),
                gateway.agents);
        assertEquals(2, new FileWorldStore(directory, json).executionTrace().load().orElseThrow().corrections());
    }

    @Test
    void reportedMemoryOutputReachesTheUserBeforeAnyKnowledgeWrite() throws Exception {
        var runner = started();
        var gateway = new FakeLlmGateway(json).enqueueJson(fr.mas.email.GeneratedCorrectionTest.REPORTED_OUTPUT);
        assertInstanceOf(DiscussionPlan.class,
                runner.correct("Les notes seront publiées le 12 décembre.", gateway, directory, progress -> {}));
        assertEquals(1, gateway.callCount());
        var pending = runner.pending(directory);
        assertEquals(List.of("date_publication_notes_inf312"), pending.context().keys());
        assertEquals("Les notes du cours INF312 seront publiées le 12 décembre.",
                pending.context().correctionPlan().memoryProposals().getFirst().value());
        var storage = new MiniWorldService(new FileWorldStore(directory.resolve("memory"), json));
        assertTrue(storage.readKnowledge("AcademicWriter").particular().isEmpty());
        assertTrue(storage.readKnowledge("AcademicWriter").common().isEmpty());
    }

    @Test
    void malformedCorrectionKeepsDraftReviewAndRequiresAnExplicitRetry() throws Exception {
        var runner = started();
        var original = runner.review(directory).draft();
        var failure = new FakeLlmGateway(json).enqueueJson("{");
        assertInstanceOf(ErrorPlan.class, runner.correct("Nouvelle date", failure, directory, progress -> {}));
        assertEquals(1, failure.callCount());
        assertEquals(original, runner.review(directory).draft());
        assertTrue(runner.review(directory).canApprove());
        assertTrue(runner.review(directory).canCorrect());
        var retry = new RecordingGateway().enqueue(correction(SCRIBE_AGENT))
                .enqueue(new DraftContent("Re: INF312", "Salut, voici la réponse corrigée."));
        assertInstanceOf(Success.class, runner.correct("Tutoie-le", retry, directory, progress -> {}));
        assertEquals(List.of("CorrectingAgent", "ScribeAgent"), retry.agents);
        assertEquals(2, new FileWorldStore(directory, json).executionTrace().load().orElseThrow().corrections());
    }

    private PipelineRunner started() throws Exception {
        var runner = new PipelineRunner(json, directory.resolve("memory"));
        assertInstanceOf(Success.class, runner.run(DemoScenario.EMAIL, DemoScenario.gateway(json), "simulation",
                directory, progress -> {}));
        return runner;
    }

    private static CorrectionPlan correction(CorrectionPlan.Target target) {
        return new CorrectionPlan(target, switch (target) {
            case SCRIBE_AGENT -> CorrectionPlan.Type.ADJUST_STYLE;
            case WRITER_AGENT -> CorrectionPlan.Type.REVISE_PLAN;
            case DECISION_AGENT -> CorrectionPlan.Type.REROUTE_DOMAIN;
            case PARSER_AGENT -> CorrectionPlan.Type.REPARSE_EMAIL;
            case ORCHESTRATOR -> throw new AssertionError();
        }, false, target == SCRIBE_AGENT ? "Tutoie-le" : "Corriger la date mentionnée", List.of(), "Retour utilisateur");
    }

    private static ResponsePlan.Content replyPlan() {
        return replyPlan(NEW_DATE);
    }

    private static ResponsePlan.Content replyPlan(String fact) {
        return new ResponsePlan.Content(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                List.of(new ResponsePlan.Fact(fact, "domainKnowledge")), List.of(), false);
    }

    private final class RecordingGateway implements LlmGateway {
        final List<String> agents = new ArrayList<>();
        final List<LlmRequest<?>> requests = new ArrayList<>();
        private final List<Object> responses = new ArrayList<>();

        RecordingGateway enqueue(Object response) {
            responses.add(response);
            return this;
        }

        @Override public <T> AgentResult<T> generate(LlmRequest<T> request, TaskContext task, String component) {
            agents.add(component);
            requests.add(request);
            var next = responses.removeFirst();
            if (next instanceof CorrectionRequest<?> correction) return correction.retype();
            return new FakeLlmGateway(json).enqueueJson(json.write(next)).generate(request, task, component);
        }
    }
}
