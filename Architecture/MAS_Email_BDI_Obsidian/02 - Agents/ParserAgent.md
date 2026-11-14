---
tags: [mas, agent, simple]
statut: implemente-v3.0
---
# ParserAgent

> Agent simple d’extraction sémantique. Il transforme un email en [[EmailAnalysis]], sans sélectionner de Writer ni entretenir de modèle BDI autonome.

## Expertise

Extraire intentions, sujets, entités, informations demandées, opérations demandées, déclarations et incertitudes à partir du texte reçu. Les citations relient les observations au message source.

## Knowledge

Le modèle reçoit le texte exact du message courant et les consignes d'extraction V3.0. Les références ambiguës restent dans `uncertainties`. Java conserve les identifiants d'email et de fil dans `ParserInput` jusqu'à l'enrichissement du résultat.

## Beliefs

**Pas de beliefs propres nécessaires**, conformément à ta réponse. EmailAnalysis est une observation intermédiaire, pas un ajout de croyances à [[SharedWorldState]]. Les valeurs absentes ou incertaines restent explicitement telles.

## Fonctionnement simple — sans BDI imposé

- **Objectif :** transmettre une extraction fidèle, utilisable pour le routage.
- **Traitement :** recevoir le texte, extraire les champs sémantiques, retourner l’analyse au coordinateur.
- **Si ambigu :** renseigner les incertitudes et les passages concernés ; ne pas inventer une interprétation certaine.
- **Si échec :** signaler [[errorJSON]] si possible ; le contrôle d’exécution le fournit en cas de sortie illisible ou d’absence de résultat.

Ces étapes ne sont pas une délibération entre plans BDI. Une reprise après correction utilisateur relit les entrées corrigées, sous le contrôle de l’Orchestrator.

## Prompt spécialisé

Le prompt validé et son exemple sont chargés depuis
`src/main/resources/prompts/parser-agent-v3.0.txt` et transmis dans le rôle
`system`. Le texte du message occupe le rôle `user`. Le schéma du contenu
[[EmailAnalysis]] V3.0 est envoyé à Ollama dans `format`.

Le prompt distingue une question sur une réservation existante d'une demande
explicite de réserver. Java contrôle le JSON et la présence exacte de chaque
citation. Les tests sur des messages représentatifs évaluent les distinctions
métier et l'exhaustivité de l'extraction.

## Routage et exclusions

Appelé par [[OrchestratorAgent]] à la réception d’un email ou lors d’une reprise d’extraction. [[DecisionAgent]] exploite ensuite l’analyse transmise par l’Orchestrator. Parser ne gère ni choix de profil, ni confirmation documentaire, ni envoi.

## Communication

L'Orchestrator fournit le texte et les identifiants de l'email et du fil. Le Parser retourne une `EmailAnalysis` enrichie ou un `ErrorPlan`. L'Orchestrator crée les métadonnées d'enveloppe.

Un texte vide, un appel interrompu ou un JSON non exploitable relève du [[Suivi des taches et reprise]]. Une analyse valide mais incertaine est transmise avec ses limites.

## Permissions et durées / Sécurité

Lecture de l’email et du contexte confiés ; production de l’analyse seulement. Aucune écriture dans K_commun, K_perso, K_base ou SharedWorldState. Les observations sont rattachées à la version courante de l’email ; leur traçabilité ne les promeut pas en connaissances durables. Voir [[Global security]].

## Points à préciser

Les codes d'intention restent ouverts dans les limites de la syntaxe définie par le schéma.
