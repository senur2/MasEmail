package fr.mas.email.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.*;
import fr.mas.email.result.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Uses the supplied, packaged schemas. No remote schemas or generated approximation. */
public final class ContractSchemas {
    private ContractSchemas() {}
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final Map<String, JsonSchema> CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, String> PATHS = Map.ofEntries(
            Map.entry(EmailAnalysis.class, "payload/email-analysis"),
            Map.entry(EmailAnalysis.Content.class, "llm/email-analysis"),
            Map.entry(RouteDecision.class, "payload/route-decision"),
            Map.entry(RouteDecision.Content.class, "llm/route-decision"),
            Map.entry(ResponsePlan.class, "payload/response-plan"),
            Map.entry(ResponsePlan.Content.class, "llm/response-plan"),
            Map.entry(DraftResponse.class, "payload/draft-response"),
            Map.entry(DraftContent.class, "llm/draft-content"),
            Map.entry(CorrectionPlan.class, "payload/correction-plan"),
            Map.entry(DiscussionPlan.class, "payload/discussion-request"),
            Map.entry(ErrorPlan.class, "payload/error-plan"),
            Map.entry(UserDiscussionAnswer.class, "payload/user-discussion-answer"),
            Map.entry(DiscussionResume.class, "payload/discussion-resume"));

    public static JsonNode llmSchema(Contract<?> contract) {
        Checks.rule(contract.isLlmOutput());
        String path = contract.equals(Contract.CORRECTION_PLAN) ? "llm/correction-plan" : PATHS.get(contract.payloadType());
        return document(path);
    }

    static void validatePayload(JsonNode value, Class<?> type) {
        String path = PATHS.get(type);
        if (path != null) validate(value, path);
    }

    static void validateGenerated(JsonNode value, Class<?> type) {
        // The flat generation schema avoids unsupported oneOf/sibling combinations in decoders.
        // Validate the returned correction against the full contract, including cross-field rules.
        String path = PATHS.get(type);
        if (path != null) validate(value, path, true);
    }

    static void validateMessage(JsonNode value, Contract<?> contract) {
        String path = PATHS.get(contract.payloadType());
        if (path == null || !path.startsWith("payload/")) {
            throw new ContractException();
        }
        validate(value, "message/" + path.substring("payload/".length()));
    }

    private static void validate(JsonNode value, String path) {
        validate(value, path, false);
    }

    private static void validate(JsonNode value, String path, boolean diagnostic) {
        JsonSchema schema = CACHE.computeIfAbsent(path, key -> FACTORY.getSchema(document(key),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()));
        var errors = schema.validate(value);
        if (!errors.isEmpty()) {
            if (diagnostic) throw new ContractException(errors.toString());
            throw new ContractException();
        }
    }

    private static JsonNode document(String path) {
        try (var stream = ContractSchemas.class.getResourceAsStream("/schemas/" + path + ".schema.json")) {
            if (stream == null) throw new IllegalStateException("Missing packaged contract schema");
            return JSON.readTree(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load packaged contract schema");
        }
    }
}
