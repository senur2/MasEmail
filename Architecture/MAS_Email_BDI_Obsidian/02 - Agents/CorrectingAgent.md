---
tags: [mas, agent, architecture]
statut: conception-a-valider
---
# CorrectingAgent

> Agent de diagnostic des corrections utilisateur. Il produit un [[CorrectionPlan]] ; [[OrchestratorAgent]] décide de l’exécution selon les règles et autorisations.

## Expertise

Identifier l’étape responsable d’un problème en comparant le feedback aux sorties disponibles : extraction, routage, contenu métier ou formulation. Il prépare une instruction ciblée, préserve les éléments valides et distingue la correction courante d’une proposition de mémoire durable.

## Knowledge

- **K_commun** : règles communes et préférences accessibles.
- **K_perso** : rôles des agents et exemples de corrections pour le diagnostic.
- **Contexte courant** : demande utilisateur, traces utiles, EmailAnalysis, RouteDecision, ResponsePlan et DraftResponse.

Le choix sémantique de la cible et de la portée s’appuie sur des exemples few-shot, comme demandé. Cela n’acte pas un entraînement des poids du modèle.

## Beliefs

L’agent observe qu’un passage est erroné, absent ou ambigu et formule une hypothèse de diagnostic à partir des traces. Il n’ajoute pas de belief provisoire au monde partagé. L’attribution de l’erreur reste incertaine si les preuves sont insuffisantes.

**Knowledge = rôles et exemples ; Beliefs = situation à corriger.** Toute rectification du contexte commun est proposée à l’Orchestrator.

## Desires / Intentions / Plans BDI

### Desires

Corriger la bonne étape, limiter la reprise nécessaire et respecter le sens du feedback sans étendre sa portée.

### Intentions

Diagnostiquer, produire une consigne précise, demander une clarification ou proposer une mémorisation.

### Plans

| Plan | Préconditions | Actions | Effets |
| --- | --- | --- | --- |
| Cibler la correction | Feedback et traces suffisants. | Identifier la première étape fautive et produire CorrectionPlan. | Reprise ciblée par l’Orchestrator. |
| Clarifier | Diagnostic ou portée ambigus. | Poser une question via l’Orchestrator. | Diagnostic suspendu jusqu’à réponse exploitable. |
| Demander un document | Pièce nécessaire indisponible. | Formuler une demande à l’administration/Orchestrator. | L’utilisateur fournit ou refuse ; aucun accès supplémentaire implicite. |

Une omission présente dans le plan mais absente du brouillon cible le Scribe ; une omission du plan cible le Writer. Une extraction erronée cible Parser ; un mauvais domaine cible DecisionAgent.

## Prompt spécialisé

```text
Tu es CorrectingAgent. Analyse le feedback et les traces autorisées.
PARSER extrait ; DECISION choisit le Writer ; ORCHESTRATOR coordonne ;
WRITER décide du contenu ; SCRIBE formule le brouillon.
Choisis la première étape à corriger et préserve ce qui reste valide.
Style ou omission d’un fait déjà dans le plan : SCRIBE.
Fait manquant/erroné dans le plan : WRITER. Mauvais domaine : DECISION.
Extraction erronée : PARSER. Ambiguïté ou document manquant : question via Orchestrator.
Produis uniquement le contenu cognitif de CorrectionPlan :
{
  "status": "READY",
  "target": "SCRIBE",
  "action": "REWRITE_DRAFT",
  "scope": "DRAFT",
  "instruction": "Rendre la réponse plus concise.",
  "reason": "Correction de style.",
  "question": null,
  "memoryProposal": "NONE"
}
Respecte les valeurs du contrat CorrectionPlan. Si le diagnostic est ambigu,
status=NEEDS_CLARIFICATION, target=USER, action=ASK_CLARIFICATION ; renseigne question.
Une portée GLOBAL est seulement proposée, jamais appliquée sans autorisation.
Ne modifie ni connaissances ni beliefs ; ne rédige et n’envoie aucun email.
```

**Exemples few-shot de travail :** « plus concis » → SCRIBE/DRAFT ; « dans ce fil, il est partenaire du projet » → DECISION/THREAD si le routage est en cause ; « retiens toujours cette préférence » → proposition GLOBAL à confirmer. Ces exemples ne fixent pas les cas ambigus ; voir le contrat.

## Routage et exclusions

Sollicité lors d’une correction utilisateur ; un incident technique passe d’abord par le suivi de l’Orchestrator. Il peut aider au diagnostic si l’utilisateur le demande, mais ne relance pas lui-même un agent et n’est pas un validateur automatique de chaque brouillon.

## Communication

Reçoit feedback et traces via l’Orchestrator ; lui retourne CorrectionPlan, question documentaire ou [[errorJSON]]. L’Orchestrator transmet les instructions à l’agent ciblé, consulte l’utilisateur pour la mémoire et présente le nouveau brouillon.

## Permissions et durées / Sécurité

Lecture autorisée et production du plan uniquement. Aucune écriture directe dans SharedWorldState ou K_commun/K_perso. Les portées DRAFT, THREAD et GLOBAL sont diagnostiquées sémantiquement ; GLOBAL reste une proposition avec validation explicite. Une correction de brouillon n’autorise pas sa mémorisation.

L’utilisateur valide chaque nouvelle version avant envoi. Voir [[Global security]] et [[Suivi des taches et reprise]].


