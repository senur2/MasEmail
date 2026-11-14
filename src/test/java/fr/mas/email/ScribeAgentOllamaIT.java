package fr.mas.email;

import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.contract.DraftResponse;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.llm.ollama.OllamaLlmGateway;
import fr.mas.email.result.Success;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.FileWorldStore;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "ollama.integration", matches = "true")
class ScribeAgentOllamaIT {
    @TempDir Path directory;

    @ParameterizedTest
    @MethodSource("fr.mas.email.ScribeAgentTest#scenarios")
    void generatesDraftForVisualInspection(ScribeAgentTest.Scenario scenario) throws IOException {
        var server = URI.create(System.getProperty("ollama.url", OllamaLlmGateway.LOCAL_SERVER.toString()));
        var model = System.getProperty("ollama.model", "qwen3.5:4b-q4_K_M");
        var timeout = Duration.ofSeconds(Long.getLong("ollama.timeoutSeconds", 300L));
        var json = new JsonCodec();
        var plan = scenario.plan();
        try (var gateway = new OllamaLlmGateway(server, model, json)) {
            var scribe = new AgentFactory(gateway, new FileWorldStore(directory, json))
                    .createScribe(AgentFactoryTest.config("Scribe", AgentRole.SCRIBE), timeout);
            var result = scribe.execute(plan, TaskContext.start());
            var success = assertInstanceOf(Success.class, result, () -> "Expected a draft: " + result);
            var draft = assertInstanceOf(DraftResponse.class, success.value());
            assertEquals(plan.emailId(), draft.emailId());
            assertEquals(plan.planId(), draft.planId());
            assertEquals(DraftResponse.Status.DRAFT, draft.status());
            System.out.printf("%s%n%s%n%n%s%n%n", scenario, draft.subject(), draft.body());
        }
    }
}
