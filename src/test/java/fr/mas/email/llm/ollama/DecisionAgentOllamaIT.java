package fr.mas.email.llm.ollama;

import fr.mas.email.agent.AgentConfig;
import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.agent.DecisionInput;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.RouteDecision;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.FileWorldStore;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "ollama.integration", matches = "true")
class DecisionAgentOllamaIT {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void routesUsingOnlyTheDeclaredRegistry(boolean hasSpecialist) throws IOException {
        var specialist = new AgentConfig("Writer42", AgentRole.WRITER, "Projets de recherche scientifique",
                List.of("Répondre aux questions sur les réunions de projet"), List.of("Recherche uniquement"));
        var other = new AgentConfig("ResearchWriter", AgentRole.WRITER, "Dossiers de remboursement",
                List.of("Traiter les demandes de remboursement"), List.of("Comptabilité uniquement"));
        var analysis = new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of("RESEARCH_PROJECT", "MEETING"),
                List.of(new EmailAnalysis.Entity("PROJECT", "ORION")), List.of("date_reunion"),
                List.of(), List.of(), List.of(), List.of(new EmailAnalysis.Evidence("requestedInformation",
                "Quelle est la date de la réunion du projet de recherche ORION ?"))).enrich("email", "thread");
        var input = new DecisionInput(analysis, hasSpecialist ? List.of(other, specialist) : List.of(other));
        var server = URI.create(System.getProperty("ollama.url", OllamaLlmGateway.LOCAL_SERVER.toString()));
        var model = System.getProperty("ollama.model", "Supa-AI/ministral-8b-instruct-2410:q4_k_m");
        var timeout = Duration.ofSeconds(Long.getLong("ollama.timeoutSeconds", 300L));
        Boolean think = Boolean.getBoolean("ollama.omitThink") ? null : false;
        var options = new OllamaLlmGateway.ChatOptions(think, Integer.getInteger("ollama.numPredict", 1536));
        var json = new JsonCodec();
        var config = new AgentConfig("Decision", AgentRole.DECISION, "Routage", List.of(), List.of());
        try (var gateway = new OllamaLlmGateway(server, model, json, options)) {
            var decision = new AgentFactory(gateway, new FileWorldStore(directory, json)).createDecision(config, timeout);
            var result = decision.execute(input, TaskContext.start());
            var success = assertInstanceOf(Success.class, result, () -> "Expected a route decision: " + result);
            var route = assertInstanceOf(RouteDecision.class, success.value());
            assertEquals(analysis.emailId(), route.emailId());
            if (hasSpecialist) {
                assertEquals(RouteDecision.DecisionStatus.SELECTED, route.decisionStatus());
                assertEquals(specialist.agentId(), route.selectedAgentId());
                assertEquals(List.of(specialist.agentId()), route.candidateAgents());
                assertFalse(route.requiresUserConfirmation());
            } else {
                assertEquals(RouteDecision.DecisionStatus.NEEDS_CLARIFICATION, route.decisionStatus());
                assertEquals(List.of(), route.candidateAgents());
                assertTrue(route.requiresUserConfirmation());
                assertNotNull(route.questionToUser());
            }
        }
    }
}
