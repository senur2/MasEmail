# Décisions et questions ouvertes

> Source d’autorité : les notes et Canvas modifiés par l’utilisateur dans ce coffre. Les réponses présentes sont conservées ci-dessous ; les compléments de formalisation ne valent pas nouvelles décisions utilisateur. Travail limité à l’architecture et au BDI.

## Choix confirmés dans tes réponses

| Sujet | Choix conservé | Conséquence dans le coffre |
| --- | --- | --- |
| Séparation de l’Orchestrator | « Desicion Agent ET Orchetrator agent ». | [[DecisionAgent]] choisit ; [[OrchestratorAgent]] administre, communique et contrôle. |
| Cognition et routage | « LLm commun sauf Pour orchestrator […] regle fixe » ; Decision « d’abord en regle fix puis en JEV-like ». | LLM local commun pour les rôles génératifs ; Orchestrator sans LLM ; règles de routage V1 puis évolution JEV-like. Aucun modèle particulier choisi. |
| Beliefs des Writers | « Leurs belief sont un peu differente ». | Noyau de référence commun autorisé, vues pertinentes différentes ; pas d’identité absolue des états mentaux imposée. |
| Hypothèses | « leurs hypothése non confirmé sont dans leurs k_base ». | K_base conservé comme terme ; statut non confirmé distinct d’un fait. Droits et articulation avec K_perso encore ouverts. |
| Promotion en fait commun | « Orchetsrator Agent aprés validation utilisateur ». | Seul l’Orchestrator applique une promotion autorisée. |
| Belief minimal | « Juste besoin d’un claim et d’une confidence ». | Contenu conceptuel minimal conservé ; provenance et autorisation peuvent être suivies dans les traces, sans ajouter de champs obligatoires au belief. |
| Révision et création | Remplacement/révocation : « deamnde user » ; création provisoire par Writer : « Non ». | Aucun Writer ne modifie les beliefs partagés ; l’ambiguïté avec les hypothèses K_base reste signalée ci-dessous. |
| Exécution | Canvas Monde et BDI : « pas de parrallemisme autorisé ». | Exécution séquentielle conservée ; aucune collaboration parallèle introduite. |
| Writers | Réponse « Oui » à étudiant, administration, recherche, générique, personnel. | Profils conservés ; [[PersonalWriter]] documente le rôle personnel confirmé. TeacherWriter préexistant est conservé, liste finale à réconcilier. |
| Ajout d’agent | « Code avec Factory ou abstract Factroy ». | Préférence enregistrée pour une phase ultérieure ; aucune classe, signature ni hiérarchie conçue ici. |
| Correcting et document | « Si un document est demandé il doit envopyer une question a Admin ». | [[CorrectingAgent]] adresse sa demande à l’Orchestrator, rôle d’administration, sans élargir ses droits. |
| Portée des corrections | « Decision semantique avec few shot training ». | Diagnostic sémantique avec exemples few-shot ; portée DRAFT/THREAD/GLOBAL proposée. Un entraînement des poids n’est pas déduit de cette formulation. |
| Clarification, rejet, recours | « Few shot prompting ». | Exemples pour les agents génératifs ; cela ne remplace pas les règles fixes de l’Orchestrator/Decision V1. Critères précis à définir. |
| Erreurs JSON | « Crash avec tracabilité » ; « errorJSon […] à orchestrator […] à user ». | Arrêt du traitement concerné, trace et signalement utilisateur via [[errorJSON]]. Aucun auto-réparateur LLM imposé. |
| Charge du LLM | « le llm ne construirat pas tout le JSON ». | Séparer contenu généré et métadonnées du système ; Writer limité aux quatre champs cognitifs du prompt de référence. |
| Sécurité | « Ajout GlobalSecurity ». | [[Global security]] commun aux rôles et communications. |
| Stockage | KnowledgeService : « BD SQL » ; Canvas : « probablement […] SQLITE ». | Orientation SQL conservée ; SQLite probable, moteur exact non arrêté. |
| Mémoire de fil | « Résumé Mémoir avec message stocké dans un fichier temps ». | Résumé de contexte et messages en fichier temporaire ; durées et conservation de l’historique à préciser. |

## Choix déjà présents et conservés

- Analyse par Parser ; contenu métier par Writer ; formulation par Scribe ; corrections par Correcting.
- Parser sans beliefs propres nécessaires ; Parser et Scribe restent des agents simples.
- Validation explicite de l’utilisateur avant l’envoi, y compris après correction.
- Documents consultés sans modification directe par les agents.
- Choix de mémorisation **K_perso / K_commun / nulle part** ; sans choix explicite, aucun ajout durable.
- K_perso ne devient pas K_commun ; « nulle part » ne supprime pas les traces possibles dans brouillon et historique.
- Orchestrator contrôle les écritures ; KnowledgeService reste un service technique.
- Ton, langue et structure viennent du profil ; le Writer ne les génère pas.

## Contradictions et points à trancher

- [ ] **Hypothèses K_base / beliefs provisoires interdits :** préciser si l’interdiction vise tout état individuel ou seulement les beliefs partagés. Les deux réponses sont conservées ; aucun droit de création implicite n’est ajouté.
- [ ] **K_base / K_perso :** même espace ou espaces distincts ? Qui peut écrire les hypothèses ? L’autorité sur K_commun ne résout pas ce point.
- [ ] **Liste finale :** garder TeacherWriter en plus des cinq profils cités ? PersonalWriter est un nom proposé pour « personnel », pas un renommage de TeacherWriter.
- [ ] **Crash :** arrêt de la tâche/du fil ou arrêt de toute l’application ? La formalisation de travail bloque le traitement concerné ; l’étendue du plantage reste à valider.
- [ ] **Scribe :** quels contrôles de fidélité au plan et qui les effectue ? La validité JSON seule ne garantit pas l’accomplissement métier.
- [ ] **Routage :** critères V1, cas ambigu/hors domaine, recours autorisé à GenericWriter, modèle local JEV-like, forme des scores, calibration et seuils.
- [ ] **Conflits de faits :** comment présenter et résoudre deux affirmations contradictoires avant validation utilisateur ?
- [ ] **Reprises :** délai par tâche, limite des tentatives, abandon, règles en cas d’incidents répétés et indisponibilité de l’Orchestrator lui-même.
- [ ] **Mémoire :** durées, effacement, traces d’incident, limite de contexte, stockage durable des messages à partir des fichiers temporaires, moteur SQL, index éventuel et rafraîchissement.
- [ ] **Spécialités :** documents accessibles, règles de divulgation et paramètres de rédaction précis pour chaque profil.
- [ ] **Few-shot :** corpus d’exemples, ambiguïtés de portée et articulation avec les règles de décision V1.

## Formalisations ajoutées pour cette révision

[[Suivi des taches et reprise]] décrit l’obligation de résultat, le constat d’échec, l’escalade et la reprise autorisée. Les états et champs proposés servent à discuter l’architecture ; les schémas exacts et contrôles programmés restent pour une phase ultérieure.

Les corrections peuvent viser DECISION, puisque le routage a été séparé. GLOBAL désigne une portée proposée, jamais une permission automatique de mémoriser. Le mécanisme conserve ton arrêt traçable et ne fixe aucun nombre arbitraire de reprises.

Le Canvas Java préexistant est laissé inchangé et hors du travail courant. Voir [[Recapitulatif de revision - 2026-11-07]].
