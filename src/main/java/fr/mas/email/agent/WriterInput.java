package fr.mas.email.agent;

import fr.mas.email.contract.Checks;
import fr.mas.email.contract.CorrectionPlan;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.world.Belief;
import fr.mas.email.world.MiniWorldService.Knowledge;
import java.util.List;

/**
 * BDI extension points: relevant beliefs and proposed intentions are explicit input.
 * The original email is available to injected behaviors. The built-in Writer uses the analysis.
 */
public record WriterInput(EmailAnalysis analysis, List<Belief> beliefs, List<String> intentions, String originalEmail,
                          Knowledge knowledge, CorrectionPlan correctionPlan) {
    public WriterInput(EmailAnalysis analysis, List<Belief> beliefs, List<String> intentions, String originalEmail,
                       Knowledge knowledge) {
        this(analysis, beliefs, intentions, originalEmail, knowledge, null);
    }

    public WriterInput(EmailAnalysis analysis, List<Belief> beliefs, List<String> intentions, String originalEmail) {
        this(analysis, beliefs, intentions, originalEmail, new Knowledge(java.util.Map.of(), java.util.Map.of()));
    }

    public WriterInput withKnowledge(Knowledge knowledge) {
        return new WriterInput(analysis, beliefs, intentions, originalEmail, knowledge, correctionPlan);
    }

    public WriterInput withCorrection(CorrectionPlan correction) {
        return new WriterInput(analysis, beliefs, intentions, originalEmail, knowledge, correction);
    }
    public WriterInput(EmailAnalysis analysis) {
        this(analysis, List.of(), List.of());
    }

    public WriterInput(EmailAnalysis analysis, List<Belief> beliefs, List<String> intentions) {
        this(analysis, beliefs, intentions, null);
    }

    public WriterInput(String originalEmail, EmailAnalysis analysis) {
        this(analysis, List.of(), List.of(), Checks.text(originalEmail, "originalEmail"));
    }

    public WriterInput {
        Checks.required(analysis, "analysis");
        beliefs = Checks.list(beliefs, "beliefs");
        intentions = Checks.texts(intentions, "intentions");
        originalEmail = Checks.nullableText(originalEmail, "originalEmail");
        Checks.required(knowledge, "knowledge");
    }
}
