package fr.mas.email.app;

import fr.mas.email.mail.*;
import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.Callable;

/** UI for explicit mailbox operations; credentials live only for this window's session. */
final class MailboxPanel extends JPanel {
    private final UserWorkspace workspace;
    private final MailService service;
    private final Consumer<ReceivedMail> onRead;
    private final Consumer<Boolean> onBusy;
    private final JList<MailSummary> inbox = new JList<>(new DefaultListModel<>());
    private final JButton refresh = new JButton("Connexion / Actualiser Gmail");
    private final JButton open = new JButton("Ouvrir l’email sélectionné");
    private final JButton settings = new JButton("Modifier les connaissances");
    private final JButton disconnect = new JButton("Déconnexion");
    private final JButton changeAccount = new JButton("Changer de compte Gmail");
    private final JLabel status = new JLabel("Connectez-vous pour afficher vos 50 derniers emails.");
    private char[] credential;

    MailboxPanel(UserWorkspace workspace, MailService service, Consumer<ReceivedMail> onRead, Consumer<Boolean> onBusy,
                 Runnable onKnowledgeChanged, Runnable onAccountChanged) {
        super(new BorderLayout(6, 6));
        this.workspace = workspace; this.service = service; this.onRead = onRead; this.onBusy = onBusy;
        inbox.setVisibleRowCount(4);
        inbox.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Disable Swing's HTML renderer for untrusted email subjects and sender names.
        inbox.setCellRenderer((list, value, index, selected, focus) -> {
            var label = new JLabel(); label.putClientProperty("html.disable", true);
            label.setText(value == null ? "" : value.toString()); label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4)); return label;
        });
        var actions = new JPanel(new GridLayout(0, 2, 5, 5));
        actions.add(refresh); actions.add(open); actions.add(settings); actions.add(disconnect);
        actions.add(changeAccount);
        add(actions, BorderLayout.NORTH); add(new JScrollPane(inbox), BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
        refresh.addActionListener(event -> {
            if (!ensureCredential()) return;
            char[] secret = copyCredential();
            perform(() -> { try { return service.listInbox(workspace.account(), secret); }
                    finally { Arrays.fill(secret, '\0'); } }, values -> {
                var model = (DefaultListModel<MailSummary>) inbox.getModel(); model.clear(); model.addAll(values);
                status.setText(values.size() + " email(s) · " + workspace.account().email());
            });
        });
        open.addActionListener(event -> {
            var selected = inbox.getSelectedValue();
            if (selected == null) { status.setText("Sélectionnez un email dans la liste."); return; }
            if (!ensureCredential()) return;
            char[] secret = copyCredential();
            perform(() -> { try { return service.read(workspace.account(), secret, selected); }
                    finally { Arrays.fill(secret, '\0'); } }, mail -> {
                onRead.accept(mail); status.setText("Email chargé · Réponse prévue à " + mail.replyTo());
            });
        });
        settings.addActionListener(event -> {
            try {
                if (SetupDialog.show(SwingUtilities.getWindowAncestor(this), workspace)) onKnowledgeChanged.run();
            }
            catch (Exception e) { status.setText("Lecture des connaissances impossible."); }
        });
        disconnect.addActionListener(event -> clearCredential());
        changeAccount.addActionListener(event -> {
            String email = JOptionPane.showInputDialog(this,
                    "Adresse Gmail à utiliser (les connaissances de chaque compte restent séparées)",
                    "Changer de compte Gmail", JOptionPane.PLAIN_MESSAGE);
            if (email == null) return;
            try {
                var requested = new MailAccount(email.strip(), "");
                if (requested.email().equals(workspace.account().email())) return;
                perform(() -> { workspace.switchAccount(requested.email()); return requested.email(); }, selected -> {
                    clearCredential();
                    ((DefaultListModel<MailSummary>) inbox.getModel()).clear();
                    onAccountChanged.run();
                    status.setText("Compte actif : " + selected + " · Utilisez son mot de passe d’application.");
                });
            } catch (IllegalArgumentException e) { status.setText("Adresse email invalide."); }
        });
    }

    boolean ensureCredential() {
        if (credential != null) return true;
        var password = new JPasswordField(24);
        var panel = new JPanel(new GridLayout(0, 1, 5, 5));
        panel.add(new JLabel(workspace.account().email()));
        var label = new JLabel("Mot de passe d’application Google");
        label.setLabelFor(password);
        panel.add(label);
        panel.add(password);
        int choice = JOptionPane.showConfirmDialog(this, panel, "Connexion Gmail",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] entered = password.getPassword();
        password.setText("");
        if (choice != JOptionPane.OK_OPTION || entered.length == 0) {
            Arrays.fill(entered, '\0');
            return false;
        }
        credential = entered;
        return true;
    }

    char[] copyCredential() { return credential.clone(); }
    void clearCredential() {
        if (credential != null) Arrays.fill(credential, '\0');
        credential = null; status.setText("Déconnecté. Le mot de passe a été retiré de la session.");
    }
    void controls(boolean idle) { for (var button : List.of(refresh, open, settings, disconnect, changeAccount)) button.setEnabled(idle); inbox.setEnabled(idle); }

    private <T> void perform(Callable<T> action, Consumer<T> success) {
        onBusy.accept(true); status.setText("Connexion Gmail en cours…");
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return action.call(); }
            @Override protected void done() {
                try { success.accept(get()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); status.setText("Connexion interrompue."); }
                catch (java.util.concurrent.ExecutionException e) {
                    clearCredential();
                    status.setText(e.getCause() instanceof java.io.IOException ? e.getCause().getMessage() : "Lecture de cet email impossible.");
                } finally { onBusy.accept(false); }
            }
        }.execute();
    }
}
