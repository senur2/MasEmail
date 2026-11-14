package fr.mas.email;

import com.fasterxml.jackson.databind.JsonNode;
import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.WriterInput;
import fr.mas.email.agent.WriterProfiles;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.ResponsePlan;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.AgentResult;
import fr.mas.email.result.ErrorCode;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.FileWorldStore;
import fr.mas.email.world.MiniWorldService.Knowledge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WriterScopeTest {
    private static final String KEY = "date_publication_notes_uemf21";
    private static final String ANSWER = "Le 25 novembre 2026";
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();
    private final FakeLlmGateway gateway = new FakeLlmGateway(json);

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"UEMF21", "INF313", "INF3120"})
    void unrelatedOrUnidentifiedCourseCannotUseInf312DatesOrThePromptExample(String course) throws IOException {
        // Even a model willing to return the hallucinated date must not be called.
        gateway.enqueueJson(json.write(ready("Les notes du cours UEMF21 seront publiées le 20 novembre 2026.")));
        var plan = success(execute(new WriterInput(analysis(course, List.of(KEY))), gateway));
        assertTrue(plan.requiresClarification());
        assertEquals(ResponsePlan.Intent.REQUEST_CLARIFICATION, plan.responseIntent());
        assertEquals(List.of(KEY), plan.missingInformation());
        assertEquals(List.of(), plan.factsToCommunicate());
        assertEquals(0, gateway.callCount());
    }

    @Test
    void genericQuestionKeyDoesNotOverrideAnExplicitCourseMismatch() throws IOException {
        var plan = success(execute(new WriterInput(analysis("UEMF21", List.of("date_publication_notes"))), gateway));
        assertEquals(List.of("date_publication_notes"), plan.missingInformation());
        assertTrue(plan.factsToCommunicate().isEmpty());
        assertEquals(0, gateway.callCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INF312", " inf312 ", "Intelligence artificielle"})
    void exactCourseOrDeclaredAliasKeepsTheUsualLlmPath(String course) throws IOException {
        gateway.enqueueJson(json.write(ready("Les notes d'INF312 seront publiées le 18 novembre 2026.")));
        var plan = success(execute(new WriterInput(analysis(course, List.of("date_publication_notes"))), gateway));
        assertFalse(plan.requiresClarification());
        assertEquals(1, gateway.callCount());
    }

    @Test
    void matchingOneOfSeveralCoursesDoesNotAuthorizeTheOtherCourse() throws IOException {
        var analysis = new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of("NOTES"),
                List.of(new EmailAnalysis.Entity("COURSE", "INF312"), new EmailAnalysis.Entity("COURSE", "UEMF21")),
                List.of("date_publication_notes_inf312", KEY), List.of(), List.of(), List.of(), List.of())
                .enrich("email-1", "thread-1");
        var plan = success(execute(new WriterInput(analysis), gateway));
        assertEquals(analysis.requestedInformation(), plan.missingInformation());
        assertTrue(plan.factsToCommunicate().isEmpty());
        assertEquals(0, gateway.callCount());
    }

    @Test
    void unrelatedConfirmedKeyCannotAnswerAnotherCourse() throws IOException {
        var input = new WriterInput(analysis("UEMF21", List.of(KEY)))
                .withKnowledge(new Knowledge(Map.of(), Map.of("date_publication_notes_inf312", ANSWER)));
        var plan = success(execute(input, gateway));
        assertEquals(List.of(KEY), plan.missingInformation());
        assertTrue(plan.factsToCommunicate().isEmpty());
    }

    @Test
    void exactConfirmedAnswerAllowsUnknownCourseAndPreservesParticularPrecedence() throws IOException {
        var input = new WriterInput(analysis("UEMF21", List.of(KEY)))
                .withKnowledge(new Knowledge(Map.of(KEY, "Ancienne réponse"), Map.of(KEY, ANSWER)));
        var plan = success(execute(input, gateway));
        assertFalse(plan.requiresClarification());
        assertEquals(List.of(), plan.missingInformation());
        assertEquals(List.of(new ResponsePlan.Fact("date publication notes uemf21 : " + ANSWER,
                "domainKnowledge")), plan.factsToCommunicate());
        assertEquals(0, gateway.callCount());
    }

    @Test
    void partialConfirmedCommonKnowledgeKeepsOnlyTheAnswerAndAsksForTheRest() throws IOException {
        String other = "date_publication_corriges_uemf21";
        var input = new WriterInput(analysis("UEMF21", List.of(KEY, other)))
                .withKnowledge(new Knowledge(Map.of(KEY, ANSWER), Map.of()));
        var plan = success(execute(input, gateway));
        assertEquals(List.of(other), plan.missingInformation());
        assertEquals(List.of(new ResponsePlan.Fact("date publication notes uemf21 : " + ANSWER,
                "commonKnowledge")), plan.factsToCommunicate());
        assertTrue(plan.requiresClarification());
    }

    @Test
    void unrelatedCourseAnnouncementReceivesNoOtherCoursesStateOrDates() throws IOException {
        var analysis = new EmailAnalysis.Content("fr", "NOTIFY", List.of("COURSE"),
                List.of(new EmailAnalysis.Entity("COURSE", "UEMF21")), List.of(), List.of(),
                List.of("Le cours UEMF21 est annulé."), List.of(), List.of()).enrich("email-1", "thread-1");
        gateway.enqueueJson(json.write(new ResponsePlan.Content(ResponsePlan.Intent.ACKNOWLEDGE_REQUEST,
                List.of(new ResponsePlan.Fact("Accuser réception de l'annulation.", "emailAnalysis.senderClaims")),
                List.of(), false)));
        var received = new AtomicReference<JsonNode>();
        LlmGateway recording = new LlmGateway() {
            @Override public <T> AgentResult<T> generate(LlmRequest<T> request, TaskContext task, String component) {
                received.set(json.read(request.input(), JsonNode.class));
                return gateway.generate(request, task, component);
            }
        };
        var factory = new AgentFactory(recording, new FileWorldStore(directory, json));
        var writer = factory.createWriter(WriterProfiles.get("AcademicWriter").toAgentConfig());
        assertFalse(success(writer.execute(new WriterInput(analysis), TaskContext.start())).requiresClarification());
        assertTrue(received.get().at("/miniWorlds/domainKnowledge/state").isEmpty());
        assertTrue(received.get().at("/miniWorlds/domainKnowledge/informationLookup").isEmpty());
        assertFalse(received.get().toString().contains("2026-11-18"));
        // Filtering a request must not mutate the cached packaged context.
        gateway.enqueueJson(json.write(ready("2026-11-18")));
        assertFalse(success(writer.execute(new WriterInput(analysis("INF312", List.of("date_publication_notes"))),
                TaskContext.start())).requiresClarification());
        assertEquals("2026-11-18", received.get().at(
                "/miniWorlds/domainKnowledge/informationLookup/date_publication_notes").asText());
    }

    @Test
    void knownCourseTimeoutAndInterruptionStillStopWithoutRetry() throws IOException {
        var input = new WriterInput(analysis("INF312", List.of("date_publication_notes")));
        gateway.enqueueTimeout();
        assertEquals(ErrorCode.LLM_TIMEOUT, assertInstanceOf(ErrorPlan.class, execute(input, gateway)).code());
        assertEquals(1, gateway.callCount());
        gateway.enqueueFailure(new InterruptedException("interrupted"));
        try {
            assertEquals(ErrorCode.INTERNAL_ERROR, assertInstanceOf(ErrorPlan.class, execute(input, gateway)).code());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, gateway.callCount());
        } finally {
            Thread.interrupted();
        }
    }

    private AgentResult<ResponsePlan> execute(WriterInput input, LlmGateway llm) throws IOException {
        return new AgentFactory(llm, new FileWorldStore(directory, json))
                .createWriter(WriterProfiles.get("AcademicWriter").toAgentConfig()).execute(input, TaskContext.start());
    }

    private static ResponsePlan success(AgentResult<ResponsePlan> result) {
        return assertInstanceOf(ResponsePlan.class, assertInstanceOf(Success.class, result).value());
    }

    private static EmailAnalysis analysis(String course, List<String> requested) {
        return new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of("NOTES"),
                course == null ? List.of() : List.of(new EmailAnalysis.Entity("COURSE", course)), requested,
                List.of(), List.of(), List.of(), List.of()).enrich("email-1", "thread-1");
    }

    private static ResponsePlan.Content ready(String fact) {
        return new ResponsePlan.Content(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                List.of(new ResponsePlan.Fact(fact, "domainKnowledge")), List.of(), false);
    }
}
