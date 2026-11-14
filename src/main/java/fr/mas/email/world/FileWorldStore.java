package fr.mas.email.world;

import fr.mas.email.contract.Checks;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.orchestration.ExecutionTrace;
import fr.mas.email.orchestration.DraftApproval;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/** Trusted composition root only. Never pass this allocator to an agent or an LLM. */
public final class FileWorldStore {
    private final Path root;
    private final JsonCodec json;

    public FileWorldStore(Path root, JsonCodec json) throws IOException {
        Files.createDirectories(Checks.required(root, "root"));
        this.root = root.toRealPath();
        this.json = Checks.required(json, "json");
    }

    /** Hand this capability only to OrchestratorAgent. */
    public WorldRepository<SharedWorld> sharedWorld() {
        return new FileWorldRepository<>(root.resolve("shared.json"), SharedWorld.class,
                json, true, world -> Checks.required(world, "world"));
    }

    public WorldRepository<ExecutionTrace> executionTrace() {
        return new FileWorldRepository<>(root.resolve("execution.json"), ExecutionTrace.class,
                json, true, trace -> Checks.required(trace, "trace"));
    }

    public WorldRepository<DraftApproval> draftApproval() {
        return new FileWorldRepository<>(root.resolve("draft-approval.json"), DraftApproval.class,
                json, true, approval -> Checks.required(approval, "approval"));
    }

    public WorldRepository<fr.mas.email.mail.UserConfiguration> userConfiguration() {
        return new FileWorldRepository<>(root.resolve("account.json"), fr.mas.email.mail.UserConfiguration.class,
                json, true, value -> Checks.required(value, "user configuration"));
    }

    public WorldRepository<fr.mas.email.mail.MailDelivery> mailDelivery() {
        return new FileWorldRepository<>(root.resolve("mail-delivery.json"), fr.mas.email.mail.MailDelivery.class,
                json, true, value -> Checks.required(value, "mail delivery"));
    }

    /** Writable capabilities are reserved for the orchestration and storage layers. */
    public WorldRepository<MiniWorld> miniWorld(String ownerAgentId, boolean writable) {
        Checks.text(ownerAgentId, "ownerAgentId");
        // Hex encoding avoids traversal, reserved Windows filenames, and case-insensitive collisions.
        String filename = "mini-" + HexFormat.of().formatHex(ownerAgentId.getBytes(StandardCharsets.UTF_8)) + ".json";
        if (filename.length() > 240) throw new IllegalArgumentException("agent ID is too long for storage");
        return new FileWorldRepository<>(root.resolve(filename), MiniWorld.class, json, writable, world -> {
            Checks.required(world, "world");
            if (!ownerAgentId.equals(world.ownerAgentId())) {
                throw new SecurityException("MiniWorld ownership mismatch");
            }
        });
    }
}
