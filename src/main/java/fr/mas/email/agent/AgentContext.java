package fr.mas.email.agent;

import fr.mas.email.contract.Checks;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.MiniWorld;
import fr.mas.email.world.WorldRepository;

public record AgentContext(AgentConfig configuration, TaskContext task,
                           WorldRepository<MiniWorld> miniWorld, LlmGateway llm) {
    public AgentContext {
        Checks.required(configuration, "configuration");
        Checks.required(task, "task");
        Checks.required(miniWorld, "miniWorld");
        Checks.required(llm, "llm");
    }
}
