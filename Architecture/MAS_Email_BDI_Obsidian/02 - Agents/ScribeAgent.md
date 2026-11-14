---
tags: [mas, agent, simple]
statut: conception-a-valider
---
# ScribeAgent

> Agent simple de formulation. Il transforme [[ResponsePlan]] en [[DraftResponse]] ; le choix du fond appartient au Writer et l’envoi reste soumis à l’utilisateur.

## Expertise

Rédiger un brouillon lisible et fidèle aux faits autorisés du plan. Il applique la langue, le ton, la structure et les contraintes fournis par le profil et les corrections validées.

## Knowledge

Consignes de rédaction, plan reçu, paramètres du profil et contexte du fil strictement utile à la formulation. Il n’explore pas de lui-même les bases métier pour enrichir le contenu ; une information manquante revient au Writer via l’Orchestrator.

## Beliefs

**Pas de modèle BDI autonome imposé.** Plan et contraintes sont des données de travail. Le Scribe ne décide pas de nouvelles vérités métier et ne modifie pas [[SharedWorldState]].

## Fonctionnement simple — sans BDI imposé

- **Objectif :** formuler fidèlement une réponse claire.
- **Traitement :** lire le plan contrôlé, appliquer les paramètres reçus et produire le brouillon.
- **Réécriture :** corriger la forme sans altérer les faits lorsque l’Orchestrator transmet une instruction de [[CorrectingAgent]].
- **Blocage :** si la consigne exige un nouveau fait ou contredit le plan, demander une clarification via l’Orchestrator.

Un plan qui attend encore une information indispensable n’est pas présenté comme une réponse complète. Une question destinée à l’expéditeur n’est rédigée que si le Writer l’a explicitement prévue ; la clarification interne de l’utilisateur passe par l’Orchestrator.

## Prompt spécialisé

```text
Tu es ScribeAgent. Rédige uniquement à partir du ResponsePlan reçu.
Respecte les faits autorisés, le ton, la langue, la structure et les contraintes fournis.
Ne décide pas de nouveaux faits et ne crée aucun engagement absent du plan.
Ne divulgue ni notes internes, ni hypothèses, ni données hors du contenu autorisé.
Produis les champs subject et body de DraftResponse ; le système ajoute
les identifiants, la version et le statut DRAFT.
Si une correction porte sur le fond ou si le plan est inexploitable,
signale le problème à l’Orchestrator au lieu de compléter par invention.
Ne modifie pas les connaissances et n’envoie jamais le brouillon.
```

## Routage et exclusions

Appelé par [[OrchestratorAgent]] après contrôle du plan, ou pour une réécriture ciblée. Il ne choisit pas de Writer, ne répare pas un mauvais routage et ne remplace pas le jugement métier.

## Communication

Reçoit ResponsePlan, paramètres de rédaction et éventuelle correction via l’Orchestrator. Retourne DraftResponse au même coordinateur, qui contrôle puis présente le résultat à l’utilisateur. Aucun envoi direct.

Sortie vide, invalide ou échec de génération : [[errorJSON]], ou signalement du contrôle d’exécution si le Scribe ne peut le fournir. Un écart de fond constaté peut être signalé par l’utilisateur et diagnostiqué par CorrectingAgent.

## Permissions et durées / Sécurité

Lecture des seules données fournies pour rédiger ; production du brouillon uniquement. Aucune écriture dans les connaissances ou beliefs partagés. Une consigne stylistique DRAFT reste liée au brouillon ; une préférence durable exige une validation distincte via l’Orchestrator.

La réécriture invalide l’approbation d’une ancienne version. L’utilisateur approuve toujours la version courante avant envoi. Voir [[Global security]] et [[Suivi des taches et reprise]].

## Points à préciser

Méthode exacte de contrôle de fidélité : vérifications déterministes, relecture humaine et éventuel autre contrôle restent à arbitrer. Aucun agent critique supplémentaire n’est imposé.
