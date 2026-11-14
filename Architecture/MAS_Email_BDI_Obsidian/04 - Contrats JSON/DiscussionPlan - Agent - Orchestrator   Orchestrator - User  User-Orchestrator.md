---
status: valide_conceptuellement
schemaVersion: "1.0"
contrat: DISCUSSION_REQUEST
---

# DiscussionPlan — clarification partagée

> **Décision validée (13 novembre 2026).** Source métier : Obsidian d'origine + validations de conception dans la conversation. Ce document constitue la référence de contrat V1. Les adaptations Java restent à intégrer dans le dépôt.

**Producteur / circulation :** Tous les agents → [[OrchestratorAgent]] → utilisateur (et reprise). Les métadonnées `AgentMessage<T>` (`messageId`, `correlationId`, `messageType`, `schemaVersion`, `producerAgentId`, `createdAt`) sont renseignées par Java, jamais par le LLM. `schemaVersion` concerne chaque type de message indépendamment.

## JSON complet (exemple)

```json
{
  "metadata": {
    "messageId": "550e8400-e29b-41d4-a716-446655440001",
    "correlationId": "660e8400-e29b-41d4-a716-446655440001",
    "messageType": "DISCUSSION_REQUEST",
    "schemaVersion": "1.0",
    "producerAgentId": "AdministrationWriter",
    "createdAt": "2026-11-13T09:00:00Z"
  },
  "payload": {
    "action": "ASK_INFORMATION",
    "task": {
      "correlationId": "660e8400-e29b-41d4-a716-446655440001",
      "taskId": "880e8400-e29b-41d4-a716-446655440001",
      "attemptId": "990e8400-e29b-41d4-a716-446655440001"
    },
    "questions": [
      "Quelle référence de dossier faut-il mentionner ?"
    ],
    "reason": "La référence manque pour la réponse.",
    "memoryProposal": "ASK_SAVE_SCOPE"
  }
}
```

## Contrat et règles

- **Contrat Java commun** : `DiscussionPlan<T>(questions, reason, memoryProposal, TaskContext)`. Ce n'est **pas** un JSON supplémentaire généré par le LLM, ni un agent supplémentaire.
- `questions` est une liste non vide de questions non vides. `reason` est obligatoire. `memoryProposal` : `NONE` ou `ASK_SAVE_SCOPE` ; il ne constitue jamais une permission de stockage.
- Java dérive les questions du `RouteDecision.questionToUser`, de `ResponsePlan.missingInformation` ou de `CorrectionPlan.question`. Parser ne demande pas de clarification directement. Pour les plans sans champ mémoire, la politique Java fournit `NONE`, sauf demande de mémorisation explicite et autorisée.
- L'Orchestrator **suspend** la tâche et conserve le `TaskContext` (`correlationId`, `taskId`, `attemptId`) et le résultat d'origine. En V1, une seule réponse texte libre peut traiter plusieurs questions ; le composant peut redemander les informations encore absentes.
- **Réponse utilisateur** (DTO distinct `UserDiscussionAnswer`) : `requestMessageId`, `action=ANSWER_INFORMATION`, `answer`, `memoryChoice` (NONE/K_PERSO/K_COMMUN), `memoryAgentId`. Une réponse sans choix mémoire devient NONE côté application ; l'autorisation doit être explicite et traçable.
- `K_PERSO` exige un agent bénéficiaire autorisé ; pour `NONE` et `K_COMMUN`, `memoryAgentId=null`. Un refus de mémoire n'interdit pas d'utiliser l'information pour la réponse courante.
- L'Orchestrator contrôle puis effectue, si autorisé, l'écriture via le service prévu. **Résultat déclaré uniquement après confirmation** : `NOT_STORED`, `STORED_K_PERSO`, `STORED_K_COMMUN`. Une erreur d'écriture est `[[errorJSON]]`, pas un résultat STORED.
- La reprise contrôlée retourne à l'agent/étape appropriée. Réponse, mémorisation, reprise après incident et approbation d'envoi sont quatre décisions distinctes.
- Ici, le JSON présenté est une **sérialisation Java**, pas une sortie LLM. La forme imbriquée `task` reflète `TaskContext`.


## Schema / exemples

- Schéma complet : `MAS/docs/contracts/schemas/message/discussion-request.schema.json`.
- Schéma payload : `MAS/docs/contracts/schemas/payload/discussion-request.schema.json`.
- Exemple complet : `MAS/docs/contracts/examples/valid/discussion-request.question.json`.

### Réponse utilisateur

```json
{
  "requestMessageId": "550e8400-e29b-41d4-a716-446655440000",
  "action": "ANSWER_INFORMATION",
  "answer": "DOS-42",
  "memoryChoice": "K_PERSO",
  "memoryAgentId": "AdministrationWriter"
}
```

### Reprise

```json
{
  "action": "RESUME_WITH_INFORMATION",
  "task": {
    "correlationId": "660e8400-e29b-41d4-a716-446655440001",
    "taskId": "880e8400-e29b-41d4-a716-446655440001",
    "attemptId": "990e8400-e29b-41d4-a716-446655440001"
  },
  "answer": "DOS-42",
  "storageResult": "STORED_K_PERSO"
}
```

### Schémas complémentaires

- `MAS/docs/contracts/schemas/payload/user-discussion-answer.schema.json`
- `MAS/docs/contracts/schemas/payload/discussion-resume.schema.json`
