package fr.mas.email.llm.ollama;

import fr.mas.email.agent.AgentConfig;
import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.agent.ParserInput;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.FileWorldStore;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "ollama.integration", matches = "true")
class ParserAgentOllamaIT {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"orion", "universite", "eurosys", "relance-comptable", "bowling"})
    void extractsExpectedMeaningFromSyntheticEmails(String scenario) throws IOException {
        String source = loadEmail(scenario);
        var input = new ParserInput("email-" + scenario, "thread-" + scenario, source);
        var server = URI.create(System.getProperty("ollama.url", OllamaLlmGateway.LOCAL_SERVER.toString()));
        var model = System.getProperty("ollama.model", "Supa-AI/ministral-8b-instruct-2410:q4_k_m");
        var timeout = Duration.ofSeconds(Long.getLong("ollama.timeoutSeconds", 300L));
        Boolean think = Boolean.parseBoolean(System.getProperty("ollama.omitThink", "false")) ? null : false;
        var options = new OllamaLlmGateway.ChatOptions(think, Integer.getInteger("ollama.numPredict", 1536));
        var json = new JsonCodec();
        var config = new AgentConfig("Parser", AgentRole.PARSER, "Email extraction", List.of(), List.of());
        try (var gateway = new OllamaLlmGateway(server, model, json, options)) {
            var parser = new AgentFactory(gateway, new FileWorldStore(directory, json)).createParser(config, timeout);
            var result = parser.execute(input, TaskContext.start());
            var success = assertInstanceOf(Success.class, result, () -> "Expected parser success: " + result);
            var analysis = assertInstanceOf(EmailAnalysis.class, success.value());
            assertEquals(input.emailId(), analysis.emailId());
            assertEquals(input.threadId(), analysis.threadId());
            assertEquals(scenario.equals("eurosys") ? "en" : "fr", analysis.language());
            assertFalse(analysis.evidence().isEmpty(), "The email contains evidence for important fields");
            analysis.evidence().forEach(item -> assertTrue(source.contains(item.text()), item::text));
            checkScenario(scenario, analysis);
        }
    }

    private static void checkScenario(String scenario, EmailAnalysis analysis) {
        var actions = analysis.requestedActions();
        var claims = analysis.senderClaims();
        switch (scenario) {
            case "orion" -> {
                assertEquals(List.of(), actions);
                assertInformation(analysis, "reunion|meeting");
                assertMeaning(claims, "salle|room", "indisponib|unavailab|disponib");
                assertMeaning(analysis.uncertainties(), "salle|room");
            }
            case "universite" -> {
                assertEquals(List.of(), actions);
                assertInformation(analysis, "note|grade|resultat", "corrige|correction|solution", "copie|exam");
                assertEquals(List.of(), claims);
            }
            case "eurosys" -> {
                assertInformation(analysis);
                assertMeaning(claims, "accept");
                assertEquals(2, actions.size(), actions::toString);
                var submission = findAction(actions, "manuscript|manuscrit|camera.ready|paper",
                        "submit|submission|soumet|soumis|depos");
                assertMeaning(List.of(submission.description()), "prepar|revis|final", "comment|review|avis");
                assertDeadline(submission, "\\b(?:may\\s+12|12\\s+(?:may|mai))\\b");
                var registration = findAction(actions, "register|registration|inscri", "author|auteur");
                assertNotEquals(submission, registration);
                assertMeaning(List.of(registration.description()), "at least one|one author|au moins un|un auteur|1 author");
                assertNull(registration.deadline(), registration::toString);
            }
            case "relance-comptable" -> {
                assertInformation(analysis);
                assertMeaning(claims, "dossier|file", "incomplet");
                assertMeaning(claims, "remboursement|reimbursement", "suspend|suspens",
                        "risqu|peuvent|pourrai|si |sans |may|could");
                assertEquals(1, actions.size(), actions::toString);
                var transmission = findAction(actions, "justificati|document|piece",
                        "transmet|transmi|envoi|envoy|send|submit|fourn");
                assertDeadline(transmission, "\\b(?:15\\s+juin|june\\s+15|15\\s+june)\\b");
            }
            case "bowling" -> {
                assertEquals(List.of(), actions);
                assertInformation(analysis, "sortie|maintien|bowling|outing", "reserv|piste|lane", "sarah");
                assertEquals(List.of(), claims);
            }
            default -> fail("Unknown scenario " + scenario);
        }
    }

    private static void assertInformation(EmailAnalysis analysis, String... concepts) {
        var information = analysis.requestedInformation();
        assertEquals(concepts.length, information.size(), information::toString);
        for (String concept : concepts) assertMeaning(information, concept);
    }

    private static EmailAnalysis.RequestedAction findAction(List<EmailAnalysis.RequestedAction> actions,
                                                           String... concepts) {
        return actions.stream()
                .filter(action -> matches(action.action() + " " + action.description(), concepts))
                .findFirst().orElseThrow(() -> new AssertionError("Missing action in " + actions));
    }

    private static void assertDeadline(EmailAnalysis.RequestedAction action, String date) {
        assertNotNull(action.deadline(), action::toString);
        assertTrue(matches(action.deadline(), date), action::toString);
    }

    private static void assertMeaning(List<String> values, String... concepts) {
        assertTrue(values.stream().anyMatch(value -> matches(value, concepts)),
                () -> "Missing " + List.of(concepts) + " in " + values);
    }

    private static boolean matches(String value, String... concepts) {
        String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('_', ' ');
        for (String concept : concepts) {
            if (!Pattern.compile(concept).matcher(normalized).find()) return false;
        }
        return true;
    }

    private static String loadEmail(String name) throws IOException {
        try (var stream = ParserAgentOllamaIT.class.getResourceAsStream("/parser/" + name + ".txt")) {
            assertNotNull(stream, name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
