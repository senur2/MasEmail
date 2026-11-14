package fr.mas.email.llm;

import fr.mas.email.result.AgentResult;
import fr.mas.email.result.TaskContext;

/**
 * Adapter port shared by all LLM-using agents.
 * Implementations enforce request.timeout(), return a structurally validated payload,
 * and construct incidents in Java without retrying. No envelope or ErrorPlan is decoded from the model.
 */
public interface LlmGateway {
    <T> AgentResult<T> generate(LlmRequest<T> request, TaskContext task, String component);
}
