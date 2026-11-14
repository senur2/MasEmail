package fr.mas.email.orchestration;

import fr.mas.email.agent.AgentConfig;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.contract.*;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** Supply the authorized, eligible and currently available Writer configurations for this task. */
public final class RouteDecisionValidator implements PlanValidationStrategy<RouteDecision> {
    private final String emailId;
    private final Set<String> writers;

    public RouteDecisionValidator(String emailId, Collection<AgentConfig> availableWriters) {
        this.emailId = Checks.text(emailId, "emailId");
        Checks.required(availableWriters, "availableWriters");
        Checks.rule(availableWriters.stream().allMatch(config -> config.role() == AgentRole.WRITER));
        writers = availableWriters.stream().map(AgentConfig::agentId).collect(Collectors.toUnmodifiableSet());
    }

    @Override public void validate(RouteDecision route) {
        Checks.required(route, "route");
        Checks.rule(emailId.equals(route.emailId()), "Route emailId does not match the task");
        Checks.rule(writers.containsAll(route.candidateAgents()), "Route contains an unauthorized candidate");
        Checks.rule(route.selectedAgentId() == null || writers.contains(route.selectedAgentId()),
                "Selected Writer is not authorized");
    }
}
