---
status: valide_conceptuellement
schemaVersion: "1.0"
contrat: RESPONSE_PLAN
---

# ResponsePlan — Writer → Orchestrator → Scribe

> **Décision validée (13 novembre 2026).** Source métier : Obsidian d'origine + validations de conception dans la conversation. Ce document constitue la référence de contrat V1. Les adaptations Java restent à intégrer dans le dépôt.

**Producteur / circulation :** [[WriterAgent]] → [[OrchestratorAgent]] → [[ScribeAgent]]. Les métadonnées `AgentMessage<T>` (`messageId`, `correlationId`, `messageType`, `schemaVersion`, `producerAgentId`, `createdAt`) sont renseignées par Java, jamais par le LLM. `schemaVersion` concerne chaque type de message indépendamment.

## JSON complet (exemple)

```json
{
  "metadata": {
    "messageId": "550e8400-e29b-41d4-a716-446655440001",
    "correlationId": "660e8400-e29b-41d4-a716-446655440001",
    "messageType": "RESPONSE_PLAN",
    "schemaVersion": "1.0",
    "producerAgentId": "ResearchWriter",
    "createdAt": "2026-11-13T09:00:00Z"
  },
  "payload": {
    "planId": "plan-001",
    "agentId": "ResearchWriter",
    "emailId": "email-001",
    "responseIntent": "ANSWER_INFORMATION_REQUEST",
    "factsToCommunicate": [
      {
        "text": "La réunion est prévue le 12 novembre.",
        "sourceRef": "project-orion-calendar"
      }
    ],
    "missingInformation": [],
    "requiresClarification": false,
    "replyStructure": [
      "salutation",
      "answer",
      "closing"
    ],
    "language": "fr",
    "tone": "professional"
  }
}
```

## Contrat et règles

- Le **LLM Writer génère exactement quatre champs cognitifs** : `responseIntent`, `factsToCommunicate` (objets `{text,sourceRef}`), `missingInformation`, `requiresClarification`.
- Java ajoute `planId`, `agentId`, `emailId`, `replyStructure`, `language`, `tone` et l'enveloppe, après vérification des valeurs de profil.
- Intentions : `ANSWER_INFORMATION_REQUEST`, `PROVIDE_DOCUMENT`, `ACKNOWLEDGE_REQUEST`, `REQUEST_CLARIFICATION`.
- Si `requiresClarification=true`, l'intention vaut `REQUEST_CLARIFICATION` et `missingInformation` contient **une ou plusieurs questions précises**. Java transforme ce résultat en `DiscussionPlan<ResponsePlan>` ; l'Orchestrator suspend, pose les questions à l'utilisateur, puis reprend. **Le Scribe ne reçoit jamais un plan incomplet**.
- Sinon `requiresClarification=false`, l'intention n'est pas `REQUEST_CLARIFICATION`, `missingInformation=[]`.
- Chaque fait est une proposition avec texte et `sourceRef` : Java doit vérifier que la source existe, est autorisée et justifie le fait ; une hypothèse non confirmée dans K_base ne constitue pas une preuve.
- `factsToCommunicate=[]` est autorisé pour `ACKNOWLEDGE_REQUEST` et pour une clarification. Ne pas imposer au LLM d'inventer un fait.
- `PROVIDE_DOCUMENT` ne constitue ni une pièce jointe ni une autorisation de transmettre un document. La disponibilité et les permissions sont vérifiées séparément.
- Nouvelle version du plan → invalidation des brouillons dépendants.
- Schéma de **sortie brute LLM** : `MAS/docs/contracts/schemas/llm/response-plan.schema.json`. 

## Schema / exemples

- Schéma complet : `MAS/docs/contracts/schemas/message/response-plan.schema.json`.
- Schéma payload : `MAS/docs/contracts/schemas/payload/response-plan.schema.json`.
- Exemple complet : `MAS/docs/contracts/examples/valid/response-plan.ready.json`.

### Exemple de clarification

```json
{
  "metadata": {
    "messageId": "550e8400-e29b-41d4-a716-446655440001",
    "correlationId": "660e8400-e29b-41d4-a716-446655440001",
    "messageType": "RESPONSE_PLAN",
    "schemaVersion": "1.0",
    "producerAgentId": "ResearchWriter",
    "createdAt": "2026-11-13T09:00:00Z"
  },
  "payload": {
    "planId": "plan-002",
    "agentId": "AdministrationWriter",
    "emailId": "email-001",
    "responseIntent": "REQUEST_CLARIFICATION",
    "factsToCommunicate": [],
    "missingInformation": [
      "Quelle référence de dossier faut-il utiliser ?"
    ],
    "requiresClarification": true,
    "replyStructure": [
      "salutation",
      "answer",
      "closing"
    ],
    "language": "fr",
    "tone": "professional"
  }
}
```
