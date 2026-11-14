---
status: valide_conceptuellement
schemaVersion: "3.0"
contrat: EMAIL_ANALYSIS
---

# EmailAnalysis — Parser → Orchestrator

> Le contrat `EMAIL_ANALYSIS` 3.0 contient neuf champs cognitifs. `requestedActions`, introduit en 2.6, reste obligatoire.

**Producteur / circulation :** [[ParserAgent]] → [[OrchestratorAgent]]. Les métadonnées `AgentMessage<T>` (`messageId`, `correlationId`, `messageType`, `schemaVersion`, `producerAgentId`, `createdAt`) sont renseignées par Java, jamais par le LLM. `schemaVersion` concerne chaque type de message indépendamment.

## JSON complet (exemple)

```json
{
  "metadata": {
    "messageId": "550e8400-e29b-41d4-a716-446655440001",
    "correlationId": "660e8400-e29b-41d4-a716-446655440001",
    "messageType": "EMAIL_ANALYSIS",
    "schemaVersion": "3.0",
    "producerAgentId": "ParserAgent",
    "createdAt": "2026-11-13T09:00:00Z"
  },
  "payload": {
    "emailId": "email-001",
    "threadId": "thread-007",
    "language": "fr",
    "intent": "REQUEST_INFORMATION",
    "topics": [
      "RESEARCH_PROJECT",
      "MEETING"
    ],
    "entities": [
      {
        "type": "PROJECT",
        "value": "ORION"
      }
    ],
    "requestedInformation": [
      "next_meeting_date"
    ],
    "requestedActions": [],
    "senderClaims": [
      "L'expéditeur pense que la salle habituelle est peut-être indisponible."
    ],
    "uncertainties": [
      "La disponibilité de la salle habituelle reste incertaine."
    ],
    "evidence": [
      {
        "field": "requestedInformation",
        "text": "Peux-tu me dire à quelle date aura lieu la prochaine réunion du projet ORION ?"
      },
      {
        "field": "uncertainties",
        "text": "Je pense que la salle habituelle est peut-être indisponible."
      }
    ]
  }
}
```

## Contrat et règles

- Le LLM produit `language`, `intent`, `topics`, `entities`, `requestedInformation`, `requestedActions`, `senderClaims`, `uncertainties` et `evidence`.
- `requestedInformation` recueille les questions et confirmations, y compris le statut d'une tâche déjà réalisée. « As-tu réservé une piste ? » relève de ce champ.
- `requestedActions` recueille les opérations explicitement demandées. « Peux-tu réserver une piste ? » peut produire `{"action":"RESERVE_BOWLING","description":"Réserver une piste de bowling","deadline":null}`. L'identifiant utilise le format ASCII `UPPER_SNAKE_CASE`, la description est non vide et l'échéance est une chaîne ou `null`. Les trois clés sont obligatoires. La liste peut être vide et doit toujours être présente.
- `senderClaims` recueille les déclarations importantes attribuées à l'expéditeur, dont ses hypothèses, conditions et risques annoncés. `uncertainties` exprime les ambiguïtés et hypothèses non confirmées. Ces deux champs restent des listes de chaînes.
- `evidence` contient des objets `{field,text}`. Le schéma fixe les champs admissibles et Java exige une citation exacte et continue du texte source.
- Java ajoute `emailId` et `threadId` au payload. L'Orchestrator crée l'enveloppe avec ses métadonnées, dont `messageId` et `schemaVersion`. Le lecteur 3.0 rejette les messages des versions 1.0 et 2.6.
- Ollama reçoit le schéma de contenu comme contrainte de génération. Le prompt système définit l'interprétation métier. Java contrôle la structure et les citations après génération. Les scénarios sur des emails représentatifs évaluent l'interprétation et l'exhaustivité. Un échec de validation produit un [[errorJSON]].

## Schema / exemples

- `docs/schemas/llm/email-analysis.schema.json` définit le contenu brut V3.0 envoyé à Ollama dans `format`.
- `docs/schemas/payload/email-analysis.schema.json` couvre le contenu enrichi par Java.
- `docs/schemas/message/email-analysis.schema.json` couvre l'enveloppe et le payload.
- `docs/examples/valid/email-analysis.success.json` illustre le résultat complet pour ORION.

## Évolution et clarifications

La version 1.0 définissait neuf champs cognitifs. La version 2.6 en comptait dix
après l'ajout de `requestedActions`. À cette époque, `missingInformation` listait
les clés des demandes dont la réponse était absente du message. Le champ a été
supprimé en 3.0 après des omissions répétées par le modèle. Java transmet les
demandes extraites dans `requestedInformation`.

Le Writer consulte les connaissances auxquelles il a accès. Une réponse absente
du message peut déjà s'y trouver. Le champ `missingInformation` de [[ResponsePlan]]
contient les questions que le Writer juge encore nécessaires pour préparer une
réponse. Java les transmet à l'Orchestrator dans un `DiscussionPlan` lorsque le
plan demande une clarification.
