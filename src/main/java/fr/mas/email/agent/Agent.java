package fr.mas.email.agent;

import fr.mas.email.result.AgentResult;
import fr.mas.email.result.TaskContext;

/** Invoked by the mediator. An agent holds no reference to another agent or to a view. */
public interface Agent<I, O> {
    String id();
    AgentResult<O> execute(I input, TaskContext task);
}
