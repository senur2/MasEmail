package fr.mas.email.app;

import com.fasterxml.jackson.databind.JsonNode;
import fr.mas.email.contract.DraftContent;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.ResponsePlan;
import fr.mas.email.contract.RouteDecision;
import fr.mas.email.contract.UserDiscussionAnswer;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.AgentResult;
import fr.mas.email.result.DiscussionPlan;
import fr.mas.email.result.ErrorCode;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.FileWorldStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static fr.mas.email.contract.UserDiscussionAnswer.MemoryChoice.*;
import static org.junit.jupiter.api.Assertions.*;

class OrchestratorDiscussionTest {
    private static final String KEY = "date_publication_notes";
    private static final String VALUE = "Le 19 novembre 2026";
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();

    @ParameterizedTest
    @EnumSource(UserDiscussionAnswer.MemoryChoice.class)
    void resumesWriterWithScopedKnowledgeAndProducesDraft(UserDiscussionAnswer.MemoryChoice choice) throws Exception {
        var memory = directory.resolve("knowledge");
        var run = directory.resolve("run");
        var runner = new PipelineRunner(json, memory);
        var initial = startWriterDiscussion(runner, run);
        var requestId = initial.request().metadata().messageId();
        assertEquals(List.of(KEY), initial.context().keys());
        assertEquals("AcademicWriter", initial.context().route().selectedAgentId());
        assertTrue(initial.context().plan().requiresClarification());
        String target = choice == K_PERSO ? "AcademicWriter" : null;
        if (choice != NONE) {
            var unconfirmed = new UserDiscussionAnswer(requestId, UserDiscussionAnswer.Action.ANSWER_INFORMATION,
                    VALUE, choice, target, false);
            assertThrows(SecurityException.class, () -> runner.resume(unconfirmed, new FakeLlmGateway(json), run, p -> {}));
        }
        if (choice == K_PERSO) {
            var wrong = new UserDiscussionAnswer(requestId, UserDiscussionAnswer.Action.ANSWER_INFORMATION,
                    VALUE, K_PERSO, "FinanceWriter", true);
            assertThrows(SecurityException.class, () -> runner.resume(wrong, new FakeLlmGateway(json), run, p -> {}));
        }
        var answer = new UserDiscussionAnswer(requestId, UserDiscussionAnswer.Action.ANSWER_INFORMATION,
                VALUE, choice, target, choice != NONE);
        // Reproduce a Writer that quotes the answer yet repeats the same question.
        var content = new ResponsePlan.Content(ResponsePlan.Intent.REQUEST_CLARIFICATION,
                List.of(new ResponsePlan.Fact(VALUE, "domainKnowledge.informationLookup." + KEY)), List.of(KEY), true);
        var draft = new DraftContent("Re: Notes", "Bonjour,\n\n" + VALUE);
        var gateway = new FakeLlmGateway(json).enqueueJson(json.write(content)).enqueueJson(json.write(draft));
        var received = new AtomicReference<JsonNode>();
        var result = runner.resume(answer, recording(gateway, received), run, p -> {});
        assertInstanceOf(Success.class, result);
        assertEquals(2, gateway.callCount());
        assertEquals(VALUE, received.get().at("/miniWorlds/domainKnowledge/informationLookup/" + KEY).asText());
        var worlds = new FileWorldStore(memory, json);
        var common = worlds.miniWorld("COMMON_WORLD", false).load();
        var academic = worlds.miniWorld("AcademicWriter", false).load();
        assertEquals(choice == K_COMMUN, common.isPresent());
        assertEquals(choice == K_PERSO, academic.isPresent());
        assertTrue(worlds.miniWorld("FinanceWriter", false).load().isEmpty());
        if (choice == K_COMMUN) assertEquals(VALUE, common.orElseThrow().knowledge().get(KEY));
        if (choice == K_PERSO) assertEquals(VALUE, academic.orElseThrow().knowledge().get(KEY));
        if (choice == NONE) {
            try (var files = Files.list(memory)) { assertEquals(0, files.count()); }
        }
        var log = Files.readString(run.resolve(PipelineRunner.LOG_NAME));
        assertTrue(log.contains("À quelle date les notes seront-elles publiées ?"));
        assertTrue(log.contains(switch (choice) {
            case NONE -> "NOT_STORED";
            case K_COMMUN -> "STORED_K_COMMUN";
            case K_PERSO -> "STORED_K_PERSO";
        }));
        assertTrue(log.contains("ScribeAgent / SCRIBE"));
        assertTrue(log.contains("\"responseIntent\" : \"ANSWER_INFORMATION_REQUEST\""));
        assertTrue(log.contains(draft.subject()));
        assertThrows(java.util.NoSuchElementException.class, () -> runner.resume(answer, gateway, run, p -> {}));
    }

    @Test
    void rejectsIncompleteKeyedAnswerBeforeWritingAnything() throws Exception {
        var memory = directory.resolve("knowledge");
        var run = directory.resolve("run");
        var runner = new PipelineRunner(json, memory);
        var plan = new ResponsePlan.Content(ResponsePlan.Intent.REQUEST_CLARIFICATION, List.of(),
                List.of(KEY, "date_publication_corriges"), true);
        runner.run("Bonjour", analysisGateway().enqueueJson(json.write(selected())).enqueueJson(json.write(plan)),
                "test", run, p -> {});
        var pending = runner.pending(run);
        var answer = new UserDiscussionAnswer(pending.request().metadata().messageId(),
                UserDiscussionAnswer.Action.ANSWER_INFORMATION, json.write(java.util.Map.of(KEY, VALUE)),
                K_COMMUN, null, true);
        assertThrows(IllegalArgumentException.class,
                () -> runner.resume(answer, new FakeLlmGateway(json), run, p -> {}));
        try (var files = Files.list(memory)) { assertEquals(0, files.count()); }
        assertEquals(pending.request().metadata().messageId(), runner.pending(run).request().metadata().messageId());
    }

    @Test
    void bowlingTopicDoesNotJustifyFactsInAnUnansweredSingleQuestionPlan() throws Exception {
        var key = "hebergement_apres_bowling";
        var analysis = new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of("BOWLING"),
                List.of(), List.of(key), List.of(), List.of(), List.of(), List.of());
        var route = new RouteDecision.Content("PersonalWriter", List.of("PersonalWriter"),
                RouteDecision.DecisionStatus.SELECTED, List.of("Sortie personnelle"), null, false, null);
        var plan = new ResponsePlan.Content(ResponsePlan.Intent.REQUEST_CLARIFICATION,
                List.of(new ResponsePlan.Fact("La sortie bowling est confirmée.", "domainKnowledge")), List.of(key), true);
        String raw = json.write(plan);
        var gateway = new FakeLlmGateway(json).enqueueJson(json.write(analysis)).enqueueJson(json.write(route)).enqueueJson(raw);
        var result = new PipelineRunner(json).run("Puis-je dormir chez toi après le bowling ?", gateway,
                "test", directory.resolve("run"), p -> {});
        var error = assertInstanceOf(ErrorPlan.class, result);
        assertEquals(ErrorCode.INVALID_OUTPUT, error.code());
        assertTrue(error.observedProblem().contains("single requested question"));
        assertTrue(error.observedProblem().endsWith(raw));
        assertEquals(3, gateway.callCount());
    }

    @Test
    void rejectsAReadyPlanWithFewerFactsThanQuestions() throws Exception {
        var analysis = new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of("NOTES"),
                List.of(new EmailAnalysis.Entity("COURSE", "INF312")), List.of(KEY, "date_publication_corriges"),
                List.of(), List.of(), List.of(), List.of());
        var plan = new ResponsePlan.Content(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                List.of(new ResponsePlan.Fact(VALUE, "domainKnowledge")), List.of(), false);
        var gateway = new FakeLlmGateway(json).enqueueJson(json.write(analysis))
                .enqueueJson(json.write(selected())).enqueueJson(json.write(plan));
        var result = new PipelineRunner(json).run("Bonjour", gateway, "test", directory.resolve("run"), p -> {});
        var error = assertInstanceOf(ErrorPlan.class, result);
        assertEquals(ErrorCode.INVALID_OUTPUT, error.code());
        assertTrue(error.observedProblem().contains("fewer facts (1) than requestedInformation (2)"));
        assertEquals(3, gateway.callCount());
    }

    @Test
    void resumesDecisionWithHintAndPreservesTheAuthorizedRegistry() throws Exception {
        var runner = new PipelineRunner(json);
        var run = directory.resolve("run");
        var decision = new RouteDecision.Content(null, List.of("AcademicWriter", "ResearchWriter"),
                RouteDecision.DecisionStatus.NEEDS_CLARIFICATION, List.of("Domaine ambigu"), null, true,
                "Le message concerne-t-il un cours ou un projet de recherche ?");
        var initial = runner.run("Bonjour", analysisGateway().enqueueJson(json.write(decision)), "test", run, p -> {});
        assertEquals(List.of(decision.questionToUser()), assertInstanceOf(DiscussionPlan.class, initial).questions());
        var pending = runner.pending(run);
        var answer = UserDiscussionAnswer.withoutStorage(pending.request().metadata().messageId(), "Un cours");
        var content = new ResponsePlan.Content(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                List.of(new ResponsePlan.Fact(VALUE, "domainKnowledge")), List.of(), false);
        var gateway = new FakeLlmGateway(json).enqueueJson(json.write(selected())).enqueueJson(json.write(content))
                .enqueueJson(json.write(new DraftContent("Re: Cours", "Bonjour, merci pour votre message.")));
        var received = new AtomicReference<JsonNode>();
        assertInstanceOf(Success.class, runner.resume(answer, recording(gateway, received), run, p -> {}));
        assertEquals("Un cours", received.get().path("routingHint").asText());
        assertEquals(5, received.get().path("availableWriters").size());
        assertEquals(3, gateway.callCount());
    }

    private fr.mas.email.orchestration.SuspendedDiscussion startWriterDiscussion(PipelineRunner runner, Path run)
            throws Exception {
        var content = new ResponsePlan.Content(ResponsePlan.Intent.REQUEST_CLARIFICATION, List.of(), List.of(KEY), true);
        var gateway = analysisGateway().enqueueJson(json.write(selected())).enqueueJson(json.write(content));
        var result = runner.run("Bonjour", gateway, "test", run, p -> {});
        assertInstanceOf(DiscussionPlan.class, result);
        assertEquals(3, gateway.callCount());
        return runner.pending(run);
    }

    @ParameterizedTest
    @EnumSource(UserDiscussionAnswer.MemoryChoice.class)
    void unknownCoursePausesBeforeWriterLlmAndResumesWithTheActualAnswer(UserDiscussionAnswer.MemoryChoice choice)
            throws Exception {
        String key = "date_publication_notes_uemf21";
        var analysis = new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of("NOTES", "COURSE"),
                List.of(new EmailAnalysis.Entity("COURSE", "UEMF21")), List.of(key), List.of(), List.of(),
                List.of(), List.of());
        var gateway = new FakeLlmGateway(json).enqueueJson(json.write(analysis)).enqueueJson(json.write(selected()));
        var memory = directory.resolve("knowledge");
        var runner = new PipelineRunner(json, memory);
        var run = directory.resolve("uemf");
        var result = runner.run("À quelle date les notes du cours UEMF21 seront-elles publiées ?", gateway,
                "test", run, p -> {});
        assertInstanceOf(DiscussionPlan.class, result);
        assertEquals(2, gateway.callCount());
        var pending = runner.pending(run);
        assertEquals(List.of(key), pending.context().keys());
        assertTrue(pending.context().plan().factsToCommunicate().isEmpty());
        String initialLog = Files.readString(run.resolve(PipelineRunner.LOG_NAME));
        assertTrue(initialLog.contains("WAITING_USER"));
        assertFalse(initialLog.contains("ScribeAgent / SCRIBE"));
        assertFalse(initialLog.contains("2026-11-18"));
        assertFalse(initialLog.contains("20 novembre"));

        String answer = "Les notes du cours UEMF21 seront publiées le 25 novembre 2026.";
        var userAnswer = new UserDiscussionAnswer(pending.request().metadata().messageId(),
                UserDiscussionAnswer.Action.ANSWER_INFORMATION, answer, choice,
                choice == K_PERSO ? "AcademicWriter" : null, choice != NONE);
        var draft = new DraftContent("Re: Notes du cours UEMF21", "Bonjour,\n\n" + answer + "\n\nCordialement,");
        var scribeGateway = new FakeLlmGateway(json).enqueueJson(json.write(draft));
        var received = new AtomicReference<JsonNode>();
        assertInstanceOf(Success.class, runner.resume(userAnswer, recording(scribeGateway, received), run, p -> {}));
        assertEquals(1, scribeGateway.callCount());
        assertEquals("ANSWER_INFORMATION_REQUEST", received.get().path("responseIntent").asText());
        assertTrue(received.get().path("missingInformation").isEmpty());
        assertEquals(key.replace('_', ' ') + " : " + answer,
                received.get().at("/factsToCommunicate/0/text").asText());
        String resumedLog = Files.readString(run.resolve(PipelineRunner.LOG_NAME));
        assertTrue(resumedLog.contains("\"schemaVersion\" : \"1.0\""));
        assertTrue(resumedLog.contains("ScribeAgent / SCRIBE"));
        assertTrue(resumedLog.contains(answer));
        var worlds = new FileWorldStore(memory, json);
        assertEquals(choice == K_COMMUN, worlds.miniWorld("COMMON_WORLD", false).load().isPresent());
        assertEquals(choice == K_PERSO, worlds.miniWorld("AcademicWriter", false).load().isPresent());
    }

    private FakeLlmGateway analysisGateway() {
        return new FakeLlmGateway(json).enqueueJson(json.write(new EmailAnalysis.Content("fr",
                "REQUEST_INFORMATION", List.of("NOTES"), List.of(new EmailAnalysis.Entity("COURSE", "INF312")),
                List.of(KEY), List.of(),
                List.of(), List.of(), List.of())));
    }

    private static RouteDecision.Content selected() {
        return new RouteDecision.Content("AcademicWriter", List.of("AcademicWriter"),
                RouteDecision.DecisionStatus.SELECTED, List.of("Cours"), null, false, null);
    }

    private LlmGateway recording(FakeLlmGateway delegate, AtomicReference<JsonNode> received) {
        return new LlmGateway() {
            @Override public <T> AgentResult<T> generate(LlmRequest<T> request, TaskContext task, String component) {
                received.compareAndSet(null, json.read(request.input(), JsonNode.class));
                return delegate.generate(request, task, component);
            }
        };
    }
}
