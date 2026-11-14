# ConversationMemory

## Rôle architectural

Conserver et restituer le contexte utile : messages du fil, brouillons successifs, réponses réellement envoyées, décisions utilisateur et traces nécessaires. L’historique décrit ce qui s’est passé ; il ne transforme pas les déclarations d’un email en faits vérifiés.

## Choix utilisateur conservé

**Résumé mémoire avec messages stockés dans un fichier temporaire.** Le résumé limite le contexte transmis au LLM local ; un passage exact reste consultable tant que le message source est disponible et autorisé.

La conservation durable de l’historique et la durée des fichiers temporaires doivent encore être conciliées. Aucun délai d’effacement ni limite de tokens n’est fixé ici. Une suppression du temporaire ne doit pas être supposée compatible avec une réutilisation future non définie.

## Éléments à suivre

- Email reçu et métadonnées autorisées ; analyse et révisions.
- RouteDecision, profil choisi et raisons.
- ResponsePlan et DraftResponse successifs, avec références de versions.
- Feedback, CorrectionPlan, clarifications et refus.
- Choix de mémoire : espace, contenu et autorisation.
- Approbation du brouillon courant, rejet et résultat réel d’envoi.
- Tâches, [[errorJSON]], tentatives de reprise et décisions utilisateur.

Ces éléments sont des besoins de traçabilité, pas un schéma de stockage arrêté. L’Orchestrator coordonne l’enregistrement ; les autres agents ne réécrivent pas l’historique.

## Portées et accès

Le contexte transmis est filtré par rôle, tâche et permissions. Une information « nulle part » n’est pas ajoutée à K_perso ou K_commun, mais peut rester dans le brouillon ou l’historique ; cette distinction est explicitée lors du choix de mémoire.

Une autorisation de reprise ne vaut pas autorisation d’envoi. L’approbation est rattachée à une version précise ; les anciennes versions restent des traces, non des réponses approuvées courantes.

## Points à préciser

Durées de conservation, effacement, accès aux traces d’incident, protection des fichiers temporaires, limite de contexte, fidélité des résumés et stockage SQL exact. Voir [[Decisions et questions ouvertes]] et [[Global security]].
