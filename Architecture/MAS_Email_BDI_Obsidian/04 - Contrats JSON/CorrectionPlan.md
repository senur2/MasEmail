---
status: valide_conceptuellement
schemaVersion: "1.0"
contrat: CORRECTION_PLAN
---

# CorrectionPlan — Correcting → Orchestrator

> **Décision validée (13 novembre 2026).** Source métier : Obsidian d'origine + validations de conception dans la conversation. Ce document constitue la référence de contrat V1. Les adaptations Java restent à intégrer dans le dépôt.

**Producteur / circulation :** [[CorrectingAgent]] → [[OrchestratorAgent]]. Les métadonnées `AgentMessage<T>` (`messageId`, `correlationId`, `messageType`, `schemaVersion`, `producerAgentId`, `createdAt`) sont renseignées par Java, jamais par le LLM. `schemaVersion` concerne chaque type de message indépendamment.

## JSON complet (exemple)

```json
{
  "metadata": {
    "messageId": "550e8400-e29b-41d4-a716-446655440001",
    "correlationId": "660e8400-e29b-41d4-a716-446655440001",
    "messageType": "CORRECTION_PLAN",
    "schemaVersion": "1.0",
    "producerAgentId": "CorrectingAgent",
    "createdAt": "2026-11-13T09:00:00Z"
  },
  "payload": {
    "status": "READY",
    "target": "SCRIBE",
    "action": "REWRITE_DRAFT",
    "scope": "DRAFT",
    "instruction": "Rendre la réponse plus concise.",
    "reason": "Correction de style.",
    "question": null,
    "memoryProposal": "NONE"
  }
}
```

## Contrat et règles

- Les **8 champs cognitifs** sont produits dans un unique JSON par le LLM : `status`, `target`, `action`, `scope`, `instruction`, `reason`, `question`, `memoryProposal`. Java ajoute l'enveloppe et la référence de tâche conservée dans la coordination.
- `status=READY` : `scope` et `instruction` renseignés, `question=null`, cible différente de USER ; l'Orchestrator reprend à l'étape indiquée et invalide les résultats aval périmés.
- Couples stricts : `PARSER/REANALYZE`, `DECISION/REROUTE`, `WRITER/REVISE_PLAN`, `SCRIBE/REWRITE_DRAFT`, `USER/ASK_CLARIFICATION`.
- `status=NEEDS_CLARIFICATION` : `target=USER`, `action=ASK_CLARIFICATION`, `question` non vide ; `scope` et `instruction` **peuvent être `null`** seulement dans ce cas. La question est convertie par Java en `DiscussionPlan<CorrectionPlan>` partagé.
- `scope=DRAFT`, `THREAD`, `GLOBAL` : la portée n'est **jamais une permission**. `GLOBAL` et `memoryProposal=ASK_SAVE_SCOPE` nécessitent un choix explicite pour toute mémorisation durable.
- **Dans tous les cas**, la main revient à l'Orchestrator ; `ASK_SAVE_SCOPE` ne change pas le routage, il déclenche uniquement le protocole séparé de choix de mémoire. Corriger le brouillon courant n'autorise pas une préférence globale durable.
- `reason` reste obligatoire et non vide. Si plusieurs étapes sont fautives, cibler la première étape responsable et invalider les résultats dépendants.
- Timeout / JSON invalide / crash → `[[errorJSON]]`, pas une fausse clarification.
- Schéma de sortie brute : `MAS/docs/contracts/schemas/llm/correction-plan.schema.json`.


## Schema / exemples

- Schéma complet : `MAS/docs/contracts/schemas/message/correction-plan.schema.json`.
- Schéma payload : `MAS/docs/contracts/schemas/payload/correction-plan.schema.json`.
- Exemple complet : `MAS/docs/contracts/examples/valid/correction-plan.ready.json`.

### Exemple de clarification

```json
{
  "metadata": {
    "messageId": "550e8400-e29b-41d4-a716-446655440001",
    "correlationId": "660e8400-e29b-41d4-a716-446655440001",
    "messageType": "CORRECTION_PLAN",
    "schemaVersion": "1.0",
    "producerAgentId": "CorrectingAgent",
    "createdAt": "2026-11-13T09:00:00Z"
  },
  "payload": {
    "status": "NEEDS_CLARIFICATION",
    "target": "USER",
    "action": "ASK_CLARIFICATION",
    "scope": null,
    "instruction": null,
    "reason": "La correction est trop vague.",
    "question": "Souhaites-tu modifier les faits ou seulement la formulation ?",
    "memoryProposal": "NONE"
  }
}
```
