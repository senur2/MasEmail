package fr.mas.email.app;

import fr.mas.email.contract.DraftResponse;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.MemoryProposal;
import fr.mas.email.contract.UserDiscussionAnswer;
import fr.mas.email.llm.ollama.OllamaLlmGateway;
import fr.mas.email.result.AgentResult;
import fr.mas.email.result.DiscussionPlan;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Success;
import fr.mas.email.orchestration.SuspendedDiscussion;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.ButtonGroup;
import javax.swing.JRadioButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

final class TestBench extends JFrame {
    private static final Color BACKGROUND = new Color(0xF3F6F5);
    private static final Color SURFACE = Color.WHITE;
    private static final Color INK = new Color(0x172C29);
    private static final Color MUTED = new Color(0x475D57);
    private static final Color LINE = new Color(0xD5E0DD);
    private static final Color ACCENT = new Color(0x12685B);
    private static final Color ERROR = new Color(0x9A3A2E);
    private static final Color WAITING = new Color(0x875A1D);
    private static final Font FONT = new Font("Segoe UI", Font.PLAIN, 14);
    private static final DateTimeFormatter RUN_DATE = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss");

    private final JsonCodec json;
    private final Path root;
    private final UserWorkspace workspace;
    private final fr.mas.email.mail.MailService mailService;
    private final MailboxPanel mailbox;
    private fr.mas.email.mail.ReceivedMail loadedMail;
    private fr.mas.email.mail.ReceivedMail runMail;
    private final JButton send = button("Envoyer la réponse validée", true);
    private final JButton simulateSend = button("Simuler l’envoi", false);
    private final JButton nextTurn = button("Tour suivant", false);
    private final JTextField testSubject = new JTextField(DemoScenario.SUBJECT);
    private fr.mas.email.mail.ReceivedMail testMail;
    private long historyRevision;
    private final JButton editEmail = button("Modifier l’email entrant", false);
    private boolean sendAttempted;
    private final JTextArea email = textArea(true);
    private final JTextArea threadHistory = textArea(false);
    private final JScrollPane historyPane = new JScrollPane(threadHistory);
    private final JTextArea draftBody = textArea(false);
    private final JTextField draftSubject = new JTextField();
    private final JTextArea feedback = textArea(true);
    private final JButton approve = button("Valider le brouillon", true);
    private final JButton correct = button("Corriger le brouillon", true);
    private final JPanel correctionForm = new JPanel(new BorderLayout(8, 0));
    private final JComboBox<String> mode = new JComboBox<>(
            new String[]{"Ollama local", "Simulation INF312", "Simulation clarification"});
    private final JTextField address;
    private final JComboBox<String> model = new JComboBox<>();
    private final JButton refreshModels = button("Actualiser", false);
    private boolean loadingModels;
    private final JButton example = button("Charger l’exemple", false);
    private final JButton run = button("Générer le brouillon", true);
    private final JButton openLog = button("Ouvrir le journal", false);
    private final JLabel summary = label("Prêt à lancer un test.", MUTED);
    private final JLabel accountHeading = label("", MUTED);
    private final JLabel logLocation = label("Aucun journal pour le moment", MUTED);
    private final EnumMap<ErrorPlan.Stage, JLabel> stages = new EnumMap<>(ErrorPlan.Stage.class);
    private Path logPath;
    private final JButton clarify = button("Répondre à la clarification", true);
    private final CardLayout outputLayout = new CardLayout();
    private final JPanel outputCards = new JPanel(outputLayout);
    private final JPanel clarificationForm = new ClarificationForm();
    private final List<JTextArea> answers = new java.util.ArrayList<>();
    private final JRadioButton session = new JRadioButton("Répondre sans enregistrer", true);
    private final JRadioButton common = new JRadioButton("Valider et enregistrer dans K-Commun");
    private final JRadioButton particular = new JRadioButton();
    private PipelineRunner runner;
    private SuspendedDiscussion pending;
    private boolean busy;
    private PipelineRunner.DraftReview review = new PipelineRunner.DraftReview(null, false, false, false);
    private int runEngine;
    private String runEndpoint;
    private String runModel;

    TestBench(JsonCodec json, Path root, String server, String modelTag) {
        this(json, root, server, modelTag, null, null);
    }

    TestBench(JsonCodec json, Path root, String server, String modelTag, UserWorkspace workspace,
              fr.mas.email.mail.MailService mailService) {
        super("MAS Email — Banc de test");
        this.json = json;
        this.root = root;
        this.workspace = workspace;
        this.mailService = mailService;
        this.mailbox = workspace == null ? null : new MailboxPanel(workspace, mailService, this::loadMail,
                value -> { busy = value; updateControls(); }, () -> {
                    if (loadedMail != null) loadMail(loadedMail);
                    setSummary("Connaissances enregistrées. Générez un nouveau brouillon pour les utiliser.", ACCENT);
                    updateControls();
                }, () -> {
                    loadMail(null);
                    setTitle("MAS Email — " + workspace.account().email());
                    accountHeading.setText("Votre assistant Gmail · " + workspace.account().email());
                    updateControls();
                });
        address = new JTextField(server, 18);
        model.setEditable(true);
        model.setSelectedItem(modelTag);
        refreshModels.addActionListener(event -> refreshModels());
        address.addActionListener(event -> refreshModels());
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent event) { refreshModels(); }
        });
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 610));
        setSize(1120, 710);
        setLocationRelativeTo(null);
        setContentPane(buildContent());

        email.setText(workspace == null ? DemoScenario.EMAIL : "");
        if (workspace != null) {
            setTitle("MAS Email — " + workspace.account().email());
            setSummary("Connectez Gmail, choisissez un email puis générez sa réponse.", MUTED);
            setSize(1280, 850);
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent e) { mailbox.clearCredential(); }
                @Override public void windowClosed(java.awt.event.WindowEvent e) { mailbox.clearCredential(); }
            });
        }
        email.setCaretPosition(0);
        mode.addActionListener(event -> {
            updateControls();
            if (workspace == null) previewTestHistory();
        });
        testSubject.addActionListener(event -> previewTestHistory());
        example.addActionListener(event -> {
            testSubject.setText(DemoScenario.SUBJECT);
            email.setText(DemoScenario.EMAIL);
            email.setCaretPosition(0);
            email.requestFocusInWindow();
            previewTestHistory();
        });
        nextTurn.addActionListener(event -> prepareNextTurn());
        run.addActionListener(event -> startRun());
        openLog.addActionListener(event -> openLog());
        clarify.addActionListener(event -> clarify());
        correct.addActionListener(event -> correct());
        approve.addActionListener(event -> approve());
        send.addActionListener(event -> send());
        simulateSend.addActionListener(event -> simulateSend());
        editEmail.addActionListener(event -> editEmail());
        updateControls();
        if (workspace == null) previewTestHistory();
    }

    private void refreshModels() {
        String endpoint = address.getText().strip();
        if (!refreshModels.isEnabled() || endpoint.isEmpty()) return;
        loadingModels = true;
        refreshModels.setToolTipText("Recherche des modèles installés…");
        updateControls();
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                return OllamaModelList.load(endpoint);
            }

            @Override protected void done() {
                loadingModels = false;
                updateControls();
                if (!endpoint.equals(address.getText().strip())) return;
                try {
                    var models = get();
                    Object selection = model.getEditor().getItem();
                    model.setModel(new javax.swing.DefaultComboBoxModel<>(models.toArray(String[]::new)));
                    if (models.contains(selection) || models.isEmpty() || busy || pending != null) {
                        model.setSelectedItem(selection);
                    }
                    refreshModels.setToolTipText(models.isEmpty() ? "Aucun modèle installé" : "Actualiser les modèles installés");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    refreshModels.setToolTipText("Liste indisponible. Vérifiez qu’Ollama fonctionne, puis actualisez.");
                }
            }
        }.execute();
    }

    private JPanel buildContent() {
        var content = new JPanel(new BorderLayout(0, 16));
        content.setBackground(BACKGROUND);
        content.setBorder(new EmptyBorder(20, 24, 18, 24));

        var header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        var title = label("MAS Email", INK);
        title.setFont(FONT.deriveFont(Font.BOLD, 30f));
        header.add(title);
        accountHeading.setText(workspace == null ? "Banc de test du pipeline de réponse" : "Votre assistant Gmail · " + workspace.account().email());
        header.add(accountHeading);
        header.add(Box.createVerticalStrut(12));
        var settings = new JPanel(new GridLayout(1, 3, 14, 0));
        settings.setOpaque(false);
        styleField(address);
        model.setFont(FONT);
        mode.setFont(FONT);
        mode.setBackground(SURFACE);
        settings.add(field("Moteur", mode));
        settings.add(field("Adresse Ollama", address));
        var modelSelector = new JPanel(new BorderLayout(6, 0));
        modelSelector.add(model, BorderLayout.CENTER);
        modelSelector.add(refreshModels, BorderLayout.EAST);
        settings.add(field("Modèle", modelSelector));
        settings.setAlignmentX(LEFT_ALIGNMENT);
        header.add(settings);
        content.add(header, BorderLayout.NORTH);

        var work = new JPanel(new BorderLayout(0, 12));
        work.setOpaque(false);
        var mailInput = new JPanel(new BorderLayout(0, 8));
        mailInput.add(mailbox == null ? field("Objet du fil de test", testSubject) : mailbox, BorderLayout.NORTH);
        var message = new JPanel(new BorderLayout(0, 8));
        threadHistory.setRows(5);
        historyPane.setBorder(BorderFactory.createTitledBorder("Historique du fil"));
        historyPane.setVisible(false);
        message.add(historyPane, BorderLayout.NORTH);
        message.add(scroll(email), BorderLayout.CENTER);
        mailInput.add(message, BorderLayout.CENTER);
        var input = card("Email entrant", mailInput);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(example);
        actions.add(workspace == null ? nextTurn : editEmail);
        actions.add(run);
        input.add(actions, BorderLayout.SOUTH);

        draftSubject.setEditable(false);
        styleField(draftSubject);
        var output = new JPanel(new BorderLayout(0, 10));
        output.setOpaque(false);
        output.add(field("Objet", draftSubject), BorderLayout.NORTH);
        output.add(scroll(draftBody), BorderLayout.CENTER);
        feedback.setRows(3);
        correctionForm.setOpaque(false);
        correctionForm.add(field("Correction ou information à ajouter", scroll(feedback)), BorderLayout.CENTER);
        var draftActions = new JPanel(workspace == null ? new FlowLayout(FlowLayout.RIGHT, 8, 0) : new GridLayout(3, 1, 0, 5));
        draftActions.setOpaque(false);
        draftActions.add(approve);
        draftActions.add(correct);
        draftActions.add(workspace == null ? simulateSend : send);
        correctionForm.add(draftActions, BorderLayout.SOUTH);
        output.add(correctionForm, BorderLayout.SOUTH);
        outputCards.add(card("Brouillon à relire", output), "draft");
        clarificationForm.setLayout(new BoxLayout(clarificationForm, BoxLayout.Y_AXIS));
        clarificationForm.setOpaque(false);
        var scopes = new ButtonGroup();
        for (var scope : List.of(session, common, particular)) {
            scopes.add(scope);
            scope.setAlignmentX(LEFT_ALIGNMENT);
        }
        var response = card("Clarification requise", new JScrollPane(clarificationForm));
        response.add(clarify, BorderLayout.SOUTH);
        outputCards.add(response, "clarification");
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, input, outputCards);
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setBackground(BACKGROUND);
        split.setDividerSize(12);
        work.add(split, BorderLayout.CENTER);

        var stageStrip = new JPanel(new GridLayout(1, 5, 8, 0));
        stageStrip.setOpaque(false);
        for (var stage : List.of(ErrorPlan.Stage.PARSER, ErrorPlan.Stage.DECISION, ErrorPlan.Stage.WRITER,
                ErrorPlan.Stage.SCRIBE, ErrorPlan.Stage.CORRECTING)) {
            var status = label("En attente", MUTED);
            stages.put(stage, status);
            stageStrip.add(card(stageLabel(stage), status));
        }
        work.add(stageStrip, BorderLayout.SOUTH);
        content.add(work, BorderLayout.CENTER);

        var footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);
        var footerText = new JPanel();
        footerText.setOpaque(false);
        footerText.setLayout(new BoxLayout(footerText, BoxLayout.Y_AXIS));
        footerText.add(summary);
        footerText.add(logLocation);
        footer.add(footerText, BorderLayout.CENTER);
        footer.add(openLog, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);
        return content;
    }

    private void startRun() {
        int engine = mode.getSelectedIndex();
        boolean simulation = engine != 0;
        String message = email.getText();
        if (workspace != null && loadedMail == null) {
            setSummary("Ouvrez d’abord un email de votre boîte Gmail.", WAITING);
            return;
        }
        if (message.isBlank()) {
            setSummary("Collez le texte d’un email pour lancer le test.", ERROR);
            email.requestFocusInWindow();
            return;
        }
        if (simulation && !DemoScenario.EMAIL.equals(message) && !DemoScenario.FOLLOW_UP.equals(message)) {
            setSummary("La simulation utilise l’exemple INF312 ou son message de suivi.", WAITING);
            return;
        }
        String subject = testSubject.getText().strip();
        if (workspace == null && subject.isEmpty()) {
            setSummary("Indiquez un objet pour identifier le fil de test.", WAITING);
            testSubject.requestFocusInWindow();
            return;
        }
        String endpoint = address.getText().strip();
        String modelTag = java.util.Objects.toString(model.getEditor().getItem(), "").strip();
        if (!simulation && (endpoint.isEmpty() || modelTag.isEmpty())) {
            setSummary("Renseignez l’adresse Ollama et le nom du modèle.", ERROR);
            return;
        }

        var name = LocalDateTime.now().format(RUN_DATE) + "-" + UUID.randomUUID().toString().substring(0, 8);
        var directory = (workspace == null ? root : workspace.runsDirectory()).resolve(name);
        logPath = directory.resolve(PipelineRunner.LOG_NAME);
        pending = null;
        review = new PipelineRunner.DraftReview(null, false, false, false);
        feedback.setText("");
        runEngine = engine;
        runEndpoint = endpoint;
        runModel = modelTag;
        if (workspace == null && (testMail == null || !testMail.summary().subject().equals(subject)
                || !testMail.body().equals(message))) {
            testMail = newTestMail(subject, message);
        }
        runMail = workspace == null ? testMail : loadedMail;
        showThreadHistory(runMail, historyDirectory(simulation));
        sendAttempted = false;
        outputLayout.show(outputCards, "draft");
        runner = workspace == null
                ? new PipelineRunner(json, root.resolveSibling(simulation ? "simulation-knowledge" : "knowledge"),
                        true, historyDirectory(simulation), runMail)
                : new PipelineRunner(json, workspace.knowledgeDirectory(), false,
                        workspace.runsDirectory().resolve("threads"), runMail);
        busy = true;
        stages.values().forEach(status -> {
            status.setText("En attente");
            status.setForeground(MUTED);
        });
        draftSubject.setText("");
        draftBody.setText("");
        setSummary("Démarrage du test…", ACCENT);
        logLocation.setText("Journal · " + name + "/" + PipelineRunner.LOG_NAME);
        logLocation.setToolTipText(logPath.toString());
        updateControls();

        execute(directory, null, message, null);
    }

    private void editEmail() {
        if (busy || pending != null || loadedMail == null) return;
        var original = loadedMail;
        var subject = new JTextField(original.summary().subject());
        var body = textArea(true);
        body.setText(original.body());
        body.setCaretPosition(0);
        var form = new JPanel(new BorderLayout(8, 8));
        form.add(field("Objet de l’email entrant", subject), BorderLayout.NORTH);
        form.add(field("Texte de l’email entrant", scroll(body)), BorderLayout.CENTER);
        form.add(new JLabel("Modification locale : le message dans Gmail et l’adresse de réponse restent inchangés."), BorderLayout.SOUTH);
        form.setPreferredSize(new Dimension(720, 400));
        while (JOptionPane.showConfirmDialog(this, form, "Modifier l’email entrant",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            if (body.getText().isBlank()) {
                JOptionPane.showMessageDialog(this, "Le texte de l’email ne peut pas être vide.");
                continue;
            }
            if (subject.getText().equals(original.summary().subject()) && body.getText().equals(original.body())) return;
            var summary = original.summary();
            var revised = new fr.mas.email.mail.MailSummary(summary.uid(), summary.uidValidity(), summary.from(),
                    subject.getText(), summary.receivedAt());
            loadMail(new fr.mas.email.mail.ReceivedMail(original.accountEmail(), revised, original.replyTo(),
                    original.messageId(), body.getText(), original.threadId()));
            setSummary("Email modifié localement. Générez puis validez un nouveau brouillon.", ACCENT);
            updateControls();
            return;
        }
    }

    private void loadMail(fr.mas.email.mail.ReceivedMail message) {
        loadedMail = message;
        runMail = null;
        review = new PipelineRunner.DraftReview(null, false, false, false);
        runner = null; pending = null; logPath = null; sendAttempted = false;
        email.setText(message == null ? "" : message.parserText()); email.setCaretPosition(0);
        showThreadHistory(message, message == null ? null : historyDirectory(false));
        draftSubject.setText(""); draftBody.setText(""); feedback.setText("");
        logLocation.setText("Aucun journal pour cet email");
        outputLayout.show(outputCards, "draft");
        stages.values().forEach(status -> { status.setText("En attente"); status.setForeground(MUTED); });
        setSummary(message == null ? "Compte changé. Connectez Gmail et ouvrez un email." : "Email chargé. Vous pouvez générer le brouillon.", ACCENT);
    }

    private void prepareNextTurn() {
        testMail = null;
        loadMail(null);
        if (mode.getSelectedIndex() != 0) email.setText(DemoScenario.FOLLOW_UP);
        setSummary("Tour suivant · conservez l’objet pour continuer le fil.", ACCENT);
        previewTestHistory();
        updateControls();
        email.requestFocusInWindow();
    }

    private Path historyDirectory(boolean simulation) {
        return workspace == null ? root.resolve("threads").resolve(simulation ? "simulation" : "ollama")
                : workspace.runsDirectory().resolve("threads");
    }

    private static fr.mas.email.mail.ReceivedMail newTestMail(String subject, String body) {
        String sender = "nina@example.test";
        long uid = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        var summary = new fr.mas.email.mail.MailSummary(uid, 1, sender, subject, java.time.Instant.now());
        return new fr.mas.email.mail.ReceivedMail("assistant@example.test", summary, sender, "", body);
    }

    private void previewTestHistory() {
        String subject = testSubject.getText().strip();
        var mail = subject.isEmpty() ? null : testMail != null && testMail.summary().subject().equals(subject)
                ? testMail : newTestMail(subject, "");
        showThreadHistory(mail, historyDirectory(mode.getSelectedIndex() != 0));
    }

    private void showThreadHistory(fr.mas.email.mail.ReceivedMail mail, Path directory) {
        long revision = ++historyRevision;
        threadHistory.setText("");
        historyPane.setVisible(false);
        if (mail == null) return;
        new SwingWorker<fr.mas.email.world.ThreadHistoryDigest, Void>() {
            @Override protected fr.mas.email.world.ThreadHistoryDigest doInBackground() throws IOException {
                var memory = new fr.mas.email.world.ConversationMemoryService(new fr.mas.email.world.FileWorldStore(directory, json));
                return memory.loadDigest(memory.resolveThreadId(mail), mail.emailId());
            }

            @Override protected void done() {
                if (historyRevision != revision) return;
                try {
                    var turns = get().recentTurns();
                    if (turns.isEmpty()) return;
                    var summary = new StringBuilder();
                    for (var turn : turns) {
                        summary.append("Tour ").append(turn.turnIndex()).append(" · ").append(turn.receivedAt()).append('\n');
                        if (!turn.requestedInformation().isEmpty()) {
                            summary.append("Questions : ").append(String.join(", ", turn.requestedInformation())).append('\n');
                        }
                        summary.append("Brouillon : ").append(turn.generatedDraftSubject()).append(" — ")
                                .append(turn.generatedDraftSnippet()).append('\n');
                        if (turn.finalSentBodySnippet() != null) {
                            summary.append(workspace == null ? "Réponse simulée : " : "Réponse envoyée : ")
                                    .append(turn.finalSentBodySnippet()).append('\n');
                        }
                        summary.append('\n');
                    }
                    threadHistory.setText(summary.toString());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    threadHistory.setText("Impossible de charger l’historique de ce fil.");
                }
                threadHistory.setCaretPosition(0);
                historyPane.setVisible(true);
                historyPane.getParent().revalidate();
            }
        }.execute();
    }

    private void send() {
        if (busy || !review.approved() || pending != null || runMail == null || sendAttempted) return;
        if (!mailbox.ensureCredential()) return;
        var displayed = review.draft();
        var preview = textArea(false);
        preview.setText("À : " + runMail.replyTo() + "\nObjet : " + displayed.subject() + "\n\n" + displayed.body());
        preview.setCaretPosition(0);
        var content = new JScrollPane(preview); content.setPreferredSize(new Dimension(640, 360));
        int choice = JOptionPane.showConfirmDialog(this, content, "Confirmer l’envoi depuis " + workspace.account().email(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        var original = runMail;
        var directory = logPath.getParent();
        var account = workspace.account();
        char[] secret = mailbox.copyCredential();
        busy = true; sendAttempted = true;
        setSummary("Envoi de la réponse validée…", ACCENT); updateControls();
        new SwingWorker<fr.mas.email.mail.MailDelivery, Void>() {
            @Override protected fr.mas.email.mail.MailDelivery doInBackground() throws IOException {
                try { return runner.send(account, original, displayed, secret, mailService, directory); }
                finally { java.util.Arrays.fill(secret, '\0'); }
            }
            @Override protected void done() {
                busy = false;
                try { get(); setSummary("Réponse envoyée à " + original.replyTo() + ".", ACCENT); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); setSummary("Envoi interrompu. Vérifiez Gmail.", ERROR); }
                catch (ExecutionException e) { setSummary("Envoi non confirmé. Vérifiez les messages envoyés dans Gmail.", ERROR); }
                finally { updateControls(); }
            }
        }.execute();
    }

    private void correct() {
        if (busy || !review.canCorrect() || pending != null) return;
        String instruction = feedback.getText().strip();
        if (instruction.isEmpty()) {
            setSummary("Indiquez la correction souhaitée.", WAITING);
            feedback.requestFocusInWindow();
            return;
        }
        busy = true;
        updateControls();
        execute(logPath.getParent(), null, null, instruction);
    }

    private void simulateSend() {
        if (workspace != null || busy || !review.approved() || pending != null || sendAttempted) return;
        var draft = review.draft();
        var directory = logPath.getParent();
        busy = true;
        updateControls();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws IOException {
                runner.simulateSend(draft, directory);
                return null;
            }

            @Override protected void done() {
                busy = false;
                try {
                    get();
                    sendAttempted = true;
                    setSummary("Envoi simulé enregistré. Cliquez sur Tour suivant pour continuer le fil.", ACCENT);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    setSummary("Impossible d’enregistrer l’envoi simulé.", ERROR);
                } finally {
                    updateControls();
                }
            }
        }.execute();
    }

    private void approve() {
        if (busy || !review.canApprove() || pending != null) return;
        var displayed = review.draft();
        var directory = logPath.getParent();
        busy = true;
        updateControls();
        new SwingWorker<PipelineRunner.DraftReview, Void>() {
            @Override protected PipelineRunner.DraftReview doInBackground() throws IOException {
                runner.approve(displayed, directory);
                return runner.review(directory);
            }

            @Override protected void done() {
                busy = false;
                try {
                    review = get();
                    setSummary("Brouillon validé. Votre validation est enregistrée dans le journal.", ACCENT);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    setSummary("Validation interrompue.", ERROR);
                } catch (ExecutionException e) {
                    setSummary("Impossible d’enregistrer la validation. Vous pouvez réessayer.", ERROR);
                } finally {
                    updateControls();
                }
            }
        }.execute();
    }

    private void execute(Path directory, UserDiscussionAnswer answer, String message, String correction) {
        if (workspace != null) runner.useKnowledgeDirectory(workspace.knowledgeDirectory());
        new SwingWorker<AgentResult<DraftResponse>, PipelineRunner.Progress>() {
            private SuspendedDiscussion nextDiscussion;
            private PipelineRunner.DraftReview nextReview;

            @Override protected AgentResult<DraftResponse> doInBackground() throws IOException {
                AgentResult<DraftResponse> result;
                if (runEngine != 0) {
                    result = correction != null ? runner.correct(correction, DemoScenario.correction(json), directory, this::publish)
                            : answer == null ? runner.run(message, DemoScenario.benchGateway(json, runEngine == 2,
                                    DemoScenario.FOLLOW_UP.equals(message)),
                            runEngine == 2 ? "Simulation clarification" : "Simulation INF312", directory, this::publish)
                            : runner.resume(answer, DemoScenario.resumed(json, answer.answer()), directory, this::publish);
                } else {
                    try (var gateway = new OllamaLlmGateway(URI.create(runEndpoint), runModel, json)) {
                        result = correction != null ? runner.correct(correction, gateway, directory, this::publish)
                                : answer == null ? runner.run(message, gateway, runModel, directory, this::publish)
                                : runner.resume(answer, gateway, directory, this::publish);
                    }
                }
                if (result instanceof DiscussionPlan<?>) {
                    nextDiscussion = runner.pending(directory);
                }
                nextReview = runner.review(directory);
                return result;
            }

            @Override protected void process(List<PipelineRunner.Progress> events) {
                events.forEach(TestBench.this::updateStage);
                updateControls();
            }

            @Override protected void done() {
                busy = false;
                try {
                    var result = get();
                    pending = nextDiscussion;
                    review = nextReview;
                    if (correction != null && result instanceof Success<?>) feedback.setText("");
                    showResult(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    setSummary("Test interrompu.", ERROR);
                } catch (ExecutionException e) {
                    review = new PipelineRunner.DraftReview(review.draft(), false, false, false);
                    var failure = e.getCause();
                    String detail = failure instanceof IOException ? "Écriture du journal, de l’état ou de la mémoire impossible."
                            : answer != null ? "Reprise impossible. Consultez le journal."
                            : failure instanceof IllegalArgumentException ? "Configuration Ollama invalide."
                            : "Le test a rencontré un incident interne.";
                    setSummary(detail, ERROR);
                    stages.values().stream().filter(status -> status.getText().startsWith("En cours")).forEach(status -> {
                        status.setText("Erreur");
                        status.setForeground(ERROR);
                    });
                } finally {
                    if (!Files.isRegularFile(logPath)) logLocation.setText("Journal indisponible");
                    updateControls();
                }
            }
        }.execute();
    }

    private void showClarification() {
        var discussion = pending.request().payload();
        var context = pending.context();
        clarificationForm.removeAll();
        answers.clear();
        for (int i = 0; i < discussion.questions().size(); i++) {
            var question = textArea(false);
            question.setText(discussion.questions().get(i)
                    + (context.keys().isEmpty() ? "" : "\nClé : " + context.keys().get(i)));
            question.setRows(2);
            question.setAlignmentX(LEFT_ALIGNMENT);
            var field = textArea(true);
            field.setRows(2);
            if (context.isMemoryCorrection()) {
                field.setText(context.correctionPlan().memoryProposals().get(i).value());
            }
            answers.add(field);
            clarificationForm.add(question);
            var reply = field("Votre réponse", scroll(field));
            reply.setAlignmentX(LEFT_ALIGNMENT);
            clarificationForm.add(reply);
            clarificationForm.add(Box.createVerticalStrut(8));
        }
        session.setSelected(true);
        session.setText(context.isMemoryCorrection() ? "Valider pour cette session" : "Répondre sans enregistrer");
        clarificationForm.add(session);
        if (discussion.memoryProposal() == MemoryProposal.ASK_SAVE_SCOPE) {
            particular.setText("Valider et enregistrer dans K-Particulier (" + context.route().selectedAgentId() + ")");
            clarificationForm.add(common);
            clarificationForm.add(particular);
        }
        outputLayout.show(outputCards, "clarification");
        clarify.setText(context.isMemoryCorrection() ? "Valider et reprendre" : "Répondre à la clarification");
        clarificationForm.revalidate();
        clarificationForm.repaint();
        answers.getFirst().requestFocusInWindow();
    }

    private void clarify() {
        if (busy || pending == null) return;
        if (answers.stream().anyMatch(field -> field.getText().isBlank())) {
            setSummary("Renseignez chaque réponse avant de reprendre.", WAITING);
            return;
        }
        var context = pending.context();
        String value = answers.getFirst().getText().strip();
        if (answers.size() > 1) {
            var values = new LinkedHashMap<String, String>();
            for (int i = 0; i < answers.size(); i++) values.put(context.keys().get(i), answers.get(i).getText().strip());
            value = json.write(values);
        }
        var choice = common.isSelected() ? UserDiscussionAnswer.MemoryChoice.K_COMMUN
                : particular.isSelected() ? UserDiscussionAnswer.MemoryChoice.K_PERSO : UserDiscussionAnswer.MemoryChoice.NONE;
        var answer = new UserDiscussionAnswer(pending.request().metadata().messageId(),
                UserDiscussionAnswer.Action.ANSWER_INFORMATION, value, choice,
                particular.isSelected() ? context.route().selectedAgentId() : null, !session.isSelected());
        busy = true;
        setSummary("Reprise du traitement…", ACCENT);
        updateControls();
        execute(logPath.getParent(), answer, null, null);
    }

    private void updateStage(PipelineRunner.Progress progress) {
        var status = stages.get(progress.stage());
        String writer = progress.stage() == ErrorPlan.Stage.WRITER ? " · " + progress.agentId() : "";
        switch (progress.status()) {
            case RUNNING -> {
                status.setText("En cours" + writer);
                status.setForeground(ACCENT);
                setSummary(stageLabel(progress.stage()) + " en cours…", ACCENT);
            }
            case COMPLETE -> {
                status.setText("Terminé" + writer);
                status.setForeground(ACCENT);
            }
            case WAITING_USER -> {
                status.setText("Clarification");
                status.setForeground(WAITING);
            }
            case FAILED -> {
                status.setText("Erreur");
                status.setForeground(ERROR);
            }
        }
    }

    private void showResult(AgentResult<DraftResponse> result) {
        switch (result) {
            case Success(var draft, var rawIgnored0) -> {
                outputLayout.show(outputCards, "draft");
                draftSubject.setText(draft.subject());
                draftSubject.setCaretPosition(0);
                draftBody.setText(draft.body());
                setSummary("Brouillon prêt à relire.", ACCENT);
            }
            case DiscussionPlan<DraftResponse> ignored -> {
                showClarification();
                setSummary(pending.context().isMemoryCorrection()
                        ? "Vérifiez les informations proposées, choisissez leur portée puis validez."
                        : "Répondez à la clarification pour reprendre le traitement.", WAITING);
            }
            case ErrorPlan<DraftResponse> error -> {
                outputLayout.show(outputCards, "draft");
                if (review.draft() == null) {
                    draftSubject.setText("Test arrêté");
                    draftBody.setText(error.code() + "\n\n" + error.observedProblem());
                } else {
                    draftSubject.setText(review.draft().subject());
                    draftBody.setText(review.draft().body());
                }
                setSummary(error.code() + " · " + (error.code() == fr.mas.email.result.ErrorCode.MAX_CORRECTIONS_REACHED
                        ? "Trois corrections ont déjà été demandées. Vous pouvez valider le brouillon actuel."
                        : review.canCorrect() ? "Correction interrompue. Modifiez votre demande puis relancez."
                        : "Consultez le journal."), ERROR);
            }
            case fr.mas.email.result.CorrectionRequest<DraftResponse> ignored ->
                    throw new IllegalStateException("Unresolved agent correction");
        }
        draftBody.setCaretPosition(0);
    }

    private void setSummary(String text, Color color) {
        summary.setText(text);
        summary.setForeground(color);
    }

    private void updateControls() {
        boolean ollama = mode.getSelectedIndex() == 0;
        mode.setEnabled(workspace == null && !busy && pending == null);
        address.setEnabled(!busy && ollama && pending == null);
        model.setEnabled(!busy && ollama && pending == null);
        refreshModels.setEnabled(!busy && ollama && pending == null && !loadingModels);
        email.setEditable(workspace == null && !busy);
        example.setVisible(workspace == null);
        example.setEnabled(!busy);
        nextTurn.setEnabled(!busy && pending == null && runMail != null);
        testSubject.setEnabled(!busy && pending == null);
        run.setEnabled(!busy && (workspace == null || loadedMail != null && pending == null));
        if (mailbox != null) mailbox.controls(!busy && pending == null);
        editEmail.setEnabled(!busy && pending == null && loadedMail != null);
        send.setEnabled(!busy && review.approved() && pending == null && runMail != null && !sendAttempted);
        simulateSend.setEnabled(workspace == null && !busy && review.approved() && pending == null && !sendAttempted);
        openLog.setEnabled(logPath != null && Files.isRegularFile(logPath));
        clarify.setEnabled(!busy && pending != null);
        correctionForm.setVisible(review.draft() != null);
        feedback.setToolTipText(runEngine == 0 ? null : "La simulation applique une correction de style prédéfinie.");
        feedback.setEditable(!busy);
        correct.setEnabled(!busy && review.canCorrect() && pending == null);
        approve.setText(review.approved() ? "Brouillon validé" : "Valider le brouillon");
        approve.setEnabled(!busy && review.canApprove() && pending == null);
        answers.forEach(field -> field.setEditable(!busy));
        for (var scope : List.of(session, common, particular)) scope.setEnabled(!busy);
    }

    private void openLog() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(logPath.toFile());
                return;
            }
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            // The path remains useful when no editor can be opened.
        }
        JOptionPane.showMessageDialog(this, logPath.toString(), "Emplacement du journal", JOptionPane.INFORMATION_MESSAGE);
    }

    private static String stageLabel(ErrorPlan.Stage stage) {
        return switch (stage) {
            case PARSER -> "Analyse";
            case DECISION -> "Routage";
            case WRITER -> "Plan";
            case SCRIBE -> "Rédaction";
            case CORRECTING -> "Correction";
            default -> stage.name();
        };
    }

    private static JPanel field(String text, javax.swing.JComponent component) {
        var panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        var caption = label(text, MUTED);
        caption.setLabelFor(component);
        panel.add(caption, BorderLayout.NORTH);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private static final class ClarificationForm extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 16);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static JPanel card(String title, java.awt.Component body) {
        var panel = new JPanel(new BorderLayout(0, 9));
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                new EmptyBorder(12, 14, 12, 14)));
        var heading = label(title, INK);
        heading.setFont(FONT.deriveFont(Font.BOLD, 15f));
        panel.add(heading, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private static JScrollPane scroll(JTextArea area) {
        var scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(LINE));
        return scroll;
    }

    private static JLabel label(String text, Color color) {
        var label = new JLabel(text);
        label.setFont(FONT);
        label.setForeground(color);
        return label;
    }

    private static JTextArea textArea(boolean editable) {
        var area = new JTextArea(9, 30);
        area.setFont(FONT);
        area.setForeground(INK);
        area.setBackground(SURFACE);
        area.setMargin(new java.awt.Insets(10, 11, 10, 11));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(editable);
        return area;
    }

    private static void styleField(JTextField field) {
        field.setFont(FONT);
        field.setForeground(INK);
        field.setBackground(SURFACE);
        field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
                new EmptyBorder(7, 9, 7, 9)));
    }

    private static JButton button(String text, boolean primary) {
        var button = new JButton(text);
        button.setFont(FONT.deriveFont(Font.BOLD));
        button.setForeground(primary ? Color.WHITE : INK);
        button.setBackground(primary ? ACCENT : SURFACE);
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(primary ? ACCENT : LINE),
                new EmptyBorder(8, 12, 8, 12)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }
}
