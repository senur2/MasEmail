package fr.mas.email.agent;

import fr.mas.email.contract.*;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.*;
import fr.mas.email.world.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Produces a correction plan for the Orchestrator. */
public final class CorrectingAgent extends ConfiguredAgent<CorrectingInput, CorrectionPlan> {
    CorrectingAgent(AgentConfig config, LlmGateway llm, WorldRepository<MiniWorld> world,
              AgentBehavior<CorrectingInput, CorrectionPlan> behavior) {
        super(config, llm, world, behavior);
    }

    public AgentResult<CorrectionPlan> execute(String feedback, TaskContext task) {
        return execute(new CorrectingInput("USER", feedback, null, null, null, null), task);
    }

    static AgentBehavior<CorrectingInput, CorrectionPlan> diagnosis(Duration timeout) {
        var json = new JsonCodec();
        String prompt;
        try (var resource = CorrectingAgent.class.getResourceAsStream("/prompts/correcting_agent_system.md")) {
            if (resource == null) throw new IllegalStateException("Missing CorrectingAgent prompt");
            prompt = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load CorrectingAgent prompt", e);
        }
        return (input, context) -> {
            var request = new LlmRequest<>(prompt, json.write(input), Contract.CORRECTION_PLAN, timeout, 0.0);
            return context.llm().generate(request, context.task(), context.configuration().agentId());
        };
    }
}
