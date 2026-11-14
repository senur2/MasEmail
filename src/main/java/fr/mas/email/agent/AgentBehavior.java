package fr.mas.email.agent;

import fr.mas.email.result.*;

/** Strategy extension: phase 1 supplies no cognitive or routing implementation. */
@FunctionalInterface
public interface AgentBehavior<I, O> {
    AgentResult<O> process(I input, AgentContext context) throws Exception;

    static <I, O> AgentBehavior<I, O> unconfigured() {
        return (input, context) -> Errors.create(
                ErrorCode.NOT_IMPLEMENTED, context.configuration().agentId(), context.task());
    }
}
