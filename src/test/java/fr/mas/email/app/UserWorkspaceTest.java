package fr.mas.email.app;

import com.fasterxml.jackson.databind.JsonNode;
import fr.mas.email.contract.*;
import fr.mas.email.llm.*;
import fr.mas.email.mail.MailAccount;
import fr.mas.email.result.*;
import fr.mas.email.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.table.DefaultTableModel;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UserWorkspaceTest {
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();
    private final MailAccount account = new MailAccount("test@gmail.com", "Alex Martin");

    @Test void switchingAccountsIsolatesAndRestoresKnowledgeAcrossRestart() throws Exception {
        var workspace = new UserWorkspace(directory, json);
        var values = new LinkedHashMap<>(workspace.knowledge());
        values.put("PersonalWriter", Map.of("adresse_maison", "Adresse du premier compte"));
        workspace.save(account, values);
        var firstKnowledge = workspace.knowledgeDirectory();
        var firstRuns = workspace.runsDirectory();
        workspace.switchAccount("second@gmail.com");
        assertEquals("second@gmail.com", workspace.account().email());
        assertTrue(workspace.knowledge().get("PersonalWriter").isEmpty());
        assertNotEquals(firstKnowledge, workspace.knowledgeDirectory());
        assertNotEquals(firstRuns, workspace.runsDirectory());
        var secondValues = new LinkedHashMap<>(workspace.knowledge());
        secondValues.put("FinanceWriter", Map.of("reference_dossier", "Compte deux"));
        workspace.save(workspace.account(), secondValues);
        workspace = new UserWorkspace(directory, json);
        assertEquals("second@gmail.com", workspace.account().email());
        workspace.switchAccount("TEST@gmail.com");
        assertEquals(account, workspace.account());
        assertEquals(firstKnowledge, workspace.knowledgeDirectory());
        assertEquals(firstRuns, workspace.runsDirectory());
        assertEquals("Adresse du premier compte", workspace.knowledge().get("PersonalWriter").get("adresse_maison"));
        assertTrue(workspace.knowledge().get("FinanceWriter").isEmpty());
        workspace.switchAccount("second@gmail.com");
        assertEquals("Compte deux", workspace.knowledge().get("FinanceWriter").get("reference_dossier"));
    }

    @Test void savesAllSixWorldsOnlyOnExplicitSetupAndRestoresTheProfile() throws Exception {
        var workspace = new UserWorkspace(directory, json);
        assertFalse(workspace.configured());
        assertFalse(Files.exists(directory.resolve("account.json")));
        var values = new LinkedHashMap<>(workspace.knowledge());
        values.put("AcademicWriter", Map.of("date_publication_notes_inf312", "12 décembre"));
        values.put("PersonalWriter", Map.of("adresse_bowling", "16 route de Salerno"));
        workspace.save(account, values);
        var restored = new UserWorkspace(directory, json);
        assertTrue(restored.configured()); assertEquals(account, restored.account());
        assertEquals("Alex Martin", restored.knowledge().get("COMMON_WORLD").get("nom_utilisateur"));
        assertEquals(values.get("PersonalWriter"), restored.knowledge().get("PersonalWriter"));
        var service = new MiniWorldService(new FileWorldStore(restored.knowledgeDirectory(), json), false);
        assertFalse(service.readKnowledge("AcademicWriter").particular().containsKey("adresse_bowling"));
        assertEquals(account.email(), service.readKnowledge("AcademicWriter").common().get("adresse_email_utilisateur"));
        assertEquals(6, restored.knowledge().size());
        assertFalse(Files.readString(directory.resolve("account.json")).toLowerCase().contains("password"));
    }

    @Test void failedSetupDoesNotPublishPartialKnowledgeAndEditingRemovesOldFacts() throws Exception {
        var workspace = new UserWorkspace(directory, json);
        var values = new LinkedHashMap<>(workspace.knowledge());
        values.put("AcademicWriter", Map.of("date_notes", "12 décembre"));
        workspace.save(account, values);
        var previousDirectory = workspace.knowledgeDirectory();
        values.put("FinanceWriter", Map.of("INVALID KEY", "no"));
        assertThrows(IllegalArgumentException.class, () -> workspace.save(account, values));
        assertEquals(previousDirectory, new UserWorkspace(directory, json).knowledgeDirectory());
        values.put("FinanceWriter", Map.of()); values.put("AcademicWriter", Map.of());
        workspace.save(account, values);
        assertNotEquals(previousDirectory, workspace.knowledgeDirectory());
        assertTrue(workspace.knowledge().get("AcademicWriter").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> workspace.save(new MailAccount("other@gmail.com", ""), values));
    }

    @Test void producesPreciseKeysAndRejectsDuplicatesAndUnfinishedRows() {
        var table = new DefaultTableModel(new String[]{"subject", "value"}, 0);
        table.addRow(new String[]{"Date publication notes INF312", "12 décembre"});
        table.addRow(new String[]{"Adresse réception", "Paris"});
        assertEquals(Map.of("date_publication_notes_inf312", "12 décembre", "adresse_reception", "Paris"), SetupDialog.values(table));
        table.addRow(new String[]{"date_publication_notes_inf312", "different"});
        assertThrows(IllegalArgumentException.class, () -> SetupDialog.values(table));
        table.removeRow(2); table.addRow(new String[]{"missing value", ""});
        assertThrows(IllegalArgumentException.class, () -> SetupDialog.values(table));
    }

    @Test void productionPipelineGetsOnlyUserKnowledgeIncludingAfterCorrection() throws Exception {
        var workspace = new UserWorkspace(directory.resolve("user"), json);
        var values = new LinkedHashMap<>(workspace.knowledge());
        values.put("AcademicWriter", Map.of("date_publication_notes", "18 novembre 2026"));
        workspace.save(account, values);
        var runner = new PipelineRunner(json, workspace.knowledgeDirectory(), false);
        var delegate = DemoScenario.gateway(json);
        var inputs = new ArrayList<JsonNode>();
        LlmGateway recording = new LlmGateway() {
            @Override public <T> AgentResult<T> generate(LlmRequest<T> request, TaskContext task, String component) {
                if (component.equals("AcademicWriter")) inputs.add(json.read(request.input(), JsonNode.class));
                return delegate.generate(request, task, component);
            }
        };
        assertInstanceOf(Success.class, runner.run(DemoScenario.EMAIL, recording, "simulated", directory.resolve("run"), p -> {}));
        var knowledge = inputs.getFirst().get("miniWorlds");
        assertEquals("18 novembre 2026", knowledge.at("/domainKnowledge/informationLookup/date_publication_notes").asText());
        assertEquals("Alex Martin", knowledge.at("/commonKnowledge/informationLookup/nom_utilisateur").asText());
        assertFalse(knowledge.toString().contains("Camille"));
        assertTrue(knowledge.at("/domainKnowledge/state").isMissingNode());
        assertTrue(knowledge.at("/domainKnowledge/scope").isMissingNode());
        values.put("AcademicWriter", Map.of("date_publication_notes", "12 décembre 2026"));
        workspace.save(account, values);
        runner.useKnowledgeDirectory(workspace.knowledgeDirectory());
        delegate.enqueueJson(json.write(new CorrectionPlan(CorrectionPlan.Target.WRITER_AGENT,
                CorrectionPlan.Type.REVISE_PLAN, false, "Vérifier la nouvelle date", List.of(), "Date modifiée")))
                .enqueueJson(json.write(new ResponsePlan.Content(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                        List.of(new ResponsePlan.Fact("12 décembre 2026", "domainKnowledge")), List.of(), false)))
                .enqueueJson(json.write(new DraftContent("Re: INF312", "Bonjour,\n\nLes notes seront publiées le 12 décembre 2026.")));
        assertInstanceOf(Success.class, runner.correct("Vérifier la nouvelle date", recording, directory.resolve("run"), p -> {}));
        var corrected = inputs.getLast().get("miniWorlds");
        assertEquals(2, inputs.size());
        assertEquals("12 décembre 2026", corrected.at("/domainKnowledge/informationLookup/date_publication_notes").asText());
        assertFalse(corrected.toString().contains("Camille"));
    }
}
