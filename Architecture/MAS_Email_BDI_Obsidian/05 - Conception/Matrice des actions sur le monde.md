# Actions autorisées sur le monde

> SharedWorldState = état courant ; Knowledge = références ; ConversationMemory = historique. Les productions des agents passent toutes par l’Orchestrator.

| Rôle | Lit | Produit | Écriture / autorité |
| --- | --- | --- | --- |
| [[ParserAgent]] | Email et contexte d’extraction autorisé. | EmailAnalysis, signalement. | Aucune écriture dans les beliefs ou connaissances. |
| [[DecisionAgent]] | Analyse, registre, contexte de routage. | RouteDecision, demande d’arbitrage. | Aucune écriture partagée ; ne distribue pas lui-même la tâche. |
| [[OrchestratorAgent]] | Résultats, état, permissions, décisions utilisateur. | Messages, transitions, questions, incidents, demandes d’opérations. | Seule autorité sur le monde partagé ; faits après validation ; écritures K_commun/K_perso via KnowledgeService après choix explicite. |
| [[WriterAgent]] et profils | Monde et sources autorisés, contexte du fil. | ResponsePlan, questions, propositions. | Pas de modification directe du monde ou des connaissances ; droits K_base ouverts. |
| [[ScribeAgent]] | Plan, style et contexte de formulation. | DraftResponse, signalement. | Aucun envoi ni écriture de connaissances. |
| [[CorrectingAgent]] | Feedback et traces autorisées. | CorrectionPlan, demande documentaire. | Propose ; ne modifie ni beliefs ni mémoire. |
| [[KnowledgeService]] | Sources et permissions applicables. | Extraits référencés, résultat d’une opération. | Service technique appliquant les seules écritures demandées et autorisées par l’Orchestrator. |
| Utilisateur | Brouillon, questions, justification et incidents utiles. | Réponse, choix de mémoire, validation, rejet, décision de reprise. | Autorise les changements métier/durables et l’envoi de la version courante. |

Tous les agents peuvent signaler l’impossibilité d’accomplir leur tâche. Le contrôle d’exécution signale à leur place s’ils sont muets ou illisibles. Le suivi opérationnel ne constitue pas une promotion de faits métier.

## Durées

- **DRAFT** : brouillon concerné ; pas de préférence permanente déduite.
- **THREAD** : contexte du fil ; pas de généralisation à la personne ou à tous les emails.
- **GLOBAL** : portée de changement proposée, appliquée seulement après autorisation explicite et choix d’espace.
- **Incident** : tâche et tentative concernées ; durée de trace à définir.

Voir [[Global security]], [[Suivi des taches et reprise]] et [[Decisions et questions ouvertes]]. Aucun acteur ne contourne la validation utilisateur d’envoi.
