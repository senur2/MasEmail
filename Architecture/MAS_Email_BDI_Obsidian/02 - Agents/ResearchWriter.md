---
tags: [mas, agent, architecture]
statut: conception-a-valider
---
# ResearchWriter

> Profil de [[WriterAgent]]. Il choisit le contenu d’un [[ResponsePlan]] ; [[ScribeAgent]] rédige le brouillon et l’utilisateur approuve l’envoi. Les communications passent par [[OrchestratorAgent]].

## Expertise

Préparer les réponses concernant les projets scientifiques, réunions, partenaires et documents de recherche accessibles à l’utilisateur.

## Knowledge

- **K_commun** : informations générales et préférences communes dont la lecture est autorisée.
- **K_perso** : objectifs de projet, calendriers, références, documents scientifiques et échanges autorisés. Les sources exactes restent à définir par l’utilisateur.
- **Contexte courant** : email, [[EmailAnalysis]], mission reçue, historique utile et réponses de l’utilisateur.
- **K_base** : emplacement indiqué pour les hypothèses non confirmées ; son articulation avec K_perso et ses droits d’écriture restent ouverts dans [[Decisions et questions ouvertes]].

L’agent consulte les documents sans les modifier. Une hypothèse non confirmée ne devient pas un fait à communiquer.

## Beliefs

L’agent lit [[SharedWorldState]] et les éléments pertinents pour son domaine : un projet est identifié ; une date est vérifiée ou inconnue ; un résultat ou document est confidentiel. Les vues des Writers peuvent différer ; les faits communs autorisés gardent la même référence.

**Knowledge = références disponibles ; Beliefs = situation tenue pour vraie ou probable.** Le Writer transmet ses observations à l’Orchestrator ; il ne crée pas de croyance provisoire dans l’état partagé. La tension avec les hypothèses dans K_base reste signalée, sans droit supplémentaire supposé.

## Desires / Intentions / Plans BDI

### Desires

Répondre avec des faits vérifiables, respecter les restrictions du projet et éviter les engagements scientifiques ou calendaires non autorisés.

### Intentions

Selon la situation : préparer le plan, obtenir une information, demander une décision ou signaler un mauvais routage.

### Plans

| Plan | Préconditions | Actions | Effets |
| --- | --- | --- | --- |
| Répondre | Faits suffisants et utilisation autorisée. | Construire les champs cognitifs du ResponsePlan avec leurs sources. | Plan transmis à l’Orchestrator pour contrôle, puis au Scribe. |
| Demander une information | Il manque la date confirmée d’une réunion ou le droit de partager un document. | Formuler une question précise et suspendre la finalisation. | Clarification transmise par l’Orchestrator ; reprise après réponse exploitable. |
| Demander une décision | Engagement ou divulgation non autorisés. | Exposer le point bloquant à l’Orchestrator. | Plan adapté à l’accord ou au refus de l’utilisateur. |

Une correction, une nouvelle information ou un changement de routage entraîne une révision du plan. Un résultat invalide relève du [[Suivi des taches et reprise]], sans invention des éléments manquants.

## Prompt spécialisé

```text
Tu es ResearchWriter. Préparer les réponses concernant les projets scientifiques, réunions, partenaires et documents de recherche accessibles à l’utilisateur.
Rattache les faits à une source du projet. Ne transforme ni hypothèse ni résultat provisoire en résultat établi. Ne divulgue pas de document inédit et ne confirme pas une nouvelle tâche sans autorisation.
Consulte uniquement les sources autorisées et indique une sourceRef pour chaque fait.
Produis le plan de contenu ; le Scribe rédigera l’email.
Ne modifie ni connaissances ni beliefs partagés, et ne contacte pas directement un autre agent.
Le ton, la langue et la structure viennent du profil : ne les génère pas.
Produis uniquement ces quatre champs cognitifs, sans identifiants techniques :
{
  "responseIntent": "ANSWER_INFORMATION_REQUEST",
  "factsToCommunicate": [],
  "missingInformation": [],
  "requiresClarification": false
}
responseIntent : ANSWER_INFORMATION_REQUEST, PROVIDE_DOCUMENT,
ACKNOWLEDGE_REQUEST ou REQUEST_CLARIFICATION.
Chaque fait contient text et sourceRef. Chaque manque est une question précise.
Si une information indispensable manque, requiresClarification vaut true
et missingInformation contient les questions ; sinon false et [].
Ne prétends pas accomplir une tâche impossible : signale le blocage à l’Orchestrator.
```

**Paramètres de rédaction du profil :** Adapté au projet, avec non-divulgation ; langue et ton exacts à définir par profil. Ils sont fournis au Scribe par le système, hors génération du Writer.

## Routage et exclusions

- **Éligible :** Question sur un projet scientifique, ses réunions, ses tâches ou ses partenaires.
- **Exclu :** Formalité administrative dominante → AdministrationWriter ; organisation d’enseignement → TeacherWriter si disponible ; sujet personnel → profil personnel.
- **Mauvais routage :** signaler à l’Orchestrator, qui sollicite [[DecisionAgent]] ; le Writer ne choisit pas son successeur.

Le sujet et le contexte priment sur une catégorisation fondée uniquement sur l’identité de l’expéditeur.

## Communication

- **OrchestratorAgent** attribue la tâche, fournit le contexte, reçoit le plan, relaie les questions et surveille son accomplissement.
- **ScribeAgent** reçoit le plan contrôlé via l’Orchestrator ; aucune transmission directe entre Writers et Scribe.
- **CorrectingAgent** propose les reprises après feedback utilisateur ; l’Orchestrator transmet les instructions ciblées.
- **Incident :** transmettre [[errorJSON]] si possible ; si l’agent ne répond pas ou produit une sortie illisible, le dispositif de contrôle fournit le signalement à l’Orchestrator.

## Permissions et durées / Sécurité

- **Lecture :** état partagé, contexte et sources autorisés seulement.
- **Production :** son plan et ses demandes ; aucune écriture directe dans K_commun ou les beliefs communs.
- **Écriture indirecte :** proposition relayée par l’Orchestrator ; mémorisation dans K_perso ou K_commun après choix explicite de l’utilisateur via [[KnowledgeService]].
- **Durées :** plan pour le brouillon courant ; correction de contexte pour le fil ; mémoire durable jusqu’à correction ou suppression autorisée. « Nulle part » n’efface pas le brouillon ou l’historique.
- **Limite :** aucun envoi ; toute nouvelle version doit être approuvée par l’utilisateur. Voir [[Global security]].

## Points à préciser

Sources accessibles par projet, périmètre des partenaires autorisés et éventuels profils dédiés à un projet.
