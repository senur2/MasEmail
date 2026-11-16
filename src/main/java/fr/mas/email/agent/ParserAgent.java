package fr.mas.email.agent;

import fr.mas.email.contract.Checks;
import fr.mas.email.contract.Contract;
import fr.mas.email.contract.EmailAnalysis;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.result.CorrectionRequest;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.DiscussionPlan;
import fr.mas.email.result.ErrorCode;
import fr.mas.email.result.ErrorPlan;
import fr.mas.email.result.Errors;
import fr.mas.email.result.Success;
import fr.mas.email.world.MiniWorld;
import fr.mas.email.world.WorldRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Extracts cognitive content and enriches it with Java-owned identities. */
public final class ParserAgent<I> extends ConfiguredAgent<I, EmailAnalysis> {
    ParserAgent(AgentConfig config, LlmGateway llm, WorldRepository<MiniWorld> world,
                AgentBehavior<I, EmailAnalysis> behavior) {
        super(config, llm, world, behavior);
    }

    static AgentBehavior<ParserInput, EmailAnalysis> extraction(Duration timeout) {
        Checks.required(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        String prompt = loadPrompt();
        var json = new JsonCodec();
        return (input, context) -> {
            String payload = input.text();
            if (input.correctionPlan() != null || input.threadHistory() != null) {
                var structured = new LinkedHashMap<String, Object>();
                structured.put("email", input.text());
                if (input.correctionPlan() != null) structured.put("correctionPlan", input.correctionPlan());
                if (input.threadHistory() != null) structured.put("threadHistory", input.threadHistory());
                payload = json.write(structured);
            }
            String instructions = input.correctionPlan() == null ? prompt
                    : prompt + "\nThis request is a correction. Read the original message from the top-level email field.";
            var request = new LlmRequest<>(instructions, payload, Contract.EMAIL_ANALYSIS_CONTENT, timeout);
            var result = context.llm().generate(request, context.task(), context.configuration().agentId());
            return switch (result) {
                case Success<EmailAnalysis.Content> success -> {
                    try {
                        var content = success.value();
                        String normalizedSource = normalizeForEvidenceCheck(input.text());
                        for (var evidence : content.evidence()) {
                            String normalizedQuote = normalizeForEvidenceCheck(evidence.text());
                            Checks.rule(!normalizedQuote.isEmpty() && normalizedSource.contains(normalizedQuote),
                                    "An evidence quote is absent from the source email: " + evidence.text());
                        }
                        yield new Success<>(content.enrich(input.emailId(), input.threadId()), success.rawOutput());
                    } catch (fr.mas.email.contract.InvalidOutputException e) {
                        yield Errors.validation(ErrorCode.INVALID_OUTPUT, "ParserAgent", context.task(), e, success.rawOutput());
                    }
                }
                case ErrorPlan<EmailAnalysis.Content> error -> error.retype();
                case CorrectionRequest<EmailAnalysis.Content> correction -> correction.retype();
                case null -> Errors.create(ErrorCode.NO_RESULT, "ParserAgent", context.task());
                case DiscussionPlan<EmailAnalysis.Content> ignored ->
                        Errors.create(ErrorCode.INVALID_OUTPUT, "ParserAgent", context.task());
            };
        };
    }

    private static String normalizeForEvidenceCheck(String text) {
        if (text == null) return "";
        return text.replaceAll("[\"'«»“”‘’]", "'")
                .replaceAll("(?U)\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String loadPrompt() {
        try (var resource = ParserAgent.class.getResourceAsStream("/prompts/parser-agent-v3.0.txt")) {
            if (resource == null) throw new IllegalStateException("Missing ParserAgent V3.0 prompt");
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load ParserAgent V3.0 prompt", e);
        }
    }
}
