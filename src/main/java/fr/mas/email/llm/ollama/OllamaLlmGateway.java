package fr.mas.email.llm.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import fr.mas.email.contract.Checks;
import fr.mas.email.contract.Contract;
import fr.mas.email.contract.ContractException;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.ContractSchemas;
import fr.mas.email.contract.InvalidOutputException;
import fr.mas.email.llm.LlmGateway;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Adaptateur HTTP Ollama interchangeable avec FakeLlmGateway via LlmGateway.
 * Un appel synchrone pour l'agent, une seule requête, sans retry ni téléchargement.
 * Le client appartient à cet adaptateur : le fermer à l'arrêt de l'application.
 */
public final class OllamaLlmGateway implements LlmGateway, AutoCloseable {
    public static final URI LOCAL_SERVER = URI.create("http://127.0.0.1:11434");

    private final URI chatEndpoint;
    private final String model;
    private final JsonCodec json;
    private final ChatOptions options;
    private final HttpClient client;
    // L'enveloppe technique Ollama peut évoluer ; seul son contenu métier passe par JsonCodec.
    private final JsonMapper transportJson = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public OllamaLlmGateway(String model, JsonCodec json) {
        this(LOCAL_SERVER, model, json);
    }

    public OllamaLlmGateway(URI server, String model, JsonCodec json) {
        this(server, model, json, new ChatOptions(false, null));
    }

    /** Null lets Ollama use its own default for the corresponding option. */
    public record ChatOptions(Boolean think, Integer numPredict) {
        public ChatOptions {
            if (numPredict != null && numPredict <= 0) {
                throw new IllegalArgumentException("numPredict must be positive");
            }
        }
    }

    public OllamaLlmGateway(URI server, String model, JsonCodec json, ChatOptions options) {
        Checks.required(server, "server");
        String scheme = server.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || server.getHost() == null || server.getUserInfo() != null
                || server.getQuery() != null || server.getFragment() != null
                || !(server.getPath().isEmpty() || server.getPath().equals("/"))) {
            throw new IllegalArgumentException("server must be an HTTP(S) origin without credentials, path or query");
        }
        this.chatEndpoint = server.resolve("/api/chat");
        this.model = Checks.text(model, "model");
        this.json = Checks.required(json, "json");
        this.options = Checks.required(options, "options");
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public <T> AgentResult<T> generate(LlmRequest<T> request, TaskContext task, String component) {
        Checks.required(request, "request");
        Checks.required(task, "task");
        Checks.text(component, "component");
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            JsonNode schema = ContractSchemas.llmSchema(request.expectedContract());
            String instructions = request.instructions();
            if (!request.expectedContract().equals(Contract.EMAIL_ANALYSIS_CONTENT)
                    && !request.expectedContract().equals(Contract.ROUTE_DECISION_CONTENT)
                    && !request.expectedContract().equals(Contract.RESPONSE_PLAN_CONTENT)
                    && !request.expectedContract().equals(Contract.DRAFT_CONTENT)) {
                instructions += "\nReturn only one JSON object matching the following schema, without Markdown."
                        + "\nDo not add message metadata or technical error fields.\n" + json.write(schema);
            }
            Map<String, Object> generation = new LinkedHashMap<>();
            generation.put("temperature", request.temperature());
            if (options.numPredict() != null) generation.put("num_predict", options.numPredict());
            Map<String, Object> chat = new LinkedHashMap<>(Map.of(
                    "model", model,
                    "stream", false,
                    "format", schema,
                    "options", generation,
                    "messages", List.of(
                            Map.of("role", "system", "content", instructions),
                            Map.of("role", "user", "content", request.input()))));
            if (options.think() != null) chat.put("think", options.think());
            String body = json.write(chat);
            HttpRequest httpRequest = HttpRequest.newBuilder(chatEndpoint)
                    .timeout(request.timeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            long startedAt = System.nanoTime();
            pending = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // Le délai couvre aussi la réception intégrale du corps, pas uniquement les en-têtes.
            long remaining = timeoutNanos(request.timeout()) - (System.nanoTime() - startedAt);
            HttpResponse<String> response = pending.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
            return decode(response, request, task, component);
        } catch (TimeoutException e) {
            if (pending != null) pending.cancel(true);
            return failure(ErrorCode.LLM_TIMEOUT, request, component, task);
        } catch (InterruptedException e) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            return failure(ErrorCode.INTERNAL_ERROR, request, component, task);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) {
                return failure(ErrorCode.LLM_TIMEOUT, request, component, task);
            }
            if (e.getCause() instanceof IOException) {
                return failure(ErrorCode.LLM_UNAVAILABLE, request, component, task);
            }
            return failure(ErrorCode.INTERNAL_ERROR, request, component, task);
        } catch (ContractException | JsonProcessingException e) {
            return failure(ErrorCode.INVALID_JSON, request, component, task);
        } catch (InvalidOutputException e) {
            return failure(ErrorCode.INVALID_OUTPUT, request, component, task);
        } catch (RuntimeException e) {
            return failure(ErrorCode.INTERNAL_ERROR, request, component, task);
        }
    }

    private <T> AgentResult<T> decode(HttpResponse<String> response, LlmRequest<T> request,
                                      TaskContext task, String component) throws JsonProcessingException {
        int status = response.statusCode();
        if (status == 408 || status == 504) {
            return failure(ErrorCode.LLM_TIMEOUT, request, component, task);
        }
        if (status != 200) return failure(ErrorCode.LLM_UNAVAILABLE, request, component, task);

        JsonNode envelope = transportJson.readTree(response.body());
        if (envelope == null || !envelope.isObject()) throw new ContractException();
        if (envelope.hasNonNull("error")) {
            return failure(ErrorCode.LLM_UNAVAILABLE, request, component, task);
        }
        JsonNode done = envelope.path("done");
        if (!done.isBoolean()) throw new ContractException();
        if (!done.booleanValue() || "length".equals(envelope.path("done_reason").asText())) {
            return failure(ErrorCode.INVALID_OUTPUT, request, component, task);
        }
        JsonNode message = envelope.path("message");
        JsonNode content = message.path("content");
        if (!message.isObject() || !"assistant".equals(message.path("role").asText())
                || !content.isTextual()) {
            throw new ContractException();
        }
        if (content.textValue().isBlank()) return failure(ErrorCode.EMPTY_RESULT, request, component, task);
        // Les métriques et le champ thinking éventuel ne font jamais partie du DTO métier.
        String raw = content.textValue();
        try {
            return new Success<>(json.readGenerated(raw, request.expectedContract().payloadType()), raw);
        } catch (ContractException | InvalidOutputException e) {
            var code = e instanceof ContractException ? ErrorCode.INVALID_JSON : ErrorCode.INVALID_OUTPUT;
            return Errors.forAgent(Errors.validation(code, "OllamaLlmGateway", task, e, raw), component,
                    request.expectedContract().stage(), "OllamaLlmGateway", request.expectedContract().messageType());
        }
    }

    private static <T> ErrorPlan<T> failure(ErrorCode code, LlmRequest<T> request, String agentId, TaskContext task) {
        return Errors.forAgent(Errors.create(code, "OllamaLlmGateway", task), agentId,
                request.expectedContract().stage(), "OllamaLlmGateway", request.expectedContract().messageType());
    }

    private static long timeoutNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException tooLarge) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
