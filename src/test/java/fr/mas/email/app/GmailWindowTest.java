package fr.mas.email.app;

import com.sun.net.httpserver.HttpServer;
import fr.mas.email.contract.*;
import fr.mas.email.mail.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class GmailWindowTest {
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();

    @Test void setupSavesTheSixWorldsAndDoesNotRequestCredentials() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var workspace = new UserWorkspace(directory, json);
        var dialog = edt(() -> new SetupDialog(null, workspace));
        try {
            edt(() -> {
                var fields = descendants(dialog).filter(JTextField.class::isInstance).map(JTextField.class::cast).toList();
                fields.get(0).setText("test@gmail.com"); fields.get(1).setText("Alex");
                var tabs = descendants(dialog).filter(JTabbedPane.class::isInstance).map(JTabbedPane.class::cast).findFirst().orElseThrow();
                assertEquals(6, tabs.getTabCount());
                assertTrue(descendants(dialog).noneMatch(JPasswordField.class::isInstance));
                button(dialog, "Enregistrer mes informations").doClick(); return null;
            });
            await(() -> workspace.configured() && button(dialog, "Enregistrer mes informations").isEnabled());
            assertEquals("Alex", new UserWorkspace(directory, json).account().displayName());
        } finally { edt(() -> { dialog.dispose(); return null; }); }
    }

    @Test void readsGmailGeneratesApprovesAndRequiresASeparateSendConfirmation() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var workspace = new UserWorkspace(directory.resolve("user"), json);
        var knowledge = new LinkedHashMap<>(workspace.knowledge());
        knowledge.put("AcademicWriter", Map.of("date_publication_notes", "18 novembre 2026"));
        workspace.save(new MailAccount("test@gmail.com", "Alex"), knowledge);
        var mail = new SimulatedGmail();
        var replies = responses();
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                var bytes = json.write(Map.of("done", true, "message", Map.of("role", "assistant",
                        "content", replies.get(calls.getAndIncrement() % replies.size())))).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        var window = edt(() -> new TestBench(json, workspace.runsDirectory(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "simulated", workspace, mail));
        try {
            edt(() -> {
                assertFalse(button(window, "Envoyer la réponse validée").isEnabled());
                assertFalse(button(window, "Générer le brouillon").isEnabled());
                assertFalse(button(window, "Charger l’exemple").isVisible()); return null;
            });
            SwingUtilities.invokeLater(() -> button(window, "Connexion / Actualiser Gmail").doClick());
            await(() -> visibleDialog("Connexion Gmail") != null);
            edt(() -> {
                var dialog = visibleDialog("Connexion Gmail");
                descendants(dialog).filter(JPasswordField.class::isInstance).map(JPasswordField.class::cast)
                        .findFirst().orElseThrow().setText("abcd efgh ijkl mnop");
                optionPane(dialog).setValue(JOptionPane.OK_OPTION); return null;
            });
            await(() -> list(window).getModel().getSize() == 1 && button(window, "Ouvrir l’email sélectionné").isEnabled());
            edt(() -> { list(window).setSelectedIndex(0); button(window, "Ouvrir l’email sélectionné").doClick(); return null; });
            await(() -> button(window, "Générer le brouillon").isEnabled());
            edt(() -> { button(window, "Générer le brouillon").doClick(); return null; });
            var approve = edt(() -> button(window, "Valider le brouillon"));
            await(approve::isEnabled);
            assertEquals(4, calls.get()); assertEquals(0, mail.sends.get());
            edt(() -> { approve.doClick(); return null; });
            await(() -> button(window, "Envoyer la réponse validée").isEnabled());
            SwingUtilities.invokeLater(() -> button(window, "Envoyer la réponse validée").doClick());
            await(() -> visibleDialog("Confirmer l’envoi depuis test@gmail.com") != null);
            edt(() -> { optionPane(visibleDialog("Confirmer l’envoi depuis test@gmail.com")).setValue(JOptionPane.CANCEL_OPTION); return null; });
            assertEquals(0, mail.sends.get());
            // Cancelling a manual edit preserves approval; saving one requires a fresh pipeline run.
            SwingUtilities.invokeLater(() -> button(window, "Modifier l’email entrant").doClick());
            await(() -> visibleDialog("Modifier l’email entrant") != null);
            edt(() -> { optionPane(visibleDialog("Modifier l’email entrant")).setValue(JOptionPane.CANCEL_OPTION); return null; });
            assertTrue(edt(() -> button(window, "Envoyer la réponse validée").isEnabled()));
            SwingUtilities.invokeLater(() -> button(window, "Modifier l’email entrant").doClick());
            await(() -> visibleDialog("Modifier l’email entrant") != null);
            edt(() -> {
                var dialog = visibleDialog("Modifier l’email entrant");
                descendants(dialog).filter(JTextField.class::isInstance).map(JTextField.class::cast)
                        .findFirst().orElseThrow().setText("Question reformulée INF312");
                descendants(dialog).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                        .findFirst().orElseThrow().setText("Bonjour, quand les notes du cours INF312 seront-elles publiées ?");
                optionPane(dialog).setValue(JOptionPane.OK_OPTION); return null;
            });
            await(() -> approve.getText().equals("Valider le brouillon") && !approve.isEnabled());
            assertFalse(edt(() -> button(window, "Envoyer la réponse validée").isEnabled()));
            edt(() -> { button(window, "Générer le brouillon").doClick(); return null; });
            await(approve::isEnabled);
            assertEquals(8, calls.get());
            edt(() -> { approve.doClick(); return null; });
            await(() -> button(window, "Envoyer la réponse validée").isEnabled());
            SwingUtilities.invokeLater(() -> button(window, "Envoyer la réponse validée").doClick());
            await(() -> visibleDialog("Confirmer l’envoi depuis test@gmail.com") != null);
            edt(() -> { optionPane(visibleDialog("Confirmer l’envoi depuis test@gmail.com")).setValue(JOptionPane.OK_OPTION); return null; });
            await(() -> mail.sends.get() == 1 && button(window, "Connexion / Actualiser Gmail").isEnabled());
            assertFalse(edt(() -> button(window, "Envoyer la réponse validée").isEnabled()));
            assertEquals("nina@example.org", mail.outgoing.recipient());
            // The knowledge button opens the existing six-space editor and saves explicit changes.
            SwingUtilities.invokeLater(() -> button(window, "Modifier les connaissances").doClick());
            await(() -> visibleDialog("Mon compte et mes connaissances") != null);
            edt(() -> {
                var dialog = visibleDialog("Mon compte et mes connaissances");
                var table = descendants(dialog).filter(JTable.class::isInstance).map(JTable.class::cast).findFirst().orElseThrow();
                var model = (javax.swing.table.DefaultTableModel) table.getModel();
                model.setValueAt("adresse bureau", 0, 0); model.setValueAt("Bureau 204", 0, 1);
                button(dialog, "Enregistrer mes informations").doClick(); return null;
            });
            await(() -> visibleDialog("Mon compte et mes connaissances") == null);
            assertEquals("Bureau 204", new UserWorkspace(directory.resolve("user"), json).knowledge().get("COMMON_WORLD").get("adresse_bureau"));
            assertFalse(edt(approve::isEnabled));
            // Cancel preserves the account. Switching clears the mailbox and requires fresh credentials.
            SwingUtilities.invokeLater(() -> button(window, "Changer de compte Gmail").doClick());
            await(() -> visibleDialog("Changer de compte Gmail") != null);
            edt(() -> { optionPane(visibleDialog("Changer de compte Gmail")).setValue(JOptionPane.CANCEL_OPTION); return null; });
            assertEquals("test@gmail.com", workspace.account().email());
            SwingUtilities.invokeLater(() -> button(window, "Changer de compte Gmail").doClick());
            await(() -> visibleDialog("Changer de compte Gmail") != null);
            edt(() -> {
                var pane = optionPane(visibleDialog("Changer de compte Gmail"));
                pane.setInputValue("second@gmail.com"); pane.setValue(JOptionPane.OK_OPTION); return null;
            });
            await(() -> window.getTitle().contains("second@gmail.com") && button(window, "Connexion / Actualiser Gmail").isEnabled());
            assertEquals(0, edt(() -> list(window).getModel().getSize()));
            assertFalse(edt(() -> button(window, "Générer le brouillon").isEnabled()));
            assertFalse(edt(() -> button(window, "Envoyer la réponse validée").isEnabled()));
            SwingUtilities.invokeLater(() -> button(window, "Connexion / Actualiser Gmail").doClick());
            await(() -> visibleDialog("Connexion Gmail") != null);
            edt(() -> { optionPane(visibleDialog("Connexion Gmail")).setValue(JOptionPane.CANCEL_OPTION); return null; });
            try (var files = Files.walk(directory.resolve("user/runs"))) {
                assertTrue(files.anyMatch(file -> file.getFileName().toString().equals("mail-delivery.json")));
            }
        } finally {
            edt(() -> { for (var owned : window.getOwnedWindows()) owned.dispose(); window.dispose(); return null; });
            server.stop(0);
        }
    }

    private List<String> responses() {
        return List.of(
                json.write(new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of("NOTES"),
                        List.of(new EmailAnalysis.Entity("COURSE", "INF312")), List.of("date_publication_notes"),
                        List.of(), List.of(), List.of(), List.of())),
                json.write(new RouteDecision.Content("AcademicWriter", List.of("AcademicWriter"),
                        RouteDecision.DecisionStatus.SELECTED, List.of("Cours"), null, false, null)),
                json.write(new ResponsePlan.Content(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                        List.of(new ResponsePlan.Fact("Les notes seront publiées le 18 novembre 2026.", "domainKnowledge")), List.of(), false)),
                json.write(new DraftContent("Re: INF312", "Bonjour,\n\nLes notes seront publiées le 18 novembre 2026.\n\nCordialement,")));
    }

    private static class SimulatedGmail implements MailService {
        final AtomicInteger sends = new AtomicInteger(); volatile OutgoingMail outgoing;
        final MailSummary summary = new MailSummary(1, 99, "Nina", "INF312", Instant.now());
        public List<MailSummary> listInbox(MailAccount a, char[] p) { return List.of(summary); }
        public ReceivedMail read(MailAccount a, char[] p, MailSummary m) {
            return new ReceivedMail(a.email(), m, "nina@example.org", "<question@example.org>", DemoScenario.EMAIL);
        }
        public String send(MailAccount a, char[] p, OutgoingMail value) {
            outgoing = value; sends.incrementAndGet(); return "<sent@example.org>";
        }
    }

    private static JList<?> list(Container parent) {
        return descendants(parent).filter(JList.class::isInstance).map(JList.class::cast).findFirst().orElseThrow();
    }
    private static JOptionPane optionPane(Container parent) {
        return descendants(parent).filter(JOptionPane.class::isInstance).map(JOptionPane.class::cast).findFirst().orElseThrow();
    }
    private static JDialog visibleDialog(String title) {
        return Arrays.stream(Window.getWindows()).filter(JDialog.class::isInstance).map(JDialog.class::cast)
                .filter(dialog -> dialog.isVisible() && dialog.getTitle().equals(title)).findFirst().orElse(null);
    }
    private static JButton button(Container parent, String text) {
        return descendants(parent).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> button.getText().equals(text)).findFirst().orElseThrow();
    }
    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Stream.of(container.getComponents()).flatMap(GmailWindowTest::descendants) : Stream.empty());
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) { if (edt(ready::getAsBoolean)) return; Thread.sleep(30); }
        fail("Swing action did not complete");
    }
}
