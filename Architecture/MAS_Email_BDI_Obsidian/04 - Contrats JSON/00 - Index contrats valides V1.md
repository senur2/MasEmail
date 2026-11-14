---
status: valide_conceptuellement
schemaVersion: "1.0"
---
# Contrats JSON V1 — index et décisions validées (13 novembre 2026)

## Contrats

- [[EmailAnalysis]] — Parser → Orchestrator, version 2.6 en phase 3.1 puis 3.0 en phase 3.1.1.
- [[RouteDecision]] — Decision → Orchestrator (routage LLM en V1).
- [[ResponsePlan]] — Writer → Orchestrator → Scribe.
- [[DraftResponse]] — Scribe → Orchestrator → utilisateur.
- [[CorrectionPlan]] — Correcting → Orchestrator.
- [[DiscussionPlan - Agent - Orchestrator   Orchestrator - User  User-Orchestrator|DiscussionPlan]] — clarification Java partagée et échange utilisateur.
- [[errorJSON|ErrorPlan]] — échecs techniques générés par Java.

## Trois niveaux à ne pas confondre

1. **Sortie brute LLM** : uniquement les champs cognitifs autorisés du composant, soumis au JSON Schema `MAS/docs/contracts/schemas/llm/` et à la validation Java.
2. **Payload métier enrichi** : ajoute les identifiants et paramètres système depuis Java ; schema dans `schemas/payload/`.
3. **AgentMessage<T>** : `{metadata,payload}` (version par contrat, corrélation et identité de producteur contrôlées par Java) ; schema dans `schemas/message/`.

Les échanges internes en mémoire Java peuvent transmettre directement `AgentResult<T>` sans sérialisation JSON. `DiscussionPlan<T>` est construit **depuis la clarification contenue dans le JSON métier**. `ErrorPlan<T>` est construit par Java en cas d'incident ; le LLM ne le génère jamais.

## Règles de sécurité et de workflow

- Orchestrator = Mediator, aucune inférence LLM ; exécution séquentielle V1.
- Pas de nouvelle tentative après incident sans décision utilisateur explicite.
- Aucun stockage durable dans K_PERSO/K_COMMUN sans choix utilisateur ; proposition de mémoire ≠ autorisation.
- Aucune approbation ou transmission d'email sans validation de la **version exacte** `metadata.messageId` du brouillon.
- Aucun fallback implicite GenericWriter ni lecture arbitraire d'un MiniWorld d'un autre agent.
- Les validateurs Java contrôlent ce que JSON Schema ne garantit pas : existence/autorisation des sourceRef, AgentRegistry, transition d'états, versions, permissions, cohérence sémantique.

## Décisions non résolues (ne pas faire inventer à Codex)

- Vocabulaires fermés exacts de `intent`, `topics`, `entities.type`, ton et structure ; conserver String en V1.
- Méthode de contrôle sémantique des faits du Scribe et de l'authenticité de sourceRef.
- Politique de stockage des croyances/propositions K_base et règles d'accès aux connaissances.
- JEV-like futur : modèle, calibration, marges d'abstention, critères et seuils.
- Politique complète d'erreurs non bloquantes, trace/retention et confirmation éventuelle d'un Writer sélectionné.

## Compatibilité avec MAS.zip

Les classes du dépôt actuel sont des **DTO minimalistes de la phase 1**. Les Java de `MAS/docs/contracts/java-proposals/` sont des **propositions, non une migration automatique**, pour préserver le code et les 53 tests existants. Intégrer en phase 2.1 avec tests de non-régression.
