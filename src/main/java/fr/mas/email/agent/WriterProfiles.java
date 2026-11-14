package fr.mas.email.agent;

import fr.mas.email.contract.ContractException;
import fr.mas.email.contract.JsonCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Catalog of packaged profiles. Authorization remains with the caller of the routing step. */
public final class WriterProfiles {
    private static final String RESOURCE = "/writer_profiles.json";
    private static Map<String, WriterProfile> profiles;

    private WriterProfiles() {}

    public static synchronized WriterProfile get(String agentId) {
        if (agentId == null) throw new IllegalArgumentException("agentId is required");
        if (profiles == null) profiles = load();
        var profile = profiles.get(agentId);
        if (profile == null) {
            throw new IllegalArgumentException("No Writer profile for agentId: " + agentId);
        }
        return profile;
    }

    private static Map<String, WriterProfile> load() {
        try (var resource = WriterProfiles.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) throw new IllegalStateException("Missing Writer profile resource: " + RESOURCE);
            var json = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            var loaded = new JsonCodec().read(json, WriterProfile[].class);
            return Arrays.stream(loaded).collect(Collectors.toUnmodifiableMap(
                    profile -> profile.toAgentConfig().agentId(), Function.identity()));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load Writer profiles", e);
        } catch (ContractException e) {
            throw new IllegalStateException("Invalid packaged Writer profiles", e);
        }
    }
}
