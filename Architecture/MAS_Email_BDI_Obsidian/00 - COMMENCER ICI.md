---
tags: [mas, bdi, architecture]
statut: conception-en-cours
---
# Système multi-agents de réponse aux emails — atelier de conception

> Cette version suit tes notes modifiées et tes réponses. Les choix conservés, contradictions et points non décidés sont regroupés dans [[Decisions et questions ouvertes]]. Le travail courant porte sur l’architecture et le BDI ; aucune conception Java nouvelle n’est engagée.

## Parcours des Canvas

- [[01 - Canvas/00 - Architecture generale.canvas|00 — Architecture générale]] : Decision choisit, Orchestrator coordonne et contrôle tous les échanges.
- [[01 - Canvas/01 - Monde et BDI.canvas|01 — Monde et BDI]] : beliefs communs et vues propres, hypothèses K_base à clarifier.
- [[01 - Canvas/02 - Routage.canvas|02 — Routage]] : règles V1, évolution JEV-like, ambiguïtés.
- [[01 - Canvas/03 - Connaissances et memoire.canvas|03 — Connaissances et mémoire]] : espaces, permissions, choix utilisateur.
- [[01 - Canvas/04 - Cycle BDI Writer.canvas|04 — Cycle BDI Writer]] : connaissances, beliefs, intentions, plans et blocages.
- [[01 - Canvas/05 - Traitement email.canvas|05 — Traitement d’un email]] : séquence de délégation, résultat et validation.
- [[01 - Canvas/06 - Boucle de correction.canvas|06 — Boucle de correction]] : cible, portée et reprise.
- [[01 - Canvas/07 - Contrats JSON.canvas|07 — Contrats JSON]] : productions, échanges et errorJSON.
- [[01 - Canvas/09 - Incidents et reprise.canvas|09 — Incidents et reprise]] : détection, arrêt, escalade et reprise autorisée.

[[01 - Canvas/08 - Classes Java conceptuelles.canvas|08 — Ancien Canvas Java]] est **préexistant, inchangé et hors du travail courant**. Sa présence ne valide pas ses propositions ; ne pas l’utiliser comme architecture à implémenter à cette étape.

## Fiches des agents

[[OrchestratorAgent]] · [[DecisionAgent]] · [[ParserAgent]] · [[WriterAgent]] · [[ScribeAgent]] · [[CorrectingAgent]]

Profils : [[AdministrationWriter]] · [[StudentWriter]] · [[TeacherWriter]] · [[ResearchWriter]] · [[GenericWriter]] · [[PersonalWriter]]. Le maintien de TeacherWriter dans la liste finale reste à confirmer ; PersonalWriter est le nom proposé pour le profil personnel accepté.

## Lire et compléter

Les fiches harmonisées reprennent Expertise, Knowledge, Beliefs, fonctionnement/BDI, prompt ou règles, routage, communication et permissions. Parser et Scribe restent simples ; l’Orchestrator et Decision V1 utilisent des règles fixes.

Les communications passent par l’Orchestrator. Les mémorisations durables et l’envoi restent soumis à leurs validations utilisateur respectives. Voir [[Global security]], [[Matrice des actions sur le monde]] et [[Suivi des taches et reprise]].

## Navigation et sources

- [[Inventaire des fichiers]] : liste complète cliquable.
- [[Recapitulatif de revision - 2026-11-07]] : fichiers modifiés, ajouts et choix conservés.
- [[Exigences et hypotheses]] : contraintes du sujet distinguées des choix de conception.
- [[06 - Sources/Cahier des charges - original.pdf]] : source conservée sans modification.

Les cartes de texte sont éditables dans les Canvas ; les cartes de fichier ouvrent les notes. Bleu : coordination/données ; vert : Writers ; violet : formulation/correction ; orange : arbitrages et incidents.
