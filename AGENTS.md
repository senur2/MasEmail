# Instructions pour Codex — MAS Email

## Contexte
Projet universitaire Java 21 : système multi-agent de réponse aux emails utilisant un LLM local.

La documentation conceptuelle se trouve dans [le coffre Obsidian](Architecture/MAS_Email_BDI_Obsidian/). Les contrats validés figurent dans [Contrats JSON](<Architecture/MAS_Email_BDI_Obsidian/04 - Contrats JSON/>), avec les [schémas](docs/schemas/) et les [exemples](docs/examples/) dans `docs/`. Ne consulter que les fichiers nécessaires à la tâche en cours.

## Règles architecturales
- Architecture modulaire et exécution séquentielle pour la V1.
- Orchestrator centralise les communications (Mediator) et n'utilise pas de LLM.
- Factory pour les agents spécialisés.
- Interface `LlmGateway` commune aux agents utilisant un LLM.
- DTO typés et validation des données JSON.
- `SharedWorld` et `MiniWorld` persistés en fichiers JSON.
- Un Writer produit un `ResponsePlan` en cas de succès.
- `DiscussionPlan` demande une clarification à l'utilisateur via l'Orchestrator.
- `ErrorPlan` représente un incident technique générique.
- Aucune relance automatique après une erreur sans décision explicite.
- Ne jamais envoyer un email sans validation utilisateur.

## Développement
- Privilégier les solutions simples, testables et faiblement couplées.
- Ne pas implémenter de fonctionnalités hors du périmètre demandé.
- Ne pas introduire de framework ou de Design Pattern inutile.
- Ajouter des tests aux composants développés.
- Signaler les contradictions ou décisions non résolues.
- Ne pas modifier la documentation conceptuelle sans demande explicite.
