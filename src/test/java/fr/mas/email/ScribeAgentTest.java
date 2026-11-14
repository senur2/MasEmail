package fr.mas.email;

import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.agent.ScribeAgent;
import fr.mas.email.agent.WriterInput;
import fr.mas.email.agent.WriterProfiles;
import fr.mas.email.contract.Contract;
import fr.mas.email.contract.DraftContent;
import fr.mas.email.contract.DraftResponse;
import fr.mas.email.contract.InvalidOutputException;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.MessageFactory;
import fr.mas.email.contract.ResponsePlan;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.orchestration.DraftResponseValidator;
import fr.mas.email.orchestration.OrchestratorAgent;
import fr.mas.email.result.ErrorCode;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.FileWorldStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ScribeAgentTest {
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();
    private final FakeLlmGateway gateway = new FakeLlmGateway(json);

    record Scenario(String writerId, String language, ResponsePlan.Intent intent, String fact, DraftContent example) {
        ResponsePlan.Content content() {
            return TestData.planContent(intent, List.of(new ResponsePlan.Fact(fact, "domainKnowledge")));
        }

        ResponsePlan plan() {
            var profile = WriterProfiles.get(writerId);
            return content().enrich(writerId, "email-001", profile.replyStructure(), language, profile.tone());
        }

        @Override public String toString() { return writerId; }
    }

    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("AcademicWriter", "fr", ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                        "Indiquer que les notes d'INF312 seront publiées le 18 novembre 2026.",
                        new DraftContent("Re: Publication des notes d’INF312",
                                "Bonjour,\n\nJe vous confirme que les notes d’INF312 seront publiées le 18 novembre 2026.\n\nCordialement,")),
                new Scenario("ResearchWriter", "en", ResponsePlan.Intent.ACKNOWLEDGE_REQUEST,
                        "Thank the Program Committee for accepting the paper and acknowledge the December 18, 2026 deadline.",
                        new DraftContent("Re: Paper acceptance",
                                "Dear Program Committee,\n\nThank you for accepting our paper. We have noted the December 18, 2026 deadline.\n\nBest regards,")),
                new Scenario("PersonalWriter", "fr", ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                        "Confirmer que la sortie bowling est maintenue et qu'une piste est réservée.",
                        new DraftContent("Re: Bowling",
                                "Salut,\n\nJe te confirme que le bowling est maintenu et qu’on a réservé une piste.\n\nÀ bientôt !")),
                new Scenario("FinanceWriter", "fr", ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                        "Indiquer que le reçu du billet de train manque au dossier de septembre.",
                        new DraftContent("Re: Justificatif du dossier de septembre",
                                "Bonjour,\n\nJe vous confirme que le reçu du billet de train manque au dossier de septembre.\n\nVeuillez agréer mes salutations distinguées.")));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void writerStyleReachesScribeAndDraftIsValidated(Scenario scenario) throws IOException {
        var worlds = new FileWorldStore(directory, json);
        var factory = new AgentFactory(gateway, worlds);
        var profile = WriterProfiles.get(scenario.writerId());
        var analysis = TestData.analysis(scenario.language(), "REQUEST_INFORMATION", List.of());
        gateway.enqueueJson(json.write(scenario.content())).enqueueJson(json.write(scenario.example()));
        var writer = factory.createWriter(profile.toAgentConfig());
        var writing = writer.execute(new WriterInput(analysis), TaskContext.start());
        var plan = assertInstanceOf(ResponsePlan.class, assertInstanceOf(Success.class, writing).value());
        assertEquals(scenario.language(), plan.language());
        assertEquals(profile.tone(), plan.tone());
        assertEquals(profile.replyStructure(), plan.replyStructure());
        var scribe = factory.createScribe(AgentFactoryTest.config("Scribe", AgentRole.SCRIBE));
        var validator = new DraftResponseValidator(plan, (current, draft) -> draft.body().equals(scenario.example().body()));
        var orchestrator = new OrchestratorAgent(worlds.sharedWorld(), new MessageFactory(Clock.systemUTC()));
        var result = orchestrator.execute(scribe, plan, TaskContext.start(), Contract.DRAFT_RESPONSE, validator);
        if (!(result instanceof Success(var message, var rawIgnored0))) throw new AssertionError("Expected a draft: " + result);
        assertEquals(scenario.example().enrich(plan), message.payload());
        assertEquals(DraftResponse.Status.DRAFT, message.payload().status());
        assertEquals(2, gateway.callCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[Nom]", "[Date prévue]", "[Votre\nNom]", "[Votre Nom]"})
    void rejectsRemainingPlaceholders(String placeholder) throws IOException {
        var plan = scenarios().findFirst().orElseThrow().plan();
        var content = new DraftContent("Re: Notes", "Bonjour " + placeholder);
        var validator = new DraftResponseValidator(plan, (current, draft) -> true);
        assertThrows(InvalidOutputException.class, () -> validator.validate(content.enrich(plan)));
        gateway.enqueueJson(json.write(content));
        var error = assertInstanceOf(ErrorPlan.class, scribe().execute(plan, TaskContext.start()));
        assertEquals(ErrorCode.INVALID_OUTPUT, error.code());
        assertEquals(1, gateway.callCount());
    }

    @Test
    void blocksIncompletePlanBeforeCallingGateway() throws IOException {
        var plan = new ResponsePlan.Content(ResponsePlan.Intent.REQUEST_CLARIFICATION, List.of(),
                List.of("date_publication_notes"), true).enrich("AcademicWriter", "email", List.of(), "fr", "formel");
        var error = assertInstanceOf(ErrorPlan.class, scribe().execute(plan, TaskContext.start()));
        assertEquals(ErrorCode.INVALID_OUTPUT, error.code());
        assertEquals(0, gateway.callCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"subject\":\" \",\"body\":\"Bonjour\"}",
            "{\"subject\":\"Re: Notes\",\"body\":\" \"}",
            "{\"subject\":\"Re: Notes\",\"body\":\"Bonjour\",\"emailId\":\"forged\"}"
    })
    void rejectsInvalidGeneratedContent(String content) throws IOException {
        gateway.enqueueJson(content);
        var plan = scenarios().findFirst().orElseThrow().plan();
        var error = assertInstanceOf(ErrorPlan.class, scribe().execute(plan, TaskContext.start()));
        assertEquals(ErrorCode.INVALID_JSON, error.code());
        assertEquals(1, gateway.callCount());
    }

    private ScribeAgent scribe() throws IOException {
        return new AgentFactory(gateway, new FileWorldStore(directory, json))
                .createScribe(AgentFactoryTest.config("Scribe", AgentRole.SCRIBE));
    }
}
