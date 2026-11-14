package fr.mas.email;

import fr.mas.email.contract.JsonCodec;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorldRepositoryTest {
    @TempDir Path directory;
    private final JsonCodec json = new JsonCodec();

    @Test
    void worldsPersistAcrossRepositoryInstances() throws IOException {
        var store = new FileWorldStore(directory, json);
        var mini = store.miniWorld("ResearchWriter", true);
        assertTrue(mini.load().isEmpty());
        var world = new MiniWorld("ResearchWriter", Map.of("domain", "research"));
        mini.save(world);
        assertEquals(world, new FileWorldStore(directory, json).miniWorld("ResearchWriter", true).load().orElseThrow());

        var shared = new SharedWorld(TaskContext.start(), "ResearchWriter", SharedWorld.Status.WAITING_USER, "Date ?");
        store.sharedWorld().save(shared);
        assertEquals(shared, new FileWorldStore(directory, json).sharedWorld().load().orElseThrow());
    }

    @Test
    void capabilityRejectsAnotherOwnerAndPreservesBothWorlds() throws IOException {
        var store = new FileWorldStore(directory, json);
        var research = store.miniWorld("ResearchWriter", true);
        var student = store.miniWorld("StudentWriter", true);
        research.save(new MiniWorld("ResearchWriter", Map.of("private", "research")));
        student.save(new MiniWorld("StudentWriter", Map.of("private", "student")));
        assertThrows(SecurityException.class,
                () -> research.save(new MiniWorld("StudentWriter", Map.of("private", "overwritten"))));
        assertEquals("research", research.load().orElseThrow().knowledge().get("private"));
        assertEquals("student", student.load().orElseThrow().knowledge().get("private"));
    }

    @Test
    void readOnlyCapabilityCannotWrite() throws IOException {
        var store = new FileWorldStore(directory, json);
        store.miniWorld("Parser", true).save(MiniWorld.empty("Parser"));
        var readOnly = store.miniWorld("Parser", false);
        assertTrue(readOnly.load().isPresent());
        assertThrows(SecurityException.class, () -> readOnly.save(MiniWorld.empty("Parser")));
    }

    @Test
    void fileNamesAreSafeAndDistinctOnWindows() throws IOException {
        var store = new FileWorldStore(directory, json);
        for (String owner : new String[] {"Writer", "writer", "../escape", "CON"}) {
            store.miniWorld(owner, true).save(MiniWorld.empty(owner));
        }
        try (var files = Files.list(directory)) {
            assertEquals(4, files.count());
        }
        assertEquals("Writer", store.miniWorld("Writer", false).load().orElseThrow().ownerAgentId());
        assertEquals("writer", store.miniWorld("writer", false).load().orElseThrow().ownerAgentId());
    }

    @Test
    void successiveAtomicReplacementsLeaveValidJsonAndNoTemporaryFile() throws IOException {
        var repository = new FileWorldStore(directory, json).miniWorld("Writer", true);
        for (int revision = 0; revision < 5; revision++) {
            var world = new MiniWorld("Writer", Map.of("revision", Integer.toString(revision)));
            repository.save(world);
            assertEquals(world, repository.load().orElseThrow());
        }
        try (var files = Files.list(directory)) {
            assertTrue(files.allMatch(path -> path.toString().endsWith(".json")));
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void atomicReplacementCanRecoverAfterAReaderReleasesTheTarget() throws Exception {
        var repository = new FileWorldStore(directory, json).sharedWorld();
        var original = new SharedWorld(TaskContext.start(), "Writer", SharedWorld.Status.RUNNING, "");
        var replacement = new SharedWorld(original.task(), "Writer", SharedWorld.Status.COMPLETED, "");
        repository.save(original);
        try (var watcher = directory.getFileSystem().newWatchService();
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            java.util.concurrent.Future<Void> save;
            try (var reader = java.nio.channels.FileChannel.open(directory.resolve("shared.json"),
                    StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
                save = executor.submit(() -> { repository.save(replacement); return null; });
                var event = watcher.poll(2, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(event, "The writer should create its temporary file while the target is locked");
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> save.get(60, java.util.concurrent.TimeUnit.MILLISECONDS));
                assertEquals(original, repository.load().orElseThrow());
            }
            save.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(replacement, repository.load().orElseThrow());
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void corruptionAndForeignOwnerOnDiskAreReportedWithoutOverwriting() throws IOException {
        var repository = new FileWorldStore(directory, json).miniWorld("Writer", true);
        repository.save(MiniWorld.empty("Writer"));
        Path path;
        try (var files = Files.list(directory)) { path = files.findFirst().orElseThrow(); }
        Files.writeString(path, "{corrupt");
        assertThrows(IOException.class, repository::load);
        assertEquals("{corrupt", Files.readString(path));
        Files.writeString(path, json.write(MiniWorld.empty("OtherWriter")));
        assertThrows(SecurityException.class, repository::load);
    }

    @Test
    void failedReplacementDoesNotDestroyExistingTargetOrLeaveTemporaryFile() throws IOException {
        var repository = new FileWorldStore(directory, json).sharedWorld();
        Path target = Files.createDirectory(directory.resolve("shared.json"));
        Files.writeString(target.resolve("keep.txt"), "original");
        assertThrows(IOException.class, () -> repository.save(
                new SharedWorld(TaskContext.start(), "Writer", SharedWorld.Status.RUNNING, "")));
        assertEquals("original", Files.readString(target.resolve("keep.txt")));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void knowledgeIsImmutableAndBeliefConfidenceIsValidated() {
        var knowledge = new java.util.HashMap<>(Map.of("key", "value"));
        var world = new MiniWorld("Writer", knowledge);
        knowledge.clear();
        assertEquals(Map.of("key", "value"), world.knowledge());
        assertThrows(UnsupportedOperationException.class, () -> world.knowledge().put("key", "new"));
        assertThrows(IllegalArgumentException.class, () -> new Belief("claim", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Belief("claim", 1.1));
    }
}
