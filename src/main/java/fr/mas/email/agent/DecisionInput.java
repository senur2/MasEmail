package fr.mas.email.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.mas.email.contract.Checks;
import fr.mas.email.contract.CorrectionPlan;
import fr.mas.email.contract.EmailAnalysis;
import java.util.List;

public record DecisionInput(EmailAnalysis analysis, List<AgentConfig> availableWriters,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String routingHint,
                            @JsonInclude(JsonInclude.Include.NON_NULL) CorrectionPlan correctionPlan,
                            @JsonInclude(JsonInclude.Include.NON_NULL) fr.mas.email.world.ThreadHistoryDigest threadHistory) {
    public DecisionInput(EmailAnalysis analysis, List<AgentConfig> availableWriters, String routingHint,
                         CorrectionPlan correctionPlan) {
        this(analysis, availableWriters, routingHint, correctionPlan, null);
    }
    public DecisionInput(EmailAnalysis analysis, List<AgentConfig> availableWriters, String routingHint) {
        this(analysis, availableWriters, routingHint, null);
    }

    public DecisionInput(EmailAnalysis analysis, List<AgentConfig> availableWriters) {
        this(analysis, availableWriters, null);
    }
    /** Projects only the caller's authorized catalog profiles into the routing registry. */
    public static DecisionInput forAuthorizedWriters(EmailAnalysis analysis, List<String> authorizedWriterIds) {
        Checks.required(authorizedWriterIds, "authorizedWriterIds");
        return new DecisionInput(analysis, authorizedWriterIds.stream()
                .map(id -> WriterProfiles.get(id).toAgentConfig())
                .toList());
    }

    public DecisionInput {
        Checks.required(analysis, "analysis");
        availableWriters = Checks.list(availableWriters, "availableWriters");
        routingHint = Checks.nullableText(routingHint, "routingHint");
        if (availableWriters.stream().anyMatch(config -> config.role() != AgentRole.WRITER)) {
            throw new IllegalArgumentException("Routing candidates must be Writers");
        }
    }
}
