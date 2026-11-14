# AgentRegistry et compétences

Le registre décrit les Writers disponibles, leurs spécialités, exclusions et sources autorisées. [[DecisionAgent]] l’utilise pour choisir ; [[OrchestratorAgent]] s’en sert pour adresser et suivre la tâche. Aucun de ces rôles n’invente une compétence absente du registre.

| Profil | Domaine | Réserve |
| --- | --- | --- |
| [[StudentWriter]] | Questions pédagogiques des étudiants. | Une demande administrative d’étudiant peut relever d’AdministrationWriter. |
| [[TeacherWriter]] | Coordination entre collègues enseignants. | Fiche existante conservée ; maintien dans la liste finale à confirmer. |
| [[AdministrationWriter]] | Procédures, dossiers et documents administratifs. | Distinct de l’administration du système par Orchestrator. |
| [[ResearchWriter]] | Projets, réunions et partenaires scientifiques. | Documents et divulgation filtrés par projet. |
| [[GenericWriter]] | Demandes hors spécialités. | Conditions de recours à préciser, aucun fallback automatique après plantage. |
| [[PersonalWriter]] | Échanges personnels. | Rôle confirmé par « personnel » ; nom proposé et frontières à préciser. |

## Décision et ambiguïtés

Le routage prend en compte rôle, contenu, objet, fil, historique et métadonnées, pas seulement l’adresse. Un seul profil est sélectionné pour la tâche ; plusieurs candidats peuvent être décrits dans [[RouteDecision]]. Aucun parallélisme n’est introduit.

Plusieurs candidats ou aucun choix justifié : demander clarification via l’Orchestrator. La politique exacte de recours à GenericWriter reste ouverte. Une correction de catégorie fait reprendre DecisionAgent ; une indisponibilité ne justifie pas de choisir un autre profil sans respecter la politique autorisée.

## Extensibilité et décisions restantes

Ajout par code avec Factory ou Abstract Factory : préférence utilisateur conservée pour la phase ultérieure, sans développer les classes maintenant. Format des compétences, personnes autorisées à les modifier, critères V1, modèle JEV-like et seuils restent à définir.

Voir [[Decisions et questions ouvertes]].
