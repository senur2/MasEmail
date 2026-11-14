package fr.mas.email.world;

import fr.mas.email.contract.ContractException;
import fr.mas.email.contract.JsonCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.function.Consumer;

/** Local, single-process V1 storage. No unsafe fallback if atomic replacement is unavailable. */
final class FileWorldRepository<T> implements WorldRepository<T> {
    private final Path path;
    private final Class<T> type;
    private final JsonCodec json;
    private final boolean writable;
    private final Consumer<T> validate;

    FileWorldRepository(Path path, Class<T> type, JsonCodec json, boolean writable, Consumer<T> validate) {
        this.path = path;
        this.type = type;
        this.json = json;
        this.writable = writable;
        this.validate = validate;
    }

    @Override
    public Optional<T> load() throws IOException {
        checkPath();
        // readAttributes distinguishes absence from access failures; exists() does not.
        try {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException e) {
            return Optional.empty();
        }
        try {
            T world = json.read(Files.readString(path, StandardCharsets.UTF_8), type);
            validate.accept(world);
            return Optional.of(world);
        } catch (ContractException e) {
            throw new IOException("World file violates its structural contract");
        }
    }

    @Override
    public void save(T world) throws IOException {
        if (!writable) throw new SecurityException("This world capability is read-only");
        validate.accept(world);
        checkPath();
        byte[] bytes = json.write(world).getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(path.getParent(), ".world-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            replaceAtomically(temporary);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void replaceAtomically(Path temporary) throws IOException {
        for (int attempt = 0; ; attempt++) {
            checkPath();
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException failure) {
                // On Windows a reader can briefly hold either file open without delete sharing.
                if (attempt == 4) throw failure;
                try {
                    Thread.sleep(25L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    var aborted = new java.io.InterruptedIOException("Atomic world replacement interrupted");
                    aborted.initCause(failure);
                    throw aborted;
                }
            }
        }
    }

    private void checkPath() throws IOException {
        // Reject symbolic links and junctions visible through real-path resolution.
        if (!path.getParent().toRealPath().equals(path.getParent())
                || Files.isSymbolicLink(path)
                || (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !path.toRealPath().equals(path))) {
            throw new SecurityException("World path must remain within its allocated directory");
        }
    }
}
