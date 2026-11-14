package fr.mas.email.llm.ollama;

import fr.mas.email.contract.*;
import fr.mas.email.llm.LlmRequest;
import fr.mas.email.result.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vérification manuelle, hors suite par défaut : Ollama et le modèle doivent déjà être disponibles.
 * Données synthétiques uniquement ; aucun téléchargement ni email réel.
 */
@EnabledIfSystemProperty(named = "ollama.integration", matches = "true")
class OllamaLlmGatewayIT {
    @Test
    void installedModelReturnsAnExistingDto() {
        var server = URI.create(System.getProperty("ollama.url", OllamaLlmGateway.LOCAL_SERVER.toString()));
        var model = System.getProperty("ollama.model", "qwen3.5:4b-q4_K_M");
        var timeout = Duration.ofSeconds(Long.getLong("ollama.timeoutSeconds", 120L));
        var request = new LlmRequest<>(
                "Rédige un très court accusé de réception en français. Retourne uniquement subject et body.",
                "Exemple fictif : Bonjour, merci de confirmer la réception de mon message.",
                Contract.DRAFT_CONTENT, timeout);
        try (var gateway = new OllamaLlmGateway(server, model, new JsonCodec())) {
            var result = gateway.generate(request, TaskContext.start(), "OllamaIntegrationTest");
            var success = assertInstanceOf(Success.class, result, () -> "Expected a structured success, got: " + result);
            var draft = assertInstanceOf(DraftContent.class, success.value());
            assertFalse(draft.subject().isBlank());
            assertFalse(draft.body().isBlank());
        }
    }
}
