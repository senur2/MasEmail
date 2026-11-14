---
status: valide_conceptuellement
schemaVersion: "1.0"
contrat: ROUTE_DECISION
---

# RouteDecision — Decision → Orchestrator

> **Décision validée (13 novembre 2026).** Source métier : Obsidian d'origine + validations de conception dans la conversation. Ce document constitue la référence de contrat V1. Les adaptations Java restent à intégrer dans le dépôt.

**Producteur / circulation :** [[DecisionAgent]] → [[OrchestratorAgent]]. Les métadonnées `AgentMessage<T>` (`messageId`, `correlationId`, `messageType`, `schemaVersion`, `producerAgentId`, `createdAt`) sont renseignées par Java, jamais par le LLM. `schemaVersion` concerne chaque type de message indépendamment.

## JSON complet (exemple)

```json
{
  "metadata": {
    "messageId": "550e8400-e29b-41d4-a716-446655440001",
    "correlationId": "660e8400-e29b-41d4-a716-446655440001",
    "messageType": "ROUTE_DECISION",
    "schemaVersion": "1.0",
    "producerAgentId": "DecisionAgent",
    "createdAt": "2026-11-13T09:00:00Z"
  },
  "payload": {
    "emailId": "email-001",
    "selectedAgentId": "ResearchWriter",
    "candidateAgents": [
      "ResearchWriter",
      "TeacherWriter"
    ],
    "decisionStatus": "SELECTED",
    "reasons": [
      "La demande concerne une réunion de recherche."
    ],
    "confidence": null,
    "requiresUserConfirmation": false,
    "questionToUser": null
  }
}
```

## Contrat et règles

- **LLM V1** : `selectedAgentId`, `candidateAgents`, `decisionStatus`, `reasons`, `confidence`, `requiresUserConfirmation`, `questionToUser`. Le **système** ajoute `emailId` et l'enveloppe.
- La V1 appelle le LLM pour sélectionner le Writer ; contrairement à l'ancienne note « V1 à règles ». Java vérifie les identifiants contre `[[AgentRegistry]]`, l'éligibilité et la disponibilité. Les candidats autorisés sont passés au modèle ; aucun fallback automatique vers `GenericWriter`.
- `decisionStatus=SELECTED` : `selectedAgentId` non nul et présent parmi les candidats autorisés. Avec `requiresUserConfirmation=false`, `questionToUser=null` et attribution possible. Avec `true`, question non vide et **aucune attribution avant réponse utilisateur**.
- `decisionStatus=NEEDS_CLARIFICATION` : `selectedAgentId=null`, `requiresUserConfirmation=true`, `questionToUser` non vide. Une absence de candidats est admise seulement dans ce cas.
- `reasons` contient au moins une justification non vide. `confidence` existe mais vaut **toujours `null` en V1** (pas de score fabriqué).
- Java convertit la clarification incluse **dans ce même JSON** en `[[DiscussionPlan - Agent - Orchestrator   Orchestrator - User  User-Orchestrator|DiscussionPlan]]` ; il n'y a pas de second JSON LLM.
- Les futures approches JEV-like peuvent calculer scores et abstention sans modifier l'API ni le statut `NEEDS_CLARIFICATION`. Les algorithmes/seuils restent à décider.
- Schéma de **sortie brute LLM** : `MAS/docs/contracts/schemas/llm/route-decision.schema.json`. 

## Schema / exemples

- Schéma complet : `MAS/docs/contracts/schemas/message/route-decision.schema.json`.
- Schéma payload : `MAS/docs/contracts/schemas/payload/route-decision.schema.json`.
- Exemple complet : `MAS/docs/contracts/examples/valid/route-decision.selected.json`.

### Exemple de clarification

```json
{
  "metadata": {
    "messageId": "550e8400-e29b-41d4-a716-446655440001",
    "correlationId": "660e8400-e29b-41d4-a716-446655440001",
    "messageType": "ROUTE_DECISION",
    "schemaVersion": "1.0",
    "producerAgentId": "DecisionAgent",
    "createdAt": "2026-11-13T09:00:00Z"
  },
  "payload": {
    "emailId": "email-001",
    "selectedAgentId": null,
    "candidateAgents": [
      "ResearchWriter",
      "TeacherWriter"
    ],
    "decisionStatus": "NEEDS_CLARIFICATION",
    "reasons": [
      "La demande concerne une réunion de recherche."
    ],
    "confidence": null,
    "requiresUserConfirmation": true,
    "questionToUser": "Cette demande concerne-t-elle le projet de recherche ou le cours ?"
  }
}
```
