package fr.mas.email.world;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fr.mas.email.contract.Checks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/** Loads packaged facts and manages confirmed additions for the orchestrator. */
public final class MiniWorldService {
    private static final String ROOT = "/miniworlds/";
    private static final String COMMON_ID = "COMMON_WORLD";
    private static final Object STORAGE_LOCK = new Object();
    private static final Map<String, String> DOMAIN_FILES = Map.of(
            "AcademicWriter", "academic_miniworld.json",
            "ResearchWriter", "research_miniworld.json",
            "FinanceWriter", "finance_miniworld.json",
            "PersonalWriter", "personal_miniworld.json",
            "GenericWriter", "generic_miniworld.json");

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final Map<String, String> contexts = new HashMap<>();
    private JsonNode common;
    private final FileWorldStore storage;
    private final boolean packagedDefaults;

    public MiniWorldService() { this(null); }

    public MiniWorldService(FileWorldStore storage) { this(storage, true); }

    public MiniWorldService(FileWorldStore storage, boolean packagedDefaults) {
        this.storage = storage;
        this.packagedDefaults = packagedDefaults;
    }

    public record Knowledge(Map<String, String> common, Map<String, String> particular) {
        public Knowledge {
            common = Map.copyOf(common);
            particular = Map.copyOf(particular);
        }

        public Knowledge withSession(Map<String, String> values) {
            var merged = new HashMap<>(particular);
            merged.putAll(values);
            return new Knowledge(common, merged);
        }
    }

    public Knowledge readKnowledge(String agentId) throws IOException {
        requireWriter(agentId);
        return new Knowledge(loadStored(COMMON_ID), loadStored(agentId));
    }

    public void persistToCommonKnowledge(Map<String, String> values) throws IOException {
        persist(COMMON_ID, values);
    }

    public void persistToAgentKnowledge(String agentId, Map<String, String> values) throws IOException {
        requireWriter(agentId);
        persist(agentId, values);
    }

    private Map<String, String> loadStored(String id) throws IOException {
        if (storage == null) return Map.of();
        return storage.miniWorld(id, false).load().orElseGet(() -> MiniWorld.empty(id)).knowledge();
    }

    private void persist(String id, Map<String, String> values) throws IOException {
        if (storage == null) throw new IllegalStateException("Knowledge storage is unavailable");
        synchronized (STORAGE_LOCK) {
            var repository = storage.miniWorld(id, true);
            var merged = new HashMap<>(repository.load().orElseGet(() -> MiniWorld.empty(id)).knowledge());
            values.forEach((key, value) -> {
                if (!key.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid knowledge key");
                merged.put(key, Checks.text(value, "knowledge value"));
            });
            repository.save(new MiniWorld(id, merged));
        }
    }

    private static void requireWriter(String id) {
        if (id == null || !DOMAIN_FILES.containsKey(id)) throw new IllegalArgumentException("Unknown Writer: " + id);
    }

    public synchronized String buildCompactKnowledgeContext(String selectedAgentId) {
        if (selectedAgentId == null) throw new IllegalArgumentException("selectedAgentId is required");
        String file = DOMAIN_FILES.get(selectedAgentId);
        if (file == null) throw new IllegalArgumentException("No MiniWorld for Writer: " + selectedAgentId);
        return contexts.computeIfAbsent(selectedAgentId, id -> {
            if (!packagedDefaults) {
                var knowledge = mapper.createObjectNode();
                knowledge.putObject("commonKnowledge").put("worldId", COMMON_ID).putObject("informationLookup");
                knowledge.putObject("domainKnowledge").put("worldId", id).putObject("informationLookup");
                return knowledge.toString();
            }
            if (common == null) common = load("common_miniworld.json", COMMON_ID);
            var domain = load("writers/" + file, id);
            var knowledge = mapper.createObjectNode();
            knowledge.set("commonKnowledge", common);
            knowledge.set("domainKnowledge", domain);
            try {
                return mapper.writeValueAsString(knowledge);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot serialize MiniWorld context for " + id, e);
            }
        });
    }

    private JsonNode load(String file, String expectedId) {
        try (var resource = MiniWorldService.class.getResourceAsStream(ROOT + file)) {
            if (resource == null) throw new IllegalArgumentException("Missing MiniWorld resource: " + file);
            JsonNode world = mapper.readTree(resource);
            if (world == null || !world.isObject() || !expectedId.equals(world.path("worldId").textValue())) {
                throw new IllegalStateException("Invalid MiniWorld " + file + ": expected worldId " + expectedId);
            }
            return world;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read MiniWorld resource: " + file, e);
        }
    }
}
