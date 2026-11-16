package fr.mas.email.app;

import fr.mas.email.contract.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class TestBenchTest {
    @TempDir Path directory;

    @Test
    void clarificationMemoryAndSimulatedDeliveryAreReusableOnTheNextTurn() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Swing needs a graphical environment");
        var window = edt(() -> new TestBench(new JsonCodec(), directory.resolve("runs"), "http://localhost:11434", "test"));
        String fact = "Publication le 27 novembre à 14 h.";
        try {
            edt(() -> {
                descendants(window).filter(JComboBox.class::isInstance).map(JComboBox.class::cast)
                        .findFirst().orElseThrow().setSelectedIndex(2);
                button(window, "Générer le brouillon").doClick();
                return null;
            });
            await(() -> button(window, "Répondre à la clarification").isEnabled());
            edt(() -> {
                var label = descendants(window).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .filter(item -> item.getText().equals("Votre réponse")).findFirst().orElseThrow();
                var answer = (JTextArea) ((JScrollPane) label.getLabelFor()).getViewport().getView();
                answer.setText(fact);
                descendants(window).filter(JRadioButton.class::isInstance).map(JRadioButton.class::cast)
                        .filter(button -> button.getText().contains("K-Commun")).findFirst().orElseThrow().doClick();
                button(window, "Répondre à la clarification").doClick();
                return null;
            });
            await(() -> button(window, "Valider le brouillon").isEnabled());
            edt(() -> { button(window, "Valider le brouillon").doClick(); return null; });
            await(() -> button(window, "Simuler l’envoi").isEnabled());
            edt(() -> { button(window, "Simuler l’envoi").doClick(); return null; });
            await(() -> button(window, "Tour suivant").isEnabled() && !button(window, "Simuler l’envoi").isEnabled());
            edt(() -> { button(window, "Tour suivant").doClick(); return null; });
            await(() -> descendants(window).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                    .anyMatch(area -> area.getText().contains("Réponse simulée :") && area.getText().contains(fact)));
            edt(() -> { button(window, "Générer le brouillon").doClick(); return null; });
            await(() -> button(window, "Valider le brouillon").isEnabled());
            assertTrue(edt(() -> descendants(window).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                    .anyMatch(area -> area.getText().contains("date publication notes : " + fact))));
        } finally {
            edt(() -> { window.dispose(); return null; });
        }
    }

    @Test
    void draftApprovalAndMultilineCorrectionAreWiredToTheWindow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Swing needs a graphical environment");
        var window = edt(() -> new TestBench(new JsonCodec(), directory.resolve("runs"), "http://localhost:11434", "test"));
        var approvalButton = edt(() -> button(window, "Valider le brouillon"));
        try {
            edt(() -> {
                assertTrue(descendants(window).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                        .anyMatch(field -> field.isEditable() && field.getText().equals(DemoScenario.EMAIL)));
                assertTrue(descendants(window).noneMatch(MailboxPanel.class::isInstance));
                descendants(window).filter(JComboBox.class::isInstance).map(JComboBox.class::cast)
                        .findFirst().orElseThrow().setSelectedIndex(1);
                assertFalse(button(window, "Valider le brouillon").isEnabled());
                button(window, "Générer le brouillon").doClick();
                return null;
            });
            await(approvalButton::isEnabled);
            edt(() -> { button(window, "Valider le brouillon").doClick(); return null; });
            await(() -> descendants(window).filter(JButton.class::isInstance).map(JButton.class::cast)
                    .anyMatch(button -> button.getText().equals("Brouillon validé")));
            try (var files = Files.walk(directory)) {
                assertEquals(1, files.filter(path -> path.getFileName().toString().equals("draft-approval.json")).count());
            }
            edt(() -> {
                assertFalse(button(window, "Brouillon validé").isEnabled());
                var label = descendants(window).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .filter(item -> item.getText().equals("Correction ou information à ajouter")).findFirst().orElseThrow();
                var feedback = assertInstanceOf(JTextArea.class,
                        assertInstanceOf(JScrollPane.class, label.getLabelFor()).getViewport().getView());
                feedback.setText("Tutoie-le.\nFais plus court.");
                assertTrue(feedback.getRows() >= 3);
                button(window, "Corriger le brouillon").doClick();
                return null;
            });
            await(() -> approvalButton.getText().equals("Valider le brouillon") && approvalButton.isEnabled());
        } finally {
            edt(() -> { window.dispose(); return null; });
        }
    }

    private static JButton button(Container window, String text) {
        return descendants(window).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> button.getText().equals(text)).findFirst().orElseThrow();
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Stream.of(container.getComponents()).flatMap(TestBenchTest::descendants) : Stream.empty());
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void await(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (edt(ready::getAsBoolean)) return;
            Thread.sleep(30);
        }
        fail("The Swing action did not complete");
    }
}
