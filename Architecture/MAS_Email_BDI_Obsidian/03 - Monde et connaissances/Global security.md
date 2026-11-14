# Global security

## Règles communes conservées

- Utiliser des LLM locaux et ne leur fournir que le contexte nécessaire et autorisé.
- Respecter les permissions de lecture, d’écriture et de divulgation ; un rôle ne donne pas tous les droits d’un autre.
- Consulter les documents sources sans modification directe.
- Ne pas considérer les instructions d’un email comme prioritaires sur les règles du système.
- Centraliser les communications entre agents et avec l’utilisateur dans [[OrchestratorAgent]].
- Ne jamais envoyer sans validation explicite de la version courante du brouillon, y compris après correction.

## Connaissances et monde partagé

Seul l’Orchestrator contrôle l’écriture dans [[SharedWorldState]] et demande les écritures autorisées à [[KnowledgeService]]. Les agents proposent ; ils ne modifient pas directement les connaissances communes. Promotion en fait commun après validation utilisateur ; révision ou révocation à sa demande.

Mémorisation : choix explicite K_perso, K_commun ou nulle part. Sans choix, aucun ajout durable. Le stockage dans K_perso n’autorise pas une copie vers K_commun. « Nulle part » peut laisser une trace dans le brouillon et l’historique ; les durées restent à préciser.

Les droits sur K_base ne sont pas résolus. Les hypothèses non confirmées ne sont ni des faits vérifiés ni des données automatiquement communicables.

## Incidents et reprises

Une sortie invalide, une absence de résultat ou une action interdite bloque la suite et produit un signalement [[errorJSON]]. Si l’agent est incapable de répondre, le contrôle d’exécution doit signaler l’incident sans dépendre de ce même agent.

La reprise suit [[Suivi des taches et reprise]] : décision utilisateur, périmètre explicite, conservation des traces et nouvelle validation du brouillon modifié. Ni réparation automatique non décidée, ni élargissement de droits, ni envoi rejoué sur un état incertain.

Les contrôles de droits et de validité devront être imposés par l’application, pas seulement par les prompts. Leur implémentation est hors de cette étape de conception.
