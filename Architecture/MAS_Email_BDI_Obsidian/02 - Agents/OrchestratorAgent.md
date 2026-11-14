---
tags: [mas, agent, architecture]
statut: conception-a-valider
---
# OrchestratorAgent

> Agent d’administration et de coordination, distinct d’[[AdministrationWriter]]. La décision du Writer est confiée à [[DecisionAgent]]. L’Orchestrator suit des règles fixes et n’utilise pas de LLM.

## Expertise

Faire circuler les messages, suivre le traitement, appliquer les opérations autorisées et signaler lorsqu’un agent n’accomplit pas sa tâche. Il coordonne l’exécution sans remplacer le raisonnement métier des autres agents.

## Knowledge

- **K_commun** : informations et préférences communes autorisées.
- **Règles de coordination** : étapes, contrats attendus, permissions et décisions utilisateur.
- **Contexte courant** : [[AgentRegistry]], analyse, routage reçu, plans, brouillons, questions, validations et suivi des tâches.

[[KnowledgeService]] applique les opérations de mémoire demandées par l’Orchestrator ; c’est un service technique, pas un agent supplémentaire.

## Beliefs

L’état opérationnel de [[SharedWorldState]] indique la tâche active, l’agent attendu, le résultat reçu, l’attente de l’utilisateur ou l’incident. Une trace d’échec n’est pas un nouveau fait métier.

Les faits partagés sont ajoutés ou rectifiés selon la validation utilisateur. Les droits précis sur K_base restent ouverts ; l’Orchestrator ne transforme pas une hypothèse en fait commun de sa propre initiative.

## Desires / Intentions / Plans BDI

### Desires

Assurer un traitement cohérent, traçable et autorisé, avec contrôle humain de l’envoi.

### Intentions

Exécuter l’étape autorisée, attendre une réponse, signaler un incident ou préparer une reprise.

### Plans

| Plan | Préconditions | Actions | Effets |
| --- | --- | --- | --- |
| Déléguer | Entrées valides et étape permise. | Enregistrer la tâche attendue puis transmettre la mission. | Une exécution active suivie, sans parallélisme décidé. |
| Relayer une question | Clarification ou document requis. | Présenter la demande et, si pertinent, les choix de mémoire. | Attente de réponse utilisateur. |
| Appliquer une mise à jour | Contenu, portée et autorisation explicites. | Mettre à jour le monde ou solliciter KnowledgeService. | Changement traçable dans le seul périmètre approuvé. |
| Traiter un incident | Échec, résultat absent ou invalide. | Bloquer la suite, tracer [[errorJSON]] et informer l’utilisateur. | Tâche arrêtée ; reprise soumise à validation. |

Cette grille décrit des objectifs et plans de coordination. Elle ne prétend pas donner une cognition BDI délibérative ou un prompt LLM à un contrôleur à règles fixes.

## Prompt spécialisé / Règles de coordination

**Aucun prompt LLM.** Vérifier l’étape, l’émetteur attendu, la version et la sortie ; transmettre une mission ; attendre son résultat ; appliquer les transitions autorisées. Un JSON valide ne prouve pas à lui seul que le fond de la tâche est accompli.

L’agent peut constater un résultat vide, un contrat invalide ou un délai dépassé et recevoir un signalement métier. En cas de doute sémantique, il sollicite l’utilisateur ; [[CorrectingAgent]] peut proposer un diagnostic sur sa demande. Aucun jugement sémantique automatique par LLM n’est ajouté à l’Orchestrator.

## Routage et exclusions

Il transmet [[EmailAnalysis]] et le registre à DecisionAgent puis exécute [[RouteDecision]] si elle est recevable. En cas de correction de catégorie, il redemande une décision. Il ne sélectionne plus lui-même le Writer et ne rédige pas le contenu de réponse.

## Communication

Point de passage unique des messages entre agents et avec l’utilisateur : [[DiscussionPlan - Agent - Orchestrator   Orchestrator - User  User-Orchestrator|DiscussionPlan]]. Parser, Decision, Writer, Scribe et Correcting retournent leurs productions au coordinateur.

Pour une information manquante : demander la précision, puis proposer **K_perso**, **K_commun** ou **nulle part**. L’information peut servir au traitement sans mémorisation ; sans choix explicite, aucun ajout durable. « Nulle part » ne supprime pas le brouillon ni l’historique.

En cas de plantage, l’agent fautif signale errorJSON s’il le peut ; le contrôle d’exécution fournit ce signalement s’il est muet ou illisible. [[Suivi des taches et reprise]] détaille l’arrêt, l’escalade et la reprise après validation.

## Permissions et durées / Sécurité

- **Écriture opérationnelle :** suivi des tâches, étapes et traces, sans demander de validation pour chaque transition normale.
- **Écriture métier :** seul point d’autorité pour les beliefs partagés ; promotion après validation utilisateur, révision/révocation à sa demande.
- **Mémoire :** seul demandeur des écritures autorisées dans K_commun/K_perso via KnowledgeService ; droits K_base à trancher.
- **Envoi :** ne déclencher qu’après approbation explicite de la version courante du brouillon ; une reprise n’est pas une approbation d’envoi.
- **Durées :** distinguer tâche, fil et mémoire durable selon [[Global security]].

## Points à préciser

Délais d’absence de résultat, limites de reprises, conservation des traces et réaction si le coordinateur lui-même est indisponible. Crash si il est p&as disponicalite
