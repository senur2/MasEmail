package fr.mas.email.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.mas.email.contract.Checks;
import fr.mas.email.contract.Contract;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.contract.InvalidOutputException;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.ResponsePlan;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.DiscussionPlan;
import fr.mas.email.result.ErrorCode;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Errors;
import fr.mas.email.result.Success;
import fr.mas.email.world.MiniWorld;
import fr.mas.email.world.MiniWorldService;
import fr.mas.email.world.WorldRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Builds a response plan using the knowledge scoped to this Writer's identity. */
public final class WriterAgent extends ConfiguredAgent<WriterInput, ResponsePlan> {
    WriterAgent(AgentConfig config, LlmGateway llm, WorldRepository<MiniWorld> world,
              AgentBehavior<WriterInput, ResponsePlan> behavior) {
        super(config, llm, world, behavior);
    }

    static AgentBehavior<WriterInput, ResponsePlan> planning(MiniWorldService miniWorlds, Duration timeout) {
        String prompt = loadPrompt();
        var json = new JsonCodec();
        return (input, context) -> {
            String agentId = context.configuration().agentId();
            var profile = WriterProfiles.get(agentId).withConfiguration(context.configuration());
            var knowledge = json.read(miniWorlds.buildCompactKnowledgeContext(agentId), JsonNode.class);
            if (outsideKnowledgeScope(knowledge, input.analysis())) {
                // A matching subject (grades, for example) never authorizes another course's facts.
                // Do this before the LLM call so even a copied one-shot answer cannot bypass the gate.
                if (!input.analysis().requestedInformation().isEmpty()) {
                    var plan = clarificationOutsideScope(input);
                    if (plan.requiresClarification() || (input.correctionPlan() == null
                            && input.analysis().requestedActions().isEmpty()
                            && input.analysis().senderClaims().isEmpty())) {
                        return new Success<>(plan.enrich(agentId, input.analysis().emailId(), profile.replyStructure(),
                                input.analysis().language(), profile.tone()));
                    }
                }
                // Notifications may still be acknowledged, but without the other entity's state or answers.
                var domain = (ObjectNode) knowledge.path("domainKnowledge");
                domain.putObject("state");
                domain.putObject("informationLookup");
            }
            if (!input.knowledge().common().isEmpty()) {
                var common = (ObjectNode) knowledge.path("commonKnowledge");
                input.knowledge().common().forEach(common.putObject("informationLookup")::put);
            }
            var lookup = (ObjectNode) knowledge.at("/domainKnowledge/informationLookup");
            input.knowledge().particular().forEach(lookup::put);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("emailAnalysis", input.analysis());
            payload.put("writerProfile", profile);
            payload.put("miniWorlds", knowledge);
            if (input.correctionPlan() != null) payload.put("correctionPlan", input.correctionPlan());
            var request = new LlmRequest<>(prompt, json.write(payload), Contract.RESPONSE_PLAN_CONTENT, timeout);
            return switch (context.llm().generate(request, context.task(), agentId)) {
                case Success<ResponsePlan.Content> success -> {
                    try {
                        var content = success.value();
                        Checks.rule(content.missingInformation().stream().allMatch(key -> key.matches("[a-z][a-z0-9_]*")),
                                "missingInformation must contain snake_case keys");
                        var facts = new ArrayList<>(content.factsToCommunicate().stream().map(fact -> new ResponsePlan.Fact(
                                fact.text(), sourceOrigin(fact.sourceRef(), input.analysis(), knowledge,
                                        content.requiresClarification()))).toList());
                        var remaining = new ArrayList<String>();
                        for (String key : content.missingInformation()) {
                            // Only explicit user knowledge resolves a repeated question. Packaged defaults
                            // can still be inapplicable to the specific event or course.
                            String value = input.knowledge().particular().get(key);
                            String origin = "domainKnowledge";
                            if (value == null) {
                                value = input.knowledge().common().get(key);
                                origin = "commonKnowledge";
                            }
                            if (value == null) {
                                remaining.add(key);
                            } else {
                                String answer = value;
                                String source = origin;
                                if (facts.stream().noneMatch(fact -> fact.sourceRef().equals(source)
                                        && fact.text().contains(answer))) {
                                    facts.add(new ResponsePlan.Fact(key.replace('_', ' ') + " : " + answer, source));
                                }
                            }
                        }
                        boolean needsClarification = !remaining.isEmpty();
                        var intent = content.requiresClarification() && !needsClarification
                                ? ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST : content.responseIntent();
                        var plan = new ResponsePlan.Content(intent, facts, remaining, needsClarification);
                        validateQuestionCoverage(plan, input.analysis());
                        yield new Success<>(plan.enrich(agentId, input.analysis().emailId(), profile.replyStructure(),
                                input.analysis().language(), profile.tone()), success.rawOutput());
                    } catch (InvalidOutputException e) {
                        yield Errors.validation(ErrorCode.INVALID_OUTPUT, "WriterAgent", context.task(), e, success.rawOutput());
                    }
                }
                case ErrorPlan<ResponsePlan.Content> error -> error.retype();
                case fr.mas.email.result.CorrectionRequest<ResponsePlan.Content> correction -> correction.retype();
                case null -> Errors.create(ErrorCode.NO_RESULT, "WriterAgent", context.task());
                case DiscussionPlan<ResponsePlan.Content> ignored ->
                        Errors.create(ErrorCode.INVALID_OUTPUT, "WriterAgent", context.task());
            };
        };
    }

    private static boolean outsideKnowledgeScope(JsonNode knowledge, EmailAnalysis analysis) {
        var scope = knowledge.at("/domainKnowledge/scope");
        if (scope.isMissingNode()) return false;
        String type = scope.path("entityType").asText();
        var allowed = scope.path("entityValues");
        Checks.rule(!type.isBlank() && allowed.isArray() && !allowed.isEmpty(), "Invalid MiniWorld entity scope");
        boolean identified = false;
        for (var entity : analysis.entities()) {
            if (!type.equalsIgnoreCase(entity.type())) continue;
            identified = true;
            boolean matches = false;
            for (var value : allowed) {
                if (value.isTextual() && value.textValue().strip().equalsIgnoreCase(entity.value().strip())) {
                    matches = true;
                    break;
                }
            }
            if (!matches) return true;
        }
        return !identified;
    }

    private static ResponsePlan.Content clarificationOutsideScope(WriterInput input) {
        var missing = new ArrayList<String>();
        var facts = new ArrayList<ResponsePlan.Fact>();
        for (String key : input.analysis().requestedInformation()) {
            String answer = input.knowledge().particular().get(key);
            String source = "domainKnowledge";
            if (answer == null) {
                answer = input.knowledge().common().get(key);
                source = "commonKnowledge";
            }
            if (answer == null) missing.add(key);
            else facts.add(new ResponsePlan.Fact(key.replace('_', ' ') + " : " + answer, source));
        }
        return new ResponsePlan.Content(missing.isEmpty() ? ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST
                : ResponsePlan.Intent.REQUEST_CLARIFICATION, facts, missing, !missing.isEmpty());
    }

    private static void validateQuestionCoverage(ResponsePlan.Content plan, EmailAnalysis analysis) {
        int requested = analysis.requestedInformation().size();
        int provided = plan.factsToCommunicate().size();
        if (!plan.requiresClarification() && requested > 0 && provided < requested) {
            throw new InvalidOutputException("WriterAgent marked requiresClarification=false but provided fewer facts ("
                    + provided + ") than requestedInformation (" + requested + ").");
        }
        if (plan.requiresClarification() && requested == 1 && analysis.senderClaims().isEmpty()
                && analysis.requestedActions().isEmpty() && provided > 0) {
            throw new InvalidOutputException("WriterAgent included unrelated facts in factsToCommunicate "
                    + "despite the single requested question being unanswered.");
        }
    }

    private static String sourceOrigin(String reference, EmailAnalysis analysis, JsonNode knowledge,
                                       boolean requiresClarification) {
        String root = reference.split("\\.", 2)[0];
        if (root.equals("commonKnowledge") || root.equals("domainKnowledge")) {
            // Some models return a qualified path. Only keep its origin if the entry actually exists.
            var entry = knowledge.at("/" + reference.replace('.', '/'));
            Checks.rule(!entry.isMissingNode() && !entry.isNull(), "sourceRef does not resolve in the MiniWorld");
            return root;
        }
        Checks.rule(!requiresClarification && switch (reference) {
            case "emailAnalysis.senderClaims" -> !analysis.senderClaims().isEmpty();
            case "emailAnalysis.requestedActions" -> !analysis.requestedActions().isEmpty();
            case "emailAnalysis.requestedInformation" -> !analysis.requestedInformation().isEmpty();
            default -> false;
        }, "sourceRef is unavailable or disallowed for this response plan");
        return reference;
    }

    private static String loadPrompt() {
        try (var resource = WriterAgent.class.getResourceAsStream("/prompts/writer_agent_system.md")) {
            if (resource == null) throw new IllegalStateException("Missing WriterAgent prompt");
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load WriterAgent prompt", e);
        }
    }
}
