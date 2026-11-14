package fr.mas.email;

import com.fasterxml.jackson.databind.json.JsonMapper;
import fr.mas.email.contract.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractTest {
    private final JsonCodec json = new JsonCodec();
    private final Instant now = Instant.parse("2026-11-09T10:00:00Z");
    private final MessageFactory messages = new MessageFactory(Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void allBusinessContractsRoundTripWithTypedEnvelopes() {
        roundTrip(Contract.EMAIL_ANALYSIS, TestData.analysis("fr", "REQUEST_INFORMATION", List.of("RESEARCH")));
        roundTrip(Contract.ROUTE_DECISION, TestData.route("ResearchWriter", List.of("Research topic")));
        roundTrip(Contract.RESPONSE_PLAN, TestData.plan(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                List.of(new ResponsePlan.Fact("Meeting on Monday", "calendar"))));
        roundTrip(Contract.DRAFT_RESPONSE, TestData.draft("Re: Meeting", "Bonjour"));
    }

    @Test
    void responsePlanSerializesInSchemaOrder() throws Exception {
        var order = List.of("responseIntent", "requiresClarification", "missingInformation", "factsToCommunicate");
        var factOrder = List.of("sourceRef", "text");
        var content = TestData.planContent(ResponsePlan.Intent.ANSWER_INFORMATION_REQUEST,
                List.of(new ResponsePlan.Fact("La réunion est prévue lundi.", "domainKnowledge")));
        var mapper = new JsonMapper();
        var llmJson = mapper.readTree(json.write(content));
        var enrichedJson = mapper.readTree(json.write(content.enrich("Writer", "email-001", List.of(), "fr", "neutral")));
        assertEquals(order, llmJson.properties().stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of("language", "tone", "replyStructure", "responseIntent", "requiresClarification",
                        "missingInformation", "factsToCommunicate", "agentId", "planId", "emailId"),
                enrichedJson.properties().stream().map(Map.Entry::getKey).toList());
        for (var plan : List.of(llmJson, enrichedJson)) {
            assertEquals(factOrder, plan.at("/factsToCommunicate/0").properties().stream().map(Map.Entry::getKey).toList());
        }
    }

    private <T> void roundTrip(Contract<T> contract, T payload) {
        var envelope = messages.create(contract, payload, UUID.randomUUID(),
                payload instanceof ResponsePlan plan ? plan.agentId() : "test-agent");
        assertEquals(envelope, json.readMessage(json.write(envelope), contract));
        assertEquals(now, envelope.metadata().createdAt());
        assertInstanceOf(contract.payloadType(), json.readMessage(json.write(envelope), contract).payload());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "{}", "{", "[]",
        "{\"language\":\"fr\",\"intent\":\"\",\"topics\":[]}",
        "{\"language\":null,\"intent\":\"REQUEST\",\"topics\":[]}",
        "{\"language\":\"fr\",\"intent\":\"REQUEST\"}",
        "{\"language\":\"fr\",\"intent\":\"REQUEST\",\"topics\":[null]}",
        "{\"language\":\"fr\",\"intent\":\"REQUEST\",\"topics\":[\"\"]}",
        "{\"language\":\"fr\",\"intent\":\"REQUEST\",\"topics\":[],\"metadata\":{}}",
        "{\"language\":\"fr\",\"language\":\"en\",\"intent\":\"REQUEST\",\"topics\":[]}",
        "{\"language\":\"fr\",\"intent\":\"REQUEST\",\"topics\":[]} {}",
        "{\"language\":123,\"intent\":\"REQUEST\",\"topics\":[]}",
        "{\"language\":1.5,\"intent\":\"REQUEST\",\"topics\":[]}",
        "{\"language\":true,\"intent\":\"REQUEST\",\"topics\":[]}",
        "{\"language\":\"fr\",\"intent\":\"REQUEST\",\"topics\":[123]}"
    })
    void rejectsMalformedMissingBlankNullUnknownDuplicateTrailingOrCoercedFields(String input) {
        assertThrows(ContractException.class, () -> json.read(input, EmailAnalysis.class));
    }

    @Test
    void rejectsInvalidOtherContracts() {
        assertThrows(ContractException.class, () -> json.read(
                "{\"selectedAgentId\":\"\",\"reasons\":[]}", RouteDecision.class));
        assertThrows(ContractException.class, () -> json.read(
                "{\"responseIntent\":99,\"factsToCommunicate\":[]}", ResponsePlan.class));
        assertThrows(ContractException.class, () -> json.read(
                "{\"responseIntent\":\"UNKNOWN\",\"factsToCommunicate\":[]}", ResponsePlan.class));
        assertThrows(ContractException.class, () -> json.read(
                "{\"responseIntent\":\"ANSWER_INFORMATION_REQUEST\",\"factsToCommunicate\":[{\"text\":\"Fact\"}]}",
                ResponsePlan.class));
        assertThrows(ContractException.class, () -> json.read(
                "{\"subject\":\"Re\",\"body\":\" \"}", DraftResponse.class));
    }

    @Test
    void metadataIsGeneratedByJavaAndVersionIsCheckedPerContract() {
        var analysis = TestData.analysis("fr", "REQUEST", List.of());
        var first = messages.create(Contract.EMAIL_ANALYSIS, analysis, UUID.randomUUID(), "Parser");
        var second = messages.create(Contract.EMAIL_ANALYSIS, analysis, first.metadata().correlationId(), "Parser");
        assertNotEquals(first.metadata().messageId(), second.metadata().messageId());
        assertEquals(first.metadata().correlationId(), second.metadata().correlationId());
        assertEquals("3.0", first.metadata().schemaVersion());
        assertEquals("3.0", Contract.EMAIL_ANALYSIS_CONTENT.schemaVersion());
        assertThrows(ContractException.class, () -> json.readMessage(
                json.write(first).replace("\"schemaVersion\":\"3.0\"", "\"schemaVersion\":\"2.6\""),
                Contract.EMAIL_ANALYSIS));
        assertThrows(ContractException.class, () -> json.readMessage(
                json.write(first).replace("EMAIL_ANALYSIS", "DRAFT_RESPONSE"), Contract.EMAIL_ANALYSIS));
        var independentlyVersioned = new Contract<>("EMAIL_ANALYSIS", "2.0", EmailAnalysis.class);
        var future = messages.create(independentlyVersioned, analysis, UUID.randomUUID(), "Parser");
        assertEquals("2.0", future.metadata().schemaVersion());
        assertThrows(ContractException.class, () -> json.readMessage(json.write(future), independentlyVersioned));
        assertEquals("1.0", Contract.DRAFT_RESPONSE.schemaVersion());
    }

    @Test
    void collectionsAreDefensivelyCopied() {
        var topics = new ArrayList<>(List.of("ADMIN"));
        var analysis = TestData.analysis("fr", "REQUEST", topics);
        topics.clear();
        assertEquals(List.of("ADMIN"), analysis.topics());
        assertThrows(UnsupportedOperationException.class, () -> analysis.topics().add("OTHER"));
    }

    @Test
    void rejectsMetadataNullsAndDoesNotLeakInputInErrors() {
        var message = messages.create(Contract.DRAFT_RESPONSE,
                TestData.draft("Subject", "Body"), UUID.randomUUID(), "Scribe");
        assertThrows(ContractException.class, () -> json.readMessage(
                json.write(message).replace("\"producerAgentId\":\"Scribe\"", "\"producerAgentId\":null"),
                Contract.DRAFT_RESPONSE));
        var error = assertThrows(ContractException.class,
                () -> json.read("{\"secret-token\":\"password\"}", DraftResponse.class));
        assertFalse(error.toString().contains("password"));
        assertNull(error.getCause());
    }
}
