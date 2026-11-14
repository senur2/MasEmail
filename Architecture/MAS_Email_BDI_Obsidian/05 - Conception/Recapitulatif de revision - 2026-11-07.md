# Récapitulatif de révision — 7 novembre 2026

## Source retenue et sauvegarde

Coffre d’autorité : `C:/Users/spr2l/Documents/ChatGPT/MAS_Email_BDI_Obsidian`. Les contenus Markdown, Canvas et PDF comparés sont identiques à la copie située directement dans Documents au début du travail ; aucun contenu différent plus récent n’y a été trouvé. Le coffre ChatGPT, expressément désigné, est la seule copie cible.

Sauvegarde complète avant modification : `C:/Users/spr2l/.codex/.chatgpt-projects/g-p-6acbb1b6806081919e545665fc906966/travail_obsidian_2026-11-07/original`. Aucun fichier original n’est supprimé ou renommé ; les identifiants des cartes de fichier existantes sont conservés lorsqu’elles restent présentes dans un Canvas. Les mises en page et connexions sont réorganisées pour refléter les responsabilités actualisées.

## Résultat

34 fichiers existants modifiés et 6 fichiers ajoutés. Douze fiches d’agents au total ; huit Canvas existants actualisés et un Canvas incidents ajouté.

Les fiches Writers reprennent la structure et une longueur proche de la fiche AdministrationWriter d’origine. Correcting, Decision et Orchestrator précisent leur rôle propre. Parser et Scribe restent plus courts et simples. Le Canvas Java préexistant est conservé octet pour octet et signalé hors du travail courant ; aucune classe Java n’est conçue.

## Choix conservés

- Decision choisit ; Orchestrator administre avec des règles fixes, sans LLM, et relaie les communications.
- Routage V1 à règles, évolution JEV-like sans modèle concret choisi ; service LLM local commun pour les rôles génératifs ; exécution séquentielle.
- Writers BDI ; Parser sans beliefs propres nécessaires ; Scribe simple ; paramètres de rédaction issus des profils.
- Quatre champs cognitifs du Writer ; métadonnées des messages fournies par le système.
- Beliefs légèrement différents, référence commune, claim/confidence minimal ; promotion après validation utilisateur et révision à sa demande.
- Hypothèses K_base conservées sans résoudre artificiellement leur contradiction avec les beliefs provisoires interdits.
- K_perso / K_commun / nulle part ; aucune mémorisation durable sans choix explicite ; documents sources inchangés.
- Correction sémantique avec exemples few-shot ; demande documentaire à l’administration/Orchestrator.
- Crash traçable et errorJSON vers l’utilisateur ; reprise autorisée, aucune réparation automatique imposée.
- Orientation SQL, SQLite seulement probable, résumé de mémoire et fichiers temporaires ; choix Factory/Abstract Factory enregistré pour plus tard.

## Formalisation ajoutée et arbitrages restants

Le suivi précise les résultats attendus, les sorties vides/invalides, les absences de résultat et les signalements de fond. Le contrôle fournit l’erreur si l’agent ne le peut pas. L’Orchestrator bloque la suite, trace, informe et attend la décision de reprise ; un brouillon modifié exige une nouvelle approbation.

États de suivi, noms de champs nouveaux et exemples de spécialités sont des propositions de conception. Restent ouverts : K_base/K_perso, création des hypothèses, liste finale TeacherWriter/personnel, portée exacte du crash, délais/limites de reprise, contrôle de fidélité du Scribe, critères de routage/JEV-like, sources, droits détaillés et rétention. Voir [[05 - Conception/Decisions et questions ouvertes|Decisions et questions ouvertes.md]].

## Fichiers modifiés

| Fichier | Modification |
| --- | --- |
| [[00 - COMMENCER ICI\|00 - COMMENCER ICI.md]] | Parcours actualisé, tous les agents, incidents et réserves ; Canvas Java signalé hors périmètre. |
| [[01 - Canvas/00 - Architecture generale.canvas\|00 - Architecture generale.canvas]] | Séparation Decision/Orchestrator, tous les profils, communications centrales et suivi des incidents. |
| [[01 - Canvas/01 - Monde et BDI.canvas\|01 - Monde et BDI.canvas]] | Beliefs différenciés, autorité d’écriture, claim/confidence, K_base encore ouvert. |
| [[01 - Canvas/02 - Routage.canvas\|02 - Routage.canvas]] | DecisionAgent route ; Orchestrator exécute ; règles V1 puis JEV-like sans modèle imposé. |
| [[01 - Canvas/03 - Connaissances et memoire.canvas\|03 - Connaissances et memoire.canvas]] | Écritures et choix de mémoire contrôlés, orientation SQL et fichiers temporaires. |
| [[01 - Canvas/04 - Cycle BDI Writer.canvas\|04 - Cycle BDI Writer.canvas]] | Cycle conservé et précisé, clarification via Orchestrator, blocages et révision. |
| [[01 - Canvas/05 - Traitement email.canvas\|05 - Traitement email.canvas]] | Séquence complète avec retours au coordinateur et contrôle humain. |
| [[01 - Canvas/06 - Boucle de correction.canvas\|06 - Boucle de correction.canvas]] | Correction du routage ciblant Decision, portées et nouveau brouillon à approuver. |
| [[01 - Canvas/07 - Contrats JSON.canvas\|07 - Contrats JSON.canvas]] | Messages, DiscussionPlan, errorJSON et métadonnées séparées du contenu généré. |
| [[02 - Agents/AdministrationWriter\|AdministrationWriter.md]] | Fiche de référence conservée et harmonisée ; routage transféré à Decision et incidents ajoutés. |
| [[02 - Agents/CorrectingAgent\|CorrectingAgent.md]] | Prompt dédupliqué ; structure homogène ; few-shot, document via administration, cible DECISION. |
| [[02 - Agents/GenericWriter\|GenericWriter.md]] | Fiche métier complète ; conditions de recours conservées comme ouvertes. |
| [[02 - Agents/OrchestratorAgent\|OrchestratorAgent.md]] | Administration à règles fixes, communications, autorité d’écriture, suivi et reprise. |
| [[02 - Agents/ParserAgent\|ParserAgent.md]] | Extraction simple, sans beliefs propres ni BDI artificiel ; sorties et erreurs précisées. |
| [[02 - Agents/ResearchWriter\|ResearchWriter.md]] | Fiche complète : sources scientifiques, plans BDI, confidentialité et communication. |
| [[02 - Agents/ScribeAgent\|ScribeAgent.md]] | Formulation simple sans BDI autonome ; fidélité, correction et limites explicites. |
| [[02 - Agents/StudentWriter\|StudentWriter.md]] | Fiche complète : contexte pédagogique, plans BDI, exclusions et permissions. |
| [[02 - Agents/TeacherWriter\|TeacherWriter.md]] | Fiche complète conservée ; maintien dans la liste finale explicitement non décidé. |
| [[02 - Agents/WriterAgent\|WriterAgent.md]] | Socle commun architectural, quatre champs cognitifs, croyances et permissions cohérentes. |
| [[03 - Monde et connaissances/AgentRegistry\|AgentRegistry.md]] | Rôles des six profils et réserves ; séparation choix/exécution ; ajout par code conservé comme préférence future. |
| [[03 - Monde et connaissances/ConversationMemory\|ConversationMemory.md]] | Résumé et fichiers temporaires, historique de versions et incidents, conservation ouverte. |
| [[03 - Monde et connaissances/Global security\|Global security.md]] | Validation, écritures, communications, erreurs et reprise cohérentes. |
| [[03 - Monde et connaissances/KnowledgeService\|KnowledgeService.md]] | Service technique, espaces distincts, autorité Orchestrator et choix de mémoire. |
| [[03 - Monde et connaissances/SharedWorldState\|SharedWorldState.md]] | Réponses utilisateur appliquées : vues différentes, belief minimal, autorité d’écriture et contradiction K_base signalée. |
| [[04 - Contrats JSON/CorrectionPlan\|CorrectionPlan.md]] | Exemples JSON réparés, cible DECISION, portées cohérentes et GLOBAL seulement proposé. |
| [[04 - Contrats JSON/DiscussionPlan - Agent - Orchestrator   Orchestrator - User  User-Orchestrator\|DiscussionPlan - Agent - Orchestrator   Orchestrator - User  User-Orchestrator.md]] | Questions, réponses et mémorisation distinguées ; nom conservé ; aliases DiscussionPlan et Request. |
| [[04 - Contrats JSON/DraftResponse\|DraftResponse.md]] | Brouillon retourné au coordinateur ; métadonnées hors LLM et approbation de version. |
| [[04 - Contrats JSON/EmailAnalysis\|EmailAnalysis.md]] | Exemple préservé ; observations, limites des manques et responsabilités précisées. |
| [[04 - Contrats JSON/ResponsePlan\|ResponsePlan.md]] | Quatre champs cognitifs séparés du message complet ; missingInformation et cohérence de clarification. |
| [[04 - Contrats JSON/RouteDecision\|RouteDecision.md]] | Émetteur DecisionAgent, exemple JSON réparé, scores JEV-like et seuils laissés ouverts. |
| [[05 - Conception/Decisions et questions ouvertes\|Decisions et questions ouvertes.md]] | Réponses déjà données regroupées avec provenance ; contradictions et formalisation distinguées. |
| [[05 - Conception/Exigences et hypotheses\|Exigences et hypotheses.md]] | Sujet séparé des choix utilisateur ; architecture actualisée et phase Java différée. |
| [[05 - Conception/Inventaire des fichiers\|Inventaire des fichiers.md]] | Inventaire exhaustif et liens cliquables, incluant tous les ajouts. |
| [[05 - Conception/Matrice des actions sur le monde\|Matrice des actions sur le monde.md]] | Responsabilités et permissions alignées pour agents, services et utilisateur. |

## Fichiers ajoutés

| Fichier | Rôle |
| --- | --- |
| [[01 - Canvas/09 - Incidents et reprise.canvas\|09 - Incidents et reprise.canvas]] | Nouveau schéma de détection, arrêt, escalade et reprise autorisée. |
| [[02 - Agents/DecisionAgent\|DecisionAgent.md]] | Nouveau rôle issu de la séparation explicitement décidée ; règles V1 puis JEV-like. |
| [[02 - Agents/PersonalWriter\|PersonalWriter.md]] | Profil personnel confirmé dans les réponses ; nom proposé, frontières et sources à préciser. |
| [[04 - Contrats JSON/errorJSON\|errorJSON.md]] | Nouveau contrat conceptuel ; échec signalé par agent ou construit par le contrôle. |
| [[05 - Conception/Recapitulatif de revision - 2026-11-07\|Recapitulatif de revision - 2026-11-07.md]] | Journal de cette révision et liste précise des modifications. |
| [[05 - Conception/Suivi des taches et reprise\|Suivi des taches et reprise.md]] | Nouvelle note transversale : résultats attendus, détection, escalade, reprise et scénarios. |

## Éléments conservés sans modification

- `01 - Canvas/08 - Classes Java conceptuelles.canvas` : contenu préexistant, non poursuivi.
- `06 - Sources/Cahier des charges - original.pdf`.
- `Sans titre.md`, `Sans titre.base` et les cinq fichiers `.obsidian`.
- L’autre copie du coffre directement dans Documents et les fichiers synchronisés `sources/` du projet ChatGPT.

## Vérifications

Vérification des liens internes et alias, des cibles de fichiers Canvas, des identifiants et connexions, des blocs JSON et des délimiteurs Markdown. Vérification de l’absence de chevauchement des cartes hors groupes, inspection des aperçus de disposition et comparaison avec la sauvegarde. Ces contrôles portent sur le coffre documentaire ; ils ne constituent pas des essais d’une application SMA implémentée.

Les aperçus de disposition ne remplacent pas un rendu natif Obsidian ; les notes longues restent des cartes ouvrables et défilantes dans les Canvas. Aucun schéma de validation programmé ou test Java n’est ajouté.
