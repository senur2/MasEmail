---
status: valide_conceptuellement
schemaVersion: "1.0"
contrat: ERROR_PLAN
---

# errorJSON / ErrorPlan — échec technique transversal

> **Décision validée (13 novembre 2026).** Source métier : Obsidian d'origine + validations de conception dans la conversation. Ce document constitue la référence de contrat V1. Les adaptations Java restent à intégrer dans le dépôt.

**Producteur / circulation :** Contrôle Java → [[OrchestratorAgent]] → utilisateur. Les métadonnées `AgentMessage<T>` (`messageId`, `correlationId`, `messageType`, `schemaVersion`, `producerAgentId`, `createdAt`) sont renseignées par Java, jamais par le LLM. `schemaVersion` concerne chaque type de message indépendamment.

## JSON complet (exemple)

```json
{
  "metadata": {
    "messageId": "550e8400-e29b-41d4-a716-446655440001",
    "correlationId": "660e8400-e29b-41d4-a716-446655440001",
    "messageType": "ERROR_PLAN",
    "schemaVersion": "1.0",
    "producerAgentId": "OrchestratorAgent",
    "createdAt": "2026-11-13T09:00:00Z"
  },
  "payload": {
    "errorId": "770e8400-e29b-41d4-a716-446655440001",
    "task": {
      "correlationId": "660e8400-e29b-41d4-a716-446655440001",
      "taskId": "880e8400-e29b-41d4-a716-446655440001",
      "attemptId": "990e8400-e29b-41d4-a716-446655440001"
    },
    "agentId": "ScribeAgent",
    "component": "OllamaLlmGateway",
    "stage": "SCRIBE",
    "reportedBy": "EXECUTION_CONTROL",
    "code": "LLM_TIMEOUT",
    "expectedResult": "DraftContent avec subject et body",
    "observedProblem": "Délai de réponse du LLM dépassé.",
    "traceRef": null,
    "status": "STOPPED",
    "requiresUserDecision": true,
    "occurredAt": "2026-11-13T09:00:01Z"
  }
}
```

## Contrat et règles

- Nom historique `errorJSON` conservé pour les liens Obsidian ; **type Java unique** `ErrorPlan<T>` pour tous les agents et services. **Jamais généré par un LLM**.
- Payload : `errorId`, `task` (`TaskContext`), `agentId` nullable, `component`, `stage`, `reportedBy`, `code`, `expectedResult`, `observedProblem`, `traceRef` nullable, `status=STOPPED`, `requiresUserDecision=true`, `occurredAt` ; métadonnées dans l'enveloppe.
- `observedProblem` remplace `technicalMessage` pour éviter une duplication ; message diagnostique contrôlé, sans secrets, prompts complets ni contenu brut sensible.
- Codes V1 fusionnés : `LLM_TIMEOUT`, `LLM_UNAVAILABLE`, `INVALID_JSON`, `INVALID_OUTPUT`, `EMPTY_RESULT`, `NO_RESULT`, `AGENT_FAILURE`, `TASK_NOT_COMPLETED`, `PERMISSION_DENIED`, `FILE_ACCESS_ERROR`, `INTERNAL_ERROR`, `NOT_IMPLEMENTED`.
- Utiliser le code **le plus précis** : timeout identifiable → LLM_TIMEOUT plutôt que NO_RESULT ; JSON syntaxiquement valide mais règle métier violée → INVALID_OUTPUT.
- `stage` = PARSER, DECISION, WRITER, SCRIBE, CORRECTING, ORCHESTRATION, KNOWLEDGE. `reportedBy` = AGENT ou EXECUTION_CONTROL.
- L'Orchestrator bloque l'étape et les résultats dépendants ; **aucune nouvelle tentative automatique**. Après décision explicite de reprise, `TaskContext.nextAttempt()` conserve `taskId` et `correlationId`, crée un nouvel `attemptId`.
- Le résultat `ErrorPlan<T>` circule **en Java sans exiger de sérialisation JSON** ; cet exemple illustre l'export/journal quand il existe.
- `traceRef` ne désigne une trace que si elle existe réellement, sinon `null`. Ne jamais annoncer une écriture réussie si la persistance a échoué.
- Clarification normale → `[[DiscussionPlan - Agent - Orchestrator   Orchestrator - User  User-Orchestrator|DiscussionPlan]]`, pas un incident. `NOT_IMPLEMENTED` reste un code de développement, pas un repli utilisateur.


## Schema / exemples

- Schéma complet : `MAS/docs/contracts/schemas/message/error-plan.schema.json`.
- Schéma payload : `MAS/docs/contracts/schemas/payload/error-plan.schema.json`.
- Exemple complet : `MAS/docs/contracts/examples/valid/error-plan.timeout.json`.
