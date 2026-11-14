package fr.mas.email.app;

import fr.mas.email.mail.MailAccount;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.List;

/** First-run setup and explicit editing of the user's six knowledge spaces. */
final class SetupDialog extends JDialog {
    private final JTextField email = new JTextField(28);
    private final JTextField name = new JTextField(28);
    private final Map<String, JTable> tables = new LinkedHashMap<>();
    private boolean saved;

    static boolean show(Window owner, UserWorkspace workspace) throws IOException {
        var dialog = new SetupDialog(owner, workspace);
        dialog.setVisible(true);
        return dialog.saved;
    }

    SetupDialog(Window owner, UserWorkspace workspace) throws IOException {
        super(owner, workspace.configured() ? "Mon compte et mes connaissances" : "Bienvenue dans MAS Email", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        var content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        var identity = new JPanel(new GridLayout(0, 2, 8, 8));
        identity.add(new JLabel("Adresse du compte Gmail")); identity.add(email);
        identity.add(new JLabel("Votre nom (facultatif)")); identity.add(name);
        if (workspace.configured()) {
            email.setText(workspace.account().email());
            email.setEditable(false);
            name.setText(workspace.account().displayName());
        }
        var top = new JPanel(new BorderLayout(8, 8));
        top.add(identity, BorderLayout.NORTH);
        var help = new JTextArea("Renseignez uniquement des informations vérifiées. Chaque espace peut rester vide.\n"
                + "Précisez le cours ou l’événement concerné : « date publication notes INF312 » → « 12 décembre 2026 ».\n"
                + "Le commun est partagé avec tous les Writers ; les autres espaces restent propres au profil choisi.\n"
                + "La connexion Gmail utilise un mot de passe d’application Google conservé pendant la session.");
        help.setEditable(false); help.setOpaque(false); help.setLineWrap(true); help.setWrapStyleWord(true);
        top.add(help, BorderLayout.CENTER); content.add(top, BorderLayout.NORTH);
        var tabs = new JTabbedPane();
        var existing = workspace.knowledge();
        for (int i = 0; i < UserWorkspace.WORLDS.size(); i++) {
            String id = UserWorkspace.WORLDS.get(i);
            var model = new DefaultTableModel(new String[]{"Sujet précis de l’information", "Information vérifiée"}, 0);
            existing.get(id).entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .filter(entry -> !Set.of("nom_utilisateur", "adresse_email_utilisateur").contains(entry.getKey()))
                    .forEach(entry -> model.addRow(new Object[]{entry.getKey().replace('_', ' '), entry.getValue()}));
            if (model.getRowCount() == 0) model.addRow(new Object[]{"", ""});
            var table = new JTable(model);
            table.setRowHeight(30);
            table.putClientProperty("terminateEditOnFocusLost", true);
            table.getColumnModel().getColumn(1).setPreferredWidth(430);
            tables.put(id, table);
            var panel = new JPanel(new BorderLayout(6, 6));
            panel.add(new JScrollPane(table), BorderLayout.CENTER);
            var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
            var add = new JButton("Ajouter une information");
            add.addActionListener(event -> model.addRow(new Object[]{"", ""}));
            var remove = new JButton("Retirer la ligne");
            remove.addActionListener(event -> {
                if (table.isEditing()) table.getCellEditor().stopCellEditing();
                if (table.getSelectedRow() >= 0) model.removeRow(table.getSelectedRow());
            });
            buttons.add(add); buttons.add(remove); panel.add(buttons, BorderLayout.SOUTH);
            tabs.addTab(UserWorkspace.LABELS.get(i), panel);
        }
        content.add(tabs, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(8, 8));
        var status = new JLabel("Enregistrer confirme l’utilisation locale de ces connaissances.");
        var save = new JButton("Enregistrer mes informations");
        var cancel = new JButton("Annuler");
        cancel.addActionListener(event -> dispose());
        save.addActionListener(event -> {
            try {
                var account = new MailAccount(email.getText().strip(), name.getText().strip());
                var values = new LinkedHashMap<String, Map<String, String>>();
                tables.forEach((id, table) -> {
                    if (table.isEditing()) table.getCellEditor().stopCellEditing();
                    values.put(id, values((DefaultTableModel) table.getModel()));
                });
                save.setEnabled(false); cancel.setEnabled(false);
                setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
                status.setText("Enregistrement…");
                new SwingWorker<Void, Void>() {
                    @Override protected Void doInBackground() throws IOException { workspace.save(account, values); return null; }
                    @Override protected void done() {
                        try { get(); saved = true; dispose(); }
                        catch (Exception e) { status.setText("Enregistrement impossible. Vérifiez le dossier de données."); }
                        finally { save.setEnabled(true); cancel.setEnabled(true); setDefaultCloseOperation(DISPOSE_ON_CLOSE); }
                    }
                }.execute();
            } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
        });
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel); buttons.add(save);
        footer.add(status, BorderLayout.NORTH); footer.add(buttons, BorderLayout.SOUTH);
        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content); setSize(850, 570); setMinimumSize(new Dimension(700, 450)); setLocationRelativeTo(owner);
    }

    static Map<String, String> values(DefaultTableModel table) {
        var values = new LinkedHashMap<String, String>();
        for (int row = 0; row < table.getRowCount(); row++) {
            String label = Objects.toString(table.getValueAt(row, 0), "").strip();
            String value = Objects.toString(table.getValueAt(row, 1), "").strip();
            if (label.isEmpty() && value.isEmpty()) continue;
            String key = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                    .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
            if (!key.matches("[a-z][a-z0-9_]*") || value.isEmpty()) {
                throw new IllegalArgumentException("Chaque ligne doit contenir un sujet précis et une information.");
            }
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Sujet en double : " + label);
        }
        return values;
    }
}
