---
status: valide_conceptuellement
schemaVersion: "1.0"
contrat: DRAFT_RESPONSE
---

# DraftResponse — Scribe → Orchestrator → utilisateur

> **Décision validée (13 novembre 2026).** Source métier : Obsidian d'origine + validations de conception dans la conversation. Ce document constitue la référence de contrat V1. Les adaptations Java restent à intégrer dans le dépôt.

**Producteur / circulation :** [[ScribeAgent]] → [[OrchestratorAgent]] → utilisateur. Les métadonnées `AgentMessage<T>` (`messageId`, `correlationId`, `messageType`, `schemaVersion`, `producerAgentId`, `createdAt`) sont renseignées par Java, jamais par le LLM. `schemaVersion` concerne chaque type de message indépendamment.

## JSON complet (exemple)

```json
{
  "metadata": {
    "messageId": "550e8400-e29b-41d4-a716-446655440001",
    "correlationId": "660e8400-e29b-41d4-a716-446655440001",
    "messageType": "DRAFT_RESPONSE",
    "schemaVersion": "1.0",
    "producerAgentId": "ScribeAgent",
    "createdAt": "2026-11-13T09:00:00Z"
  },
  "payload": {
    "emailId": "email-001",
    "planId": "plan-001",
    "subject": "Re: Réunion du projet ORION",
    "body": "Bonjour,\n\nLa prochaine réunion est prévue le 12 novembre.\n\nBien cordialement,",
    "status": "DRAFT"
  }
}
```

## Contrat et règles

- Le **LLM Scribe génère uniquement** `subject` et `body`, non vides et sans contenu ajouté hors du plan. Schéma brut : `MAS/docs/contracts/schemas/llm/draft-content.schema.json`.
- **Java ajoute** `emailId`, `planId`, `status="DRAFT"` et l'enveloppe. Le Scribe n'a pas le droit de générer un statut APPROVED/SENT ni d'envoyer un email.
- `planId` doit correspondre au **plan courant validé**, dont les faits et contraintes doivent être respectés ; la conformité sémantique exige des contrôles Java distincts du simple schéma JSON.
- Aucun nouveau fait, engagement, décision ou document ne peut être introduit par le Scribe.
- Le Scribe ne reçoit que des ResponsePlan prêts à rédiger. Résultat vide, JSON invalide, timeout → `[[errorJSON]]`.
- **Approbation liée au `metadata.messageId` exact du brouillon**. Deux brouillons peuvent avoir le même `planId` après correction stylistique, mais pas le même `messageId`. Toute modification invalide l'approbation de la version précédente.
- `DRAFT` ne prouve ni approbation ni envoi. La validation utilisateur et la transmission réelle sont deux étapes distinctes.


## Schema / exemples

- Schéma complet : `MAS/docs/contracts/schemas/message/draft-response.schema.json`.
- Schéma payload : `MAS/docs/contracts/schemas/payload/draft-response.schema.json`.
- Exemple complet : `MAS/docs/contracts/examples/valid/draft-response.draft.json`.
