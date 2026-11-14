# Phase 2.1 — intégration des contrats JSON V1

Les sept contrats validés sont intégrés au socle Java 21. La Factory, le port LlmGateway,
AgentMessage/MessageMetadata, AgentResult et les repositories existants sont conservés.
Les fichiers conceptuels, les schémas et les exemples fournis n'ont pas été modifiés.

## Correspondance avec le socle précédent

| Contrat | Champs conservés | Ajouts et adaptations |
| --- | --- | --- |
| EmailAnalysis | language, intent, topics | emailId/threadId Java ; entities, requestedInformation, senderClaims, missingInformation, uncertainties, evidence ; Content distinct |
| RouteDecision | selectedAgentId, reasons | emailId Java ; candidats, statut, confirmation/question, confidence toujours null ; sélection nullable selon statut |
| ResponsePlan | intention, faits text/sourceRef | identifiants et profil Java ; clarification, informations manquantes, intention REQUEST_CLARIFICATION ; Content distinct |
| DraftResponse | subject, body | DraftContent LLM ; emailId, planId et DRAFT ajoutés par Java |
| CorrectionPlan | Aucun DTO précédent | Statut, cible/action, portée, instruction, motif, question, proposition mémoire. Depuis la phase 2.1.1, CorrectingAgent et sa Factory produisent ce type. |
| DiscussionPlan | motif, TaskContext | Liste de questions, action ASK_INFORMATION, memoryProposal ; normalisation Java du JSON initial |
| ErrorPlan | errorId, code, composant, tâche, date | agentId nullable, stage, reportedBy, expectedResult, observedProblem à la place de technicalMessage, traceRef nullable, STOPPED et requiresUserDecision=true |

Les deux échanges utilisateur des références sont aussi typés : UserDiscussionAnswer
et DiscussionResume. Ils ne constituent pas des sorties LLM supplémentaires.

## Frontières de validation

1. Jackson vérifie syntaxe et types sans coercition, propriétés inconnues, doublons,
   champs obligatoires et documents concaténés.
2. Les constructeurs vérifient les combinaisons métier. Une violation produit
   InvalidOutputException, convertie en INVALID_OUTPUT. Un problème de format produit
   ContractException, convertie en INVALID_JSON. Aucun de ces cas ne devient une clarification.
3. Les schémas fournis vérifient le document original, avec assertions UUID/date actives.
   NetworkNT JSON Schema Validator 1.5.9 est la seule nouvelle dépendance directe.
   Jackson reste responsable de la sérialisation et désérialisation.
4. Les stratégies Java vérifient le contexte : Writers autorisés/éligibles/disponibles,
   sources des faits, email/plan courant et fidélité du brouillon.

Les champs nullable sont obligatoirement présents. Leurs valeurs nulles ne sont acceptées
que dans les combinaisons autorisées. Les vocabulaires ouverts ne sont pas convertis
en enums inventés. Les collections sont copiées et immuables.

Les versions sont portées par chaque Contract. Les références fournies définissent
uniquement 1.0 ; toute autre version est refusée à la lecture tant que son schéma
et son DTO ne sont pas explicitement pris en charge.

## Sortie LLM puis enrichissement Java

Le gateway accepte seulement EmailAnalysis.Content, RouteDecision.Content,
ResponsePlan.Content, DraftContent et CorrectionPlan. Chaque Content propose une
méthode enrich qui reçoit les données de confiance ; ResponsePlan génère un nouveau
planId en Java. MessageFactory génère les identifiants et dates des enveloppes.

Exemple de raccordement dans un comportement Writer, sans définir de prompt définitif :

```java
return context.llm().generate(request, context.task(), context.configuration().agentId())
        .map(content -> content.enrich(
                context.configuration().agentId(), input.analysis().emailId(),
                configuredStructure, configuredLanguage, configuredTone));
```

Ici request est un LlmRequest<ResponsePlan.Content> utilisant
Contract.RESPONSE_PLAN_CONTENT. Un comportement Scribe utilise Contract.DRAFT_CONTENT,
puis enrichit le DraftContent avec son ResponsePlan validé.

FakeLlmGateway et OllamaLlmGateway appliquent la même validation. Ollama reçoit les
schémas LLM exacts embarqués dans le JAR ; RecordJsonSchema a été supprimé. Les
interfaces d'injection et le choix du modèle restent inchangés.

## Clarification, incident et reprise

DiscussionPlan.normalize extrait les questions de RouteDecision, ResponsePlan ou
CorrectionPlan. Les informations manquantes du Parser restent une extraction.
Le Scribe refuse un plan incomplet avant d'appeler son comportement.

Le coordinateur conserve une SuspendedDiscussion dans SharedWorld.detail : demande
enveloppée, JSON du résultat initial et, après réponse, DiscussionResume. Ces informations
survivent au redémarrage sans changer les classes ni les permissions des Worlds.
La réponse structurée doit viser la bonne version requestMessageId. Une reprise
prépare une nouvelle tentative sans exécuter automatiquement l'agent.

Les anciens snapshots WAITING_USER de phase 1 contenant seulement une question en texte
ne sont pas migrés automatiquement : leur conversion demande une décision explicite,
car ils ne contiennent pas le résultat initial ni l'identité de la demande.

Une réponse assistant vide devient EMPTY_RESULT ; un résultat Java nul devient NO_RESULT.
Le timeout reste LLM_TIMEOUT. ErrorPlan est construit par Java avec son contexte,
STOPPED et requiresUserDecision=true. traceRef reste null en l'absence d'une vraie trace.

## Points ouverts et limites explicites

Depuis la phase 2.1.1, un plan `READY` avec `ASK_SAVE_SCOPE` reste un succès contenant
les deux informations. Le futur contrôleur utilise la cible et l’action pour traiter la
correction courante dans une nouvelle tâche. Il utilise `memoryProposal` pour ouvrir
séparément le choix de mémorisation. Le choix peut être refusé sans annuler la correction.
Toute mémorisation durable exige un choix explicite et les permissions correspondantes,
y compris pour une portée `GLOBAL`.

Quand l’état courant est `COMPLETED`, l’Orchestrator refuse toute tentative portant son
`taskId`. Une étape suivante reçoit un nouveau `taskId` et peut garder la même corrélation.

- L'exemple fourni response-plan.clarification.json utilise ResearchWriter dans metadata
  et AdministrationWriter dans payload.agentId. Il est structurellement valide. Un test
  conserve cet exemple et montre que MessageFactory refuse cette incohérence à la création.
- Plusieurs chemins Obsidian indiquent docs/contracts/ ; les fichiers fournis sont dans docs/.
- La méthode sémantique de vérification des faits et sources n'est pas décidée dans l'index
  validé. ResponsePlanValidator reçoit donc un prédicat qui doit vérifier existence,
  autorisation et appui de la source. Sans prédicat, tous les faits sont refusés.
- DraftResponseValidator exige un contrôle de fidélité fourni par l'application ; il vérifie
  lui-même emailId et planId. Il n'invente pas un juge LLM ou une heuristique de vérité.
- Les formats K_PERSO/K_COMMUN et les résultats STORED_* sont reconnus. Le coordinateur
  actuel ne raccorde aucun service d'écriture de connaissances : seul NONE/NOT_STORED
  reprend par ce chemin. Un choix de stockage garde la tâche suspendue ; aucun stockage
  réussi n'est annoncé artificiellement. La politique des connaissances reste à définir.
- Aucun prompt définitif, workflow complet, BDI, interface utilisateur, mécanisme d'approbation
  ou envoi d'email n'a été ajouté. Chaque nouvelle enveloppe de brouillon reçoit un nouvel
  identifiant ; l'approbation de cette version exacte appartient au futur contrôleur.

## Vérification

Le 13 novembre 2026, JDK 21.0.9 :

- mvn clean verify : **988 tests, 0 échec, 0 erreur, 0 ignoré** ; compilation de 56 fichiers
  de production et 12 fichiers de test ; JAR produit avec les 21 schémas.
- python docs/check_schemas.py : **21 schémas, 12 exemples valides, 4 exemples invalides
  rejetés et 5 sorties LLM validées**.
- OllamaLlmGatewayIT avec **qwen3.5:4b-q4_K_M** : **1 test réussi**, réception d'un
  Success<DraftContent> valide en environ 8,8 secondes ; aucun email réel ni envoi.
- Les tests de non-régression couvrent toujours Factory, permissions, écritures atomiques,
  reprises explicites, erreurs de sauvegarde, timeouts et échanges HTTP locaux simulés.
- Les 852 cas de ValidatedContractsTest comprennent les exemples et les mutations de
  champs requis, nulls, formats, enums, propriétés inconnues et frontières LLM/Java.
- Comparaison SHA-256 des 92 fichiers protégés (Architecture, schémas, exemples, sources
  des Worlds) : aucun changement.

Le test réel Ollama est séparé de la suite automatique et utilise seulement des données
synthétiques. Commande :

```sh
mvn -Dtest=OllamaLlmGatewayIT -Dollama.integration=true -Dollama.model=qwen3.5:4b-q4_K_M test
```

Le [guide du code](guide-code.md) décrit chaque fichier ajouté ou adapté.
