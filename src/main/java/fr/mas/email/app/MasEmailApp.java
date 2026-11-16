package fr.mas.email.app;

import fr.mas.email.contract.JsonCodec;
import fr.mas.email.llm.ollama.OllamaLlmGateway;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;

public final class MasEmailApp {
    private MasEmailApp() {}

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("MAS Email — Java 21\n"
                    + "Lancer le JAR pour choisir entre Gmail et le mode test sans compte.\n"
                    + "--test-bench : banc de test avec les données de démonstration.\n"
                    + "Paramètres JVM facultatifs : mas.userDirectory, mas.runsDirectory, ollama.url, ollama.model");
            return;
        }
        boolean bench = args.length == 1 && args[0].equals("--test-bench");
        if (args.length != 0 && !bench) throw new IllegalArgumentException("Utiliser --help pour les options");
        var directory = Path.of(System.getProperty("mas.runsDirectory", "data/runs")).toAbsolutePath().normalize();
        String address = System.getProperty("ollama.url", OllamaLlmGateway.LOCAL_SERVER.toString());
        String model = System.getProperty("ollama.model", "qwen3.5:4b-q4_K_M");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                var json = new JsonCodec();
                int choice = bench ? 1 : chooseMode();
                if (choice == 1) {
                    new TestBench(json, directory, address, model).setVisible(true);
                } else if (choice == 0) {
                    var workspace = new UserWorkspace(Path.of(System.getProperty("mas.userDirectory", "data/user")), json);
                    if (!workspace.configured() && !SetupDialog.show(null, workspace)) return;
                    workspace.knowledge(); // Validate the selected user data before opening a session.
                    new TestBench(json, workspace.runsDirectory(), address, model, workspace,
                            new fr.mas.email.mail.JakartaGmailService()).setVisible(true);
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Impossible d’ouvrir MAS Email. " + e.getMessage(),
                        "MAS Email", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    static int chooseMode() {
        Object[] modes = {"Ouvrir Gmail", "Tester sans compte"};
        return JOptionPane.showOptionDialog(null, "Comment voulez-vous utiliser MAS Email ?", "MAS Email",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, modes, modes[1]);
    }
}
