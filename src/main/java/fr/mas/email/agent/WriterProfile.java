package fr.mas.email.agent;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import fr.mas.email.contract.Checks;

import java.util.LinkedHashSet;
import java.util.List;

/** The specialized profile provided to the shared Writer prompt. */
@JsonPropertyOrder({"agentId", "role", "specialization", "objectives", "constraints", "domainGuidance",
        "tone", "replyStructure"})
public record WriterProfile(String agentId, String role, List<String> specialization,
                            List<String> objectives, List<String> constraints, String domainGuidance,
                            String tone, List<String> replyStructure) {
    public WriterProfile {
        agentId = Checks.text(agentId, "agentId");
        role = Checks.text(role, "role");
        specialization = Checks.texts(specialization, "specialization");
        if (specialization.isEmpty()) throw new IllegalArgumentException("specialization is required");
        objectives = Checks.texts(objectives, "objectives");
        constraints = Checks.texts(constraints, "constraints");
        domainGuidance = Checks.text(domainGuidance, "domainGuidance");
        tone = Checks.text(tone, "tone");
        replyStructure = Checks.texts(replyStructure, "replyStructure");
        if (replyStructure.isEmpty()) throw new IllegalArgumentException("replyStructure is required");
    }

    public AgentConfig toAgentConfig() {
        return new AgentConfig(agentId, AgentRole.WRITER, specializationText(), objectives, constraints);
    }

    /** Apply task-specific configuration alongside the packaged domain restrictions. */
    WriterProfile withConfiguration(AgentConfig config) {
        var goals = new LinkedHashSet<>(objectives);
        goals.addAll(config.objectives());
        var restrictions = new LinkedHashSet<>(constraints);
        restrictions.addAll(config.constraints());
        var scope = specializationText().equals(config.specialization())
                ? specialization : List.of(config.specialization());
        return new WriterProfile(agentId, role, scope, List.copyOf(goals), List.copyOf(restrictions),
                domainGuidance, tone, replyStructure);
    }

    private String specializationText() {
        return String.join("; ", specialization);
    }
}
