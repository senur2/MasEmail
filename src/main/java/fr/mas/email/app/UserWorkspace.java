package fr.mas.email.app;

import fr.mas.email.contract.JsonCodec;
import fr.mas.email.mail.MailAccount;
import fr.mas.email.mail.UserConfiguration;
import fr.mas.email.world.FileWorldStore;
import fr.mas.email.world.MiniWorldService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** All persistent changes here originate in an explicitly saved setup form. */
final class UserWorkspace {
    static final List<String> WORLDS = List.of("COMMON_WORLD", "AcademicWriter", "ResearchWriter",
            "FinanceWriter", "PersonalWriter", "GenericWriter");
    static final List<String> LABELS = List.of("Commun", "Académique", "Recherche", "Finance", "Personnel", "Générique");
    private final Path root;
    private final JsonCodec json;
    private final FileWorldStore store;
    private UserConfiguration configuration;

    UserWorkspace(Path root, JsonCodec json) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.json = json;
        store = new FileWorldStore(this.root, json);
        configuration = store.userConfiguration().load().orElse(null);
    }

    boolean configured() { return configuration != null; }
    MailAccount account() { return Objects.requireNonNull(configuration).account(); }
    Path knowledgeDirectory() { return root.resolve("knowledge").resolve(configuration.knowledgeId().toString()); }
    Path runsDirectory() { return root.resolve("runs").resolve(accountKey(account().email())); }

    private static String accountKey(String email) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(email.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    void switchAccount(String email) throws IOException {
        var requested = new MailAccount(email, "");
        if (configured() && account().email().equals(requested.email())) return;
        var target = new FileWorldStore(root.resolve("accounts").resolve(accountKey(requested.email())), json);
        var next = target.userConfiguration().load().orElse(null);
        if (next != null) {
            if (!next.account().email().equals(requested.email())) throw new IOException("Compte enregistré incohérent.");
            var worlds = new FileWorldStore(root.resolve("knowledge").resolve(next.knowledgeId().toString()), json);
            for (String id : WORLDS) worlds.miniWorld(id, false).load().orElseThrow(
                    () -> new IOException("MiniWorld utilisateur manquant : " + id));
        }
        if (configured()) {
            new FileWorldStore(root.resolve("accounts").resolve(accountKey(account().email())), json)
                    .userConfiguration().save(configuration);
        }
        if (next == null) {
            var empty = new LinkedHashMap<String, Map<String, String>>();
            WORLDS.forEach(id -> empty.put(id, Map.of()));
            next = writeKnowledge(requested, empty);
        }
        store.userConfiguration().save(next);
        configuration = next;
    }

    Map<String, Map<String, String>> knowledge() throws IOException {
        var values = new LinkedHashMap<String, Map<String, String>>();
        if (!configured()) {
            WORLDS.forEach(id -> values.put(id, Map.of()));
        } else {
            var worlds = new FileWorldStore(knowledgeDirectory(), json);
            for (String id : WORLDS) {
                // Missing user data is an error, never a reason to fall back to demo knowledge.
                values.put(id, worlds.miniWorld(id, false).load().orElseThrow(
                        () -> new IOException("MiniWorld utilisateur manquant : " + id)).knowledge());
            }
        }
        return values;
    }

    void save(MailAccount account, Map<String, Map<String, String>> knowledge) throws IOException {
        if (!knowledge.keySet().equals(new HashSet<>(WORLDS))) throw new IllegalArgumentException("Les six MiniWorlds sont requis.");
        if (configured() && !account.email().equals(account().email())) {
            throw new IllegalArgumentException("Cette installation est liée au compte " + account().email() + ".");
        }
        var next = writeKnowledge(account, knowledge);
        store.userConfiguration().save(next);
        configuration = next;
    }

    private UserConfiguration writeKnowledge(MailAccount account, Map<String, Map<String, String>> knowledge) throws IOException {
        var next = new UserConfiguration(account, UUID.randomUUID());
        var directory = root.resolve("knowledge").resolve(next.knowledgeId().toString());
        var service = new MiniWorldService(new FileWorldStore(directory, json), false);
        var common = new LinkedHashMap<>(knowledge.get("COMMON_WORLD"));
        common.put("adresse_email_utilisateur", account.email());
        if (!account.displayName().isBlank()) common.put("nom_utilisateur", account.displayName());
        else common.remove("nom_utilisateur");
        service.persistToCommonKnowledge(common);
        for (String id : WORLDS.subList(1, WORLDS.size())) service.persistToAgentKnowledge(id, knowledge.get(id));
        // Publish only after all six files have been written. Previous configuration remains usable on failure.
        return next;
    }
}
