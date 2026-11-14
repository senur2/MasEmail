# Suivi des tâches, incidents et reprise

> Mécanisme architectural de l’[[OrchestratorAgent]]. Il concrétise la demande de signaler un agent qui n’accomplit pas sa tâche, et conserve « crash avec traçabilité » + errorJSON transmis à l’utilisateur. Aucun détail de classes Java.

## Responsabilité et résultat attendu

Avant chaque délégation, l’Orchestrator connaît la tâche, l’agent destinataire, les entrées et leur version, le résultat attendu et l’étape à reprendre. Identifiants et suivi sont produits par le système, pas par le LLM. L’exécution reste séquentielle.

| Étape | Résultat attendu | Condition de continuation |
| --- | --- | --- |
| Parser | [[EmailAnalysis]] | Analyse exploitable et liée à l’email courant ; incertitudes visibles. |
| Decision | [[RouteDecision]] | Candidat disponible et décision recevable ; sinon clarification explicite. |
| Writer | [[ResponsePlan]] | Champs cognitifs cohérents, sources présentes ; aucun manque indispensable non résolu pour finaliser. |
| Scribe | [[DraftResponse]] | Brouillon non vide, lié au plan courant, sans anomalie de conformité détectée ; fidélité métier à contrôler selon la méthode encore ouverte. |
| Correcting | [[CorrectionPlan]] | Cible, action et portée cohérentes, instruction exploitable ou question précise. |

Un simple message « terminé », un JSON valide mais vide ou une référence à une ancienne version ne suffit pas. Une clarification pertinente est une attente explicite, pas un succès final ni nécessairement une erreur.

## Détection et signalement

| Situation | Qui la détecte ou la signale ? | Réaction du coordinateur |
| --- | --- | --- |
| Agent signale son échec | Agent, si encore capable de répondre. | Recevoir [[errorJSON]], arrêter la suite et informer l’utilisateur. |
| JSON illisible, champs requis absents, mauvais type de sortie | Contrôle déterministe du résultat. | Construire le signalement sans exiger que le LLM défaillant le génère. |
| Sortie vide, mauvaise tâche/version, résultat inexploitable | Contrôle d’exécution et de cohérence. | Ne pas consommer le résultat ; tracer le motif vérifiable. |
| Aucun résultat ou agent interrompu | Suivi d’exécution ; absence constatée selon une échéance à définir. | Produire un incident même sans réponse de l’agent. Aucun délai numérique inventé. |
| Information ou document manquant | Agent ou utilisateur. | Attente de clarification si une question est exploitable ; ne pas forcer un brouillon. |
| Tâche apparemment achevée mais fond erroné | Utilisateur, agent qui constate l’écart, Correcting sollicité pour diagnostic. | Signaler « tâche non accomplie » avec les éléments observés ; demander arbitrage si doute. |
| Action hors permissions | Contrôle des droits. | Bloquer l’action et signaler ; une reprise n’accorde pas de nouveaux droits. |

**Limite assumée :** l’Orchestrator à règles fixes ne juge pas librement la qualité sémantique d’un email. Il distingue un défaut objectivement contrôlable d’un signalement de fond à faire examiner. Aucun LLM supplémentaire ni agent critique n’est ajouté.

## États de travail proposés

`ATTRIBUEE → EN_COURS → RESULTAT_RECU → TERMINEE`

- `RESULTAT_RECU` ne devient `TERMINEE` qu’après contrôles applicables ; une anomalie mène à `ECHEC_SIGNALE`.
- Une question utile mène à `EN_ATTENTE_UTILISATEUR`, puis revient au traitement après réponse exploitable.
- `ECHEC_SIGNALE` bloque les étapes dépendantes et mène à `EN_ATTENTE_DECISION`.
- Après décision explicite : `REPRISE_AUTORISEE`, `ABANDONNEE` ou maintien en attente.
- Une reprise crée une nouvelle tentative rattachée à la tâche ; l’échec précédent reste dans la trace.

Ces noms ne sont pas un schéma d’implémentation figé. Ils distinguent accomplissement, attente et échec sans imposer de sophistication BDI aux agents simples.

## Escalade vers l’utilisateur

Présenter l’agent, l’étape, le résultat attendu, le constat précis, ce qui est bloqué et les options réellement possibles : fournir une information, autoriser une nouvelle tentative, demander une correction ciblée, demander un nouveau routage ou abandonner. Ne pas afficher inutilement des données confidentielles dans le diagnostic.

L’Orchestrator peut déclarer « le résultat attendu n’a pas été produit » ou relayer un défaut de fond étayé. Il ne prétend pas avoir prouvé la qualité globale d’une réponse uniquement avec des règles.

Une répétition d’incident est signalée comme telle ; la limite et la politique d’abandon restent à définir. Pas de boucle automatique silencieuse ni de fallback vers GenericWriter sans autorisation applicable.

## Reprise contrôlée

1. Recevoir le choix de l’utilisateur et conserver sa portée : tâche, correction, mémoire ou envoi sont des autorisations distinctes.
2. Reprendre à l’étape utile : Parser pour l’extraction, Decision pour le routage, Writer pour le plan, Scribe pour la formulation. Correcting peut diagnostiquer sur demande.
3. Réutiliser seulement les résultats encore valides. Une modification d’entrée amont rend les résultats dépendants obsolètes ; les conserver comme traces, sans les envoyer comme version courante.
4. Vérifier de nouveau la sortie. En cas d’échec, revenir au signalement ; aucune réussite supposée au motif qu’une tentative a déjà été faite.
5. Présenter la version courante du brouillon à l’utilisateur pour approbation avant envoi. Toute ancienne approbation d’un brouillon modifié est invalide.

Un résultat tardif d’une tentative arrêtée ne réactive pas le traitement ; il est identifié et écarté du flux courant. Reprendre n’autorise ni un nouvel accès documentaire ni une écriture durable. Les effets déjà accomplis ne sont pas rejoués aveuglément ; un état d’envoi incertain doit être clarifié avant toute nouvelle émission.

## Arrêt et traçabilité

Conserver le motif, les références de tâche et de version, les décisions et les tentatives. La quantité de sortie invalide conservée et la durée de rétention sont à définir selon [[Global security]] et [[ConversationMemory]]. Aucun contenu sensible n’est automatiquement copié dans K_commun.

La lecture de travail de « crash » est l’arrêt du traitement concerné avec diagnostic visible ; la portée exacte du crash reste ouverte. Si l’Orchestrator lui-même est indisponible, on ne peut pas promettre qu’il relaiera l’erreur : le dispositif local de diagnostic/reprise reste à concevoir, sans inventer un nouvel agent.

## Scénarios de vérification architecturale

- Parser renvoie un JSON tronqué : signalement par le contrôle, aucun routage lancé.
- Writer ne répond pas : incident à l’échéance configurée, aucune génération du Scribe.
- Writer demande une pièce : attente utilisateur ; refus de stockage respecté, donnée utilisable pour le traitement.
- Scribe omet un fait présent dans le plan : correction ciblée après feedback ; nouveau brouillon à approuver.
- Mauvais Writer : CorrectionPlan cible DECISION ; l’Orchestrator demande un nouveau routage autorisé.
- Reprise échoue encore : nouvelle trace et escalade, pas de boucle infinie.
- Résultat ancien arrive après reprise : pas de remplacement du résultat courant.

Voir [[01 - Canvas/09 - Incidents et reprise.canvas]] et [[Decisions et questions ouvertes]] pour les paramètres non décidés.
