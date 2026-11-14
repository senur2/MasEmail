---
tags: [mas, agent, architecture]
statut: conception-a-valider
---
# DecisionAgent

> Agent de décision extrait de l’ancien Orchestrator. Il choisit le Writer ; [[OrchestratorAgent]] exécute la décision et conserve l’autorité de communication et d’écriture.

## Expertise

Associer une demande au profil compétent en tenant compte du contenu, de l’objet, du rôle de l’expéditeur et du contexte du fil. Les règles fixes sont retenues pour la première version ; une approche JEV-like est prévue ensuite, sans modèle concret choisi ici.

## Knowledge

- **Registre :** profils disponibles, domaines, exclusions et accès annoncés dans [[AgentRegistry]].
- **Règles de décision :** critères de routage configurés et cas de clarification.
- **Contexte courant :** [[EmailAnalysis]], passages autorisés de l’email, contexte utile, décision précédente et correction éventuelle.

Décider du domaine ne nécessite pas l’accès intégral aux connaissances privées de tous les Writers. Il reçoit les seules informations utiles au routage.

## Beliefs

Observations de routage : sujet dominant, candidats compatibles, ambiguïtés et disponibilité des profils. Une déclaration d’expéditeur ne suffit pas à confirmer son rôle. Ces observations servent la décision courante ; elles n’ajoutent rien à [[SharedWorldState]] sans l’Orchestrator.

**Knowledge = critères et compétences ; Beliefs = situation de routage observée.** Les incertitudes restent visibles dans la décision, sans score de confiance inventé.

## Desires / Intentions / Plans BDI

### Desires

Sélectionner un profil pertinent et autorisé, expliquer le choix et éviter une délégation arbitraire.

### Intentions

Retenir un Writer, demander un arbitrage ou reconsidérer une décision après correction.

### Plans

| Plan | Préconditions | Actions | Effets |
| --- | --- | --- | --- |
| Choisir | Un candidat répond aux critères applicables. | Produire RouteDecision avec le profil et les raisons. | Délégation possible par l’Orchestrator. |
| Clarifier | Plusieurs candidats ou aucun choix justifié. | Exposer l’ambiguïté et demander l’arbitrage via l’Orchestrator. | Attente utilisateur ; pas de sélection forcée. |
| Reconsidérer | Correction de catégorie ou contexte actualisé. | Refaire le choix sur les nouvelles entrées. | Nouvelle décision ; effets aval à réexaminer. |

La grille exprime le rôle au niveau architectural. La V1 applique des règles explicites ; elle n’implique pas une cognition générative. L’approche ultérieure JEV-like conserve les mêmes responsabilités.

## Prompt spécialisé / Règles de décision

**V1 : pas de prompt LLM de routage.** Examiner les critères disponibles, exclure les profils incompatibles ou indisponibles, justifier le candidat retenu et signaler l’insuffisance des entrées.  TRés bête on vas juste demander au llm pour la v1

**Évolution JEV-like :** comparer les options du registre à partir du même contexte autorisé. Le modèle local, les entrées, la forme des scores, leur calibration et les seuils restent à choisir. Aucun choix entre JEV, NanoJev ou une autre famille n’est acté par cette fiche.

La sortie de travail reste [[RouteDecision]] ; ses métadonnées sont fournies par le système. Un score éventuel est une indication du modèle, pas une probabilité garantie.

## Routage et exclusions

Un étudiant peut faire une demande administrative ; un ami peut écrire au sujet d’un projet professionnel. Le sujet et le fil doivent donc départager les domaines.

[[GenericWriter]] n’est sélectionné que si sa politique de recours le permet. Il ne sert pas à masquer un échec technique. La présence de [[TeacherWriter]] et le nom du profil [[PersonalWriter]] gardent les réserves du registre.

## Communication

Reçoit la mission de l’Orchestrator, lui retourne RouteDecision ou une demande d’arbitrage. Il ne contacte directement ni Writer ni utilisateur. [[CorrectingAgent]] peut cibler DECISION dans un CorrectionPlan, exécuté par l’Orchestrator.

Sortie invalide, impossible ou absente : [[errorJSON]] et [[Suivi des taches et reprise]]. Une ambiguïté explicitement signalée relève de la clarification, pas d’un plantage.

## Permissions et durées / Sécurité

Lecture du registre et du contexte autorisé ; production de la seule décision et des signalements. Aucune écriture dans les mémoires ou beliefs communs, aucune modification des compétences, aucun envoi d’email.

La décision concerne le traitement courant ; une préférence durable de routage nécessite une validation explicite de l’utilisateur via l’Orchestrator. Voir [[Global security]].



.
