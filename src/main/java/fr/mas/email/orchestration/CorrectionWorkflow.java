package fr.mas.email.orchestration;

import fr.mas.email.agent.CorrectingInput;
import fr.mas.email.contract.*;
import fr.mas.email.result.*;
import java.io.IOException;

/** Application callbacks for agent execution and logging. The mediator chooses the restart stage. */
public interface CorrectionWorkflow {
    AgentResult<AgentMessage<CorrectionPlan>> diagnose(OrchestratorAgent mediator, CorrectingInput input,
                                                      TaskContext task) throws IOException;
    AgentResult<DraftResponse> restart(OrchestratorAgent mediator, ErrorPlan.Stage stage, ExecutionTrace trace,
                                       CorrectionPlan correction) throws IOException;
}
