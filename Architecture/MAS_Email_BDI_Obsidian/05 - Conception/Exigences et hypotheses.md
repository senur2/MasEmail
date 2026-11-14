# Exigences du sujet et choix d’architecture

## Contraintes du cahier des charges

Source : [[06 - Sources/Cahier des charges - original.pdf]], notamment sections 4 à 16.

- Application principalement Java ; LLM de génération exécuté localement et remplaçable.
- Routage vers des agents spécialisés selon le contenu et le contexte, sans se limiter à l’adresse.
- Spécialisation par prompt et par contexte ; droits et sources propres aux profils.
- Prise en compte du fil, des brouillons et des réponses réellement envoyées.
- Contrôle utilisateur sur la réponse envoyée ; validation/rejet et révision.
- Extensibilité des agents, modèles, prompts, sources, routage et mémoire.
- Expérimentations sur des cas distincts, ambiguïtés, erreurs et limites ; justification des choix dans le rapport.

Les exemples d’agents du sujet ne constituent pas une liste imposée. Les diagrammes sont recommandés. Les classes et interfaces seront traitées lors de la phase logicielle, pas dans cette révision.

## Architecture issue de tes choix

L’Orchestrator reçoit et coordonne toutes les communications. Il sollicite successivement Parser, Decision, Writer et Scribe ; contrôle leurs productions ; présente le brouillon à l’utilisateur. Correcting diagnostique les corrections reçues ; Decision reprend les erreurs de routage. Voir [[01 - Canvas/00 - Architecture generale.canvas]].

- **Decision** : règles V1, puis approche locale JEV-like à préciser.
- **Orchestrator** : règles fixes, sans cognition LLM ; autorité de coordination et d’écriture contrôlée.
- **Writers** : BDI explicite, connaissances spécialisées, beliefs pouvant différer autour d’un noyau commun.
- **Parser et Scribe** : fonctions simples, sans BDI artificiel.
- **LLM** : service local commun aux rôles génératifs, pas un agent autonome ; exécution séquentielle.
- **Messages** : contrats conceptuels ; contenu généré limité, métadonnées ajoutées par le système.
- **Erreur** : arrêt traçable, errorJSON vers Orchestrator puis utilisateur ; reprise autorisée.
- **Mémoire** : orientation SQL ; résumé et fichiers temporaires, détails de conservation ouverts.

BDI, séparation en ces rôles, JSON intermédiaires et supervision des incidents sont des choix de conception, pas une liste d’obligations imposée par le sujet. RAG, fine-tuning et choix d’un modèle JEV-like concret restent des pistes ou décisions ultérieures.

## Périmètre de cette révision

Terminer la conception architecturale et BDI, harmoniser les fiches et les Canvas, conserver les réponses utilisateur et rendre les incertitudes visibles. Aucun développement ni conception nouvelle de classes Java. Le Canvas 08 préexistant est conservé inchangé, hors du parcours actif.

Voir [[Decisions et questions ouvertes]] et [[Recapitulatif de revision - 2026-11-07]] pour séparer choix conservés, formalisation ajoutée et arbitrages restants.
