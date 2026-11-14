package fr.mas.email;

import com.fasterxml.jackson.databind.json.JsonMapper;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.agent.DecisionInput;
import fr.mas.email.agent.WriterProfiles;
import fr.mas.email.contract.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WriterProfilesTest {
    @ParameterizedTest
    @ValueSource(strings = {"AcademicWriter", "ResearchWriter", "FinanceWriter", "PersonalWriter", "GenericWriter"})
    void loadsSpecializedProfilesAndProjectsTheirRoutingConfiguration(String id) throws Exception {
        var profile = WriterProfiles.get(id);
        assertEquals(id, profile.agentId());
        assertFalse(profile.domainGuidance().isBlank());
        assertFalse(profile.specialization().isEmpty());
        assertFalse(profile.objectives().isEmpty());
        assertFalse(profile.constraints().isEmpty());
        var json = new JsonMapper().readTree(new JsonCodec().write(profile));
        assertEquals(List.of("agentId", "role", "specialization", "objectives", "constraints", "domainGuidance",
                        "tone", "replyStructure"),
                json.properties().stream().map(Map.Entry::getKey).toList());
        assertTrue(json.path("role").isTextual());
        assertTrue(json.path("specialization").isArray());
        assertFalse(profile.tone().isBlank());
        assertFalse(profile.replyStructure().isEmpty());
        var input = DecisionInput.forAuthorizedWriters(TestData.analysis("fr", "REQUEST", List.of()), List.of(id));
        var config = input.availableWriters().getFirst();
        assertEquals(1, input.availableWriters().size());
        assertEquals(id, config.agentId());
        assertEquals(AgentRole.WRITER, config.role());
        assertEquals(String.join("; ", profile.specialization()), config.specialization());
        assertEquals(profile.objectives(), config.objectives());
        assertEquals(profile.constraints(), config.constraints());
    }

    @Test
    void cannotAuthorizeAnUnknownCatalogId() {
        var analysis = TestData.analysis("fr", "REQUEST", List.of());
        assertThrows(IllegalArgumentException.class,
                () -> DecisionInput.forAuthorizedWriters(analysis, List.of("UnknownWriter")));
    }
}
