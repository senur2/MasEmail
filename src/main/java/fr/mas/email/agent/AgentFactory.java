package fr.mas.email.agent;

import fr.mas.email.contract.*;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.world.*;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/** Composition-time factory; every profile uses the same WriterAgent implementation. */
public final class AgentFactory {
    private final LlmGateway llm;
    private final FileWorldStore worlds;
    private final MiniWorldService miniWorlds;
    private final Set<String> allocatedIds = new HashSet<>();

    public AgentFactory(LlmGateway llm, FileWorldStore worlds) {
        this(llm, worlds, new MiniWorldService());
    }

    public AgentFactory(LlmGateway llm, FileWorldStore worlds, MiniWorldService miniWorlds) {
        this.llm = Checks.required(llm, "llm");
        this.worlds = Checks.required(worlds, "worlds");
        this.miniWorlds = Checks.required(miniWorlds, "miniWorlds");
    }

    public ParserAgent<ParserInput> createParser(AgentConfig config) {
        return createParser(config, Duration.ofSeconds(300));
    }

    public ParserAgent<ParserInput> createParser(AgentConfig config, Duration timeout) {
        var behavior = ParserAgent.extraction(timeout);
        return new ParserAgent<>(config, llm, allocate(config, AgentRole.PARSER, behavior), behavior);
    }

    public ParserAgent<String> createParser(AgentConfig config, AgentBehavior<String, EmailAnalysis> behavior) {
        return new ParserAgent<>(config, llm, allocate(config, AgentRole.PARSER, behavior), behavior);
    }

    public DecisionAgent createDecision(AgentConfig config) {
        return createDecision(config, Duration.ofSeconds(300));
    }

    public DecisionAgent createDecision(AgentConfig config, Duration timeout) {
        return createDecision(config, DecisionAgent.routing(timeout));
    }

    public DecisionAgent createDecision(AgentConfig config, AgentBehavior<DecisionInput, RouteDecision> behavior) {
        return new DecisionAgent(config, llm, allocate(config, AgentRole.DECISION, behavior), behavior);
    }

    public WriterAgent createWriter(AgentConfig config, AgentBehavior<WriterInput, ResponsePlan> behavior) {
        return new WriterAgent(config, llm, allocate(config, AgentRole.WRITER, behavior), behavior);
    }

    public WriterAgent createWriter(AgentConfig config) {
        return createWriter(config, WriterAgent.planning(miniWorlds, Duration.ofSeconds(300)));
    }

    public ScribeAgent createScribe(AgentConfig config) {
        return createScribe(config, Duration.ofSeconds(300));
    }

    public ScribeAgent createScribe(AgentConfig config, Duration timeout) {
        var behavior = ScribeAgent.drafting(timeout);
        return new ScribeAgent(config, llm, allocate(config, AgentRole.SCRIBE, behavior), behavior);
    }

    public ScribeAgent createScribe(AgentConfig config, AgentBehavior<ResponsePlan, DraftResponse> behavior) {
        return new ScribeAgent(config, llm, allocate(config, AgentRole.SCRIBE, behavior),
                (input, context) -> behavior.process(input.responsePlan(), context));
    }

    public CorrectingAgent createCorrecting(AgentConfig config) {
        return createCorrecting(config, CorrectingAgent.diagnosis(Duration.ofSeconds(300)));
    }

    public CorrectingAgent createCorrecting(AgentConfig config, AgentBehavior<CorrectingInput, CorrectionPlan> behavior) {
        return new CorrectingAgent(config, llm, allocate(config, AgentRole.CORRECTING, behavior), behavior);
    }

    private WorldRepository<MiniWorld> allocate(AgentConfig config, AgentRole role, Object behavior) {
        Checks.required(config, "config");
        Checks.required(behavior, "behavior");
        if (config.role() != role) throw new IllegalArgumentException("Configuration role does not match agent");
        if (allocatedIds.contains(config.agentId())) throw new IllegalArgumentException("Duplicate agent ID");
        var repository = worlds.miniWorld(config.agentId(), false);
        allocatedIds.add(config.agentId());
        return repository;
    }
}
