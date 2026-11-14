package fr.mas.email.agent;

import fr.mas.email.contract.Checks;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.result.*;
import fr.mas.email.world.*;

/** Shared execution boundary, without domain reasoning or communication with other agents. */
public abstract class ConfiguredAgent<I, O> implements Agent<I, O> {
    private final AgentConfig configuration;
    private final LlmGateway llm;
    private final WorldRepository<MiniWorld> miniWorld;
    private final AgentBehavior<I, O> behavior;

    ConfiguredAgent(AgentConfig configuration, LlmGateway llm, WorldRepository<MiniWorld> miniWorld,
                    AgentBehavior<I, O> behavior) {
        this.configuration = Checks.required(configuration, "configuration");
        this.llm = Checks.required(llm, "llm");
        this.miniWorld = Checks.required(miniWorld, "miniWorld");
        this.behavior = Checks.required(behavior, "behavior");
    }

    public final AgentConfig configuration() { return configuration; }
    @Override public final String id() { return configuration.agentId(); }

    @Override
    public final AgentResult<O> execute(I input, TaskContext task) {
        Checks.required(input, "input");
        Checks.required(task, "task");
        try {
            var result = behavior.process(input, new AgentContext(configuration, task, miniWorld, llm));
            if (result == null) return contextualize(Errors.create(ErrorCode.NO_RESULT, id(), task));
            if (result instanceof ErrorPlan<O> error && error.agentId() == null) return contextualize(error);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return contextualize(Errors.create(ErrorCode.INTERNAL_ERROR, id(), task));
        } catch (Exception e) {
            return contextualize(Errors.from(e, id(), task));
        }
    }

    private ErrorPlan<O> contextualize(ErrorPlan<O> error) {
        return Errors.forAgent(error, id(), ErrorPlan.Stage.valueOf(configuration.role().name()),
                getClass().getSimpleName(), "Valid " + configuration.role() + " result");
    }
}
