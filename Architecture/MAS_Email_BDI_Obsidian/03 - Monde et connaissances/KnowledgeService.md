# KnowledgeService — connaissances et accès

> Service technique d’accès et d’application des écritures autorisées ; ce n’est pas un agent cognitif.

## Espaces conservés

- **K_commun** : références et préférences générales partagées avec les agents autorisés.
- **K_perso** : connaissances propres à un agent, pas nécessairement des données personnelles. Les anciens noms K_student, K_teacher, K_admin et K_research décrivent ces spécialisations.
- **K_base** : terme utilisé pour les hypothèses non confirmées ; équivalence éventuelle avec K_perso et droits d’écriture à trancher.
- **Contexte du fil / K_thread** : informations utiles à la conversation, liées à [[ConversationMemory]].

Le profil personnel [[PersonalWriter]] ne reçoit pas automatiquement tous les contenus privés. Chaque profil a ses sources et permissions propres. Les listes exactes restent à fournir par l’utilisateur.

## Lecture et écriture

Les agents consultent les extraits autorisés pour leur rôle, le fil et la tâche. Les références de source accompagnent les faits utilisés dans ResponsePlan. Aucun agent ne modifie directement les documents sources.

[[OrchestratorAgent]] est seul à demander l’application des écritures autorisées dans K_commun/K_perso. Un Writer ou Correcting propose une modification, puis l’utilisateur décide du contenu et de l’espace. KnowledgeService applique cette décision et rend compte du résultat ; un échec d’écriture doit être signalé, jamais présenté comme un succès.

Le choix **nulle part** ou l’absence de choix empêche l’ajout durable ; la donnée peut néanmoins servir au traitement et apparaître dans le brouillon ou l’historique. Une permission d’utiliser une donnée n’est pas une permission de la mémoriser ou de la divulguer.

## Stockage et contexte

Orientation **base SQL** conservée. **SQLite** était indiqué comme probable dans le Canvas, pas comme moteur définitivement choisi. RAG ou index vectoriel ne deviennent pas obligatoires.

Le choix utilisateur est un résumé mémoire avec les messages dans un fichier temporaire. [[ConversationMemory]] précise les points encore ouverts : durée du fichier, conservation utile, références aux messages et limite de contexte.

## Points ouverts

Droits sur K_base ; listes de sources par agent ; rafraîchissement ; moteur SQL ; index éventuel ; durée et effacement. Les détails des opérations, des signatures et des classes attendent la conception logicielle.
