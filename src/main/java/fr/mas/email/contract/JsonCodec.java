package fr.mas.email.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Strict JSON boundary. No polymorphic default typing or scalar coercion. */
public final class JsonCodec {
    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public String write(Object value) {
        Checks.required(value, "value");
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ContractException();
        }
    }

    public String writePretty(Object value) {
        Checks.required(value, "value");
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ContractException();
        }
    }

    public <T> T read(String json, Class<T> type) {
        var node = tree(json);
        // Typed construction checks business combinations before the schema's equivalent if/then rules,
        // preserving INVALID_OUTPUT versus malformed/missing/wrongly typed JSON (INVALID_JSON).
        T value = read(json, mapper.constructType(type));
        ContractSchemas.validatePayload(node, type);
        return value;
    }

    public <T> T readGenerated(String raw, Class<T> type) {
        try {
            var node = mapper.readTree(raw);
            if (type == CorrectionPlan.class) GeneratedCorrectionNormalizer.normalize(node);
            T value = mapper.treeToValue(node, type);
            if (value == null) throw new ContractException("Expected a JSON object");
            ContractSchemas.validateGenerated(node, type);
            return value;
        } catch (JsonProcessingException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof InvalidOutputException invalid) throw invalid;
            }
            throw new ContractException(e.getOriginalMessage());
        }
    }

    public <T> AgentMessage<T> readMessage(String json, Contract<T> contract) {
        var node = tree(json);
        JavaType type = mapper.getTypeFactory()
                .constructParametricType(AgentMessage.class, contract.payloadType());
        AgentMessage<T> message = read(json, type);
        ContractSchemas.validateMessage(node, contract);
        try {
            contract.validate(message);
            return message;
        } catch (IllegalArgumentException e) {
            throw new ContractException();
        }
    }

    private <T> T read(String json, JavaType type) {
        try {
            T value = mapper.readValue(Checks.text(json, "json"), type);
            return Checks.required(value, "JSON value");
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof InvalidOutputException invalid) throw invalid;
            }
            throw new ContractException();
        }
    }

    private com.fasterxml.jackson.databind.JsonNode tree(String json) {
        try {
            return Checks.required(mapper.readTree(Checks.text(json, "json")), "JSON value");
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException e) {
            throw new ContractException();
        }
    }
}
