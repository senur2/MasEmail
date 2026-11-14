package fr.mas.email.app;

import fr.mas.email.contract.DraftContent;
import fr.mas.email.contract.CorrectionPlan;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.ResponsePlan;
import fr.mas.email.contract.RouteDecision;
import fr.mas.email.llm.FakeLlmGateway;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.AgentResult;
import fr.mas.email.result.TaskContext;
import fr.mas.email.agent.CorrectingInput;

import java.util.List;

final class DemoScenario {
    private static final String QUESTION = "À quelle date les notes du cours INF312 seront-elles publiées ?";
    static final String EMAIL = "Objet : Publication des notes d’INF312\n\nBonjour,\n"
            + QUESTION + "\n\nMerci,\nNina";

    private DemoScenario() {}

    static FakeLlmGateway gateway(JsonCodec json) {
        return gateway(json, false);
    }

    static FakeLlmGateway gateway(JsonCodec json, boolean clarification) {
        var analysis = new EmailAnalysis.Content("fr", "REQUEST_INFORMATION", List.of("EVALUATION"),
                List.of(new EmailAnalysis.Entity("COURSE", "INF312")), List.of("date_publication_notes"),
                List.of(), List.of(), List.of(), List.of(new EmailAnalysis.Evidence("requestedInformation", QUESTION)));
        var route = new RouteDecision.Content("AcademicWriter", List.of("AcademicWriter"),
                RouteDecision.DecisionStatus.SELECTED, List.of("La demande porte sur les notes d’un cours."),
                null, false, null);
        var gateway = new FakeLlmGateway(json).enqueueJson(json.write(analysis)).enqueueJson(json.write(route));
        if (clarification) {
            var plan = new ResponsePlan.Content(ResponsePlan.Intent.REQUEST_CLARIFICATION,
                    List.of(), List.of("date_publication_notes"), true);
            return gateway.enqueueJson(json.write(plan));
        }
        var plan = new ResponsePlan.Content(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                List.of(new ResponsePlan.Fact("Indiquer que les notes d’INF312 seront publiées le 18 novembre 2026.",
                        "domainKnowledge.informationLookup.date_publication_notes")), List.of(), false);
        var draft = new DraftContent("Re: Publication des notes d’INF312",
                "Bonjour,\n\nLes notes du cours INF312 seront publiées le 18 novembre 2026.\n\nCordialement,");
        return gateway.enqueueJson(json.write(plan)).enqueueJson(json.write(draft));
    }

    static FakeLlmGateway resumed(JsonCodec json, String answer) {
        var plan = new ResponsePlan.Content(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                List.of(new ResponsePlan.Fact(answer, "domainKnowledge.informationLookup.date_publication_notes")),
                List.of(), false);
        var draft = new DraftContent("Re: Publication des notes d’INF312", "Bonjour,\n\n" + answer + "\n\nCordialement,");
        return new FakeLlmGateway(json).enqueueJson(json.write(plan)).enqueueJson(json.write(draft));
    }

    static LlmGateway correction(JsonCodec json) {
        var fake = new FakeLlmGateway(json);
        return new LlmGateway() {
            @Override public <T> AgentResult<T> generate(LlmRequest<T> request, TaskContext task, String component) {
                if (request.expectedContract().equals(fr.mas.email.contract.Contract.CORRECTION_PLAN)) {
                    var input = json.read(request.input(), CorrectingInput.class);
                    var draft = input.draftResponse();
                    var plan = new CorrectionPlan(CorrectionPlan.Target.SCRIBE_AGENT, CorrectionPlan.Type.ADJUST_STYLE,
                            false, "Utiliser une salutation et une formule de fin plus familières.", List.of(),
                            "La simulation illustre une correction de style.");
                    var revised = new DraftContent(draft.subject(), draft.body()
                            .replace("Bonjour,", "Salut,").replace("Cordialement,", "À bientôt,"));
                    fake.enqueueJson(json.write(plan)).enqueueJson(json.write(revised));
                }
                return fake.generate(request, task, component);
            }
        };
    }
}
