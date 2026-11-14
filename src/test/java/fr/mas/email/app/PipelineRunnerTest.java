package fr.mas.email.app;

import fr.mas.email.contract.DraftResponse;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.ResponsePlan;
import fr.mas.email.contract.RouteDecision;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.result.DiscussionPlan;
import fr.mas.email.result.ErrorCode;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Success;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static fr.mas.email.app.PipelineRunner.Status.COMPLETE;
import static fr.mas.email.app.PipelineRunner.Status.RUNNING;
import static org.junit.jupiter.api.Assertions.*;

class PipelineRunnerTest {
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();
    private final PipelineRunner runner = new PipelineRunner(json);

    @Test
    void runsTheFourAgentsAndWritesReadableOutputs() throws IOException {
        var events = new ArrayList<PipelineRunner.Progress>();
        var gateway = DemoScenario.gateway(json);
        var result = runner.run(DemoScenario.EMAIL, gateway, "Simulation", directory, event -> {
            assertTrue(Files.isRegularFile(directory.resolve(PipelineRunner.LOG_NAME)));
            events.add(event);
        });
        var draft = assertInstanceOf(DraftResponse.class, assertInstanceOf(Success.class, result).value());
        assertEquals(DraftResponse.Status.DRAFT, draft.status());
        assertTrue(draft.body().contains("18 novembre 2026"));
        assertEquals(4, gateway.callCount());
        assertEquals(List.of(RUNNING, COMPLETE, RUNNING, COMPLETE, RUNNING, COMPLETE, RUNNING, COMPLETE),
                events.stream().map(PipelineRunner.Progress::status).toList());

        var log = readLog();
        assertTrue(log.indexOf("ParserAgent / PARSER") < log.indexOf("DecisionAgent / DECISION"));
        assertTrue(log.indexOf("DecisionAgent / DECISION") < log.indexOf("AcademicWriter / WRITER"));
        assertTrue(log.indexOf("AcademicWriter / WRITER") < log.indexOf("ScribeAgent / SCRIBE"));
        assertTrue(log.contains("\"requestedInformation\" : [ \"date_publication_notes\" ]"));
        assertTrue(log.contains("\"selectedAgentId\" : \"AcademicWriter\""));
        assertTrue(log.contains("\"responseIntent\" : \"ANSWER_INFORMATION_REQUEST\""));
        assertTrue(log.contains("\"status\" : \"DRAFT\""));
        assertTrue(log.contains("À quelle date"));
        assertFalse(log.contains("## 1. ROLE"));
    }

    @Test
    void logsTheErrorAndStopsAfterTheFailedStage() throws IOException {
        var gateway = new FakeLlmGateway(json).enqueueJson("{");
        var result = runner.run("Bonjour", gateway, "Simulation", directory, event -> {});
        assertEquals(ErrorCode.INVALID_JSON, assertInstanceOf(ErrorPlan.class, result).code());
        assertEquals(1, gateway.callCount());
        var log = readLog();
        assertTrue(log.contains("ParserAgent / PARSER"));
        assertTrue(log.contains("\"code\" : \"INVALID_JSON\""));
        assertFalse(log.contains("DecisionAgent / DECISION"));
    }

    @Test
    void logsTheInitialPlanAndQuestionsWhenTheWriterNeedsClarification() throws IOException {
        String key = "date_publication_notes";
        var analysis = new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of("NOTES"),
                List.of(), List.of(key), List.of(), List.of(), List.of(), List.of());
        var route = new RouteDecision.Content("AcademicWriter", List.of("AcademicWriter"),
                RouteDecision.DecisionStatus.SELECTED, List.of("Cours"), null, false, null);
        var gateway = new FakeLlmGateway(json).enqueueJson(json.write(analysis)).enqueueJson(json.write(route));
        var result = runner.run("À quelle date les notes seront-elles publiées ?", gateway, "Simulation",
                directory, event -> {});
        assertEquals(List.of("À quelle date les notes seront-elles publiées ?"),
                assertInstanceOf(DiscussionPlan.class, result).questions());
        // Without an identified course, the scope guard asks before invoking the Writer LLM.
        assertEquals(2, gateway.callCount());
        var log = readLog();
        assertTrue(log.contains("\"responseIntent\" : \"REQUEST_CLARIFICATION\""));
        assertTrue(log.contains("\"questions\" : [ \"À quelle date les notes seront-elles publiées ?\" ]"));
        assertTrue(log.contains("WAITING_USER"));
        assertFalse(log.contains("ScribeAgent / SCRIBE"));
    }

    private String readLog() throws IOException {
        return Files.readString(directory.resolve(PipelineRunner.LOG_NAME));
    }
}
