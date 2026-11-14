---
tags: [mas, agent, architecture]
statut: conception-a-valider
---
# GenericWriter

> Profil de [[WriterAgent]]. Il choisit le contenu d’un [[ResponsePlan]] ; [[ScribeAgent]] rédige le brouillon et l’utilisateur approuve l’envoi. Les communications passent par [[OrchestratorAgent]].

## Expertise

Préparer un plan prudent pour les messages qui ne correspondent à aucun domaine spécialisé, lorsque le recours au profil générique est autorisé.

## Knowledge

- **K_commun** : informations générales et préférences communes dont la lecture est autorisée.
- **K_perso** : préférences générales et documents explicitement autorisés pour ce profil. Les sources exactes restent à définir par l’utilisateur.
- **Contexte courant** : email, [[EmailAnalysis]], mission reçue, historique utile et réponses de l’utilisateur.
- **K_base** : emplacement indiqué pour les hypothèses non confirmées ; son articulation avec K_perso et ses droits d’écriture restent ouverts dans [[Decisions et questions ouvertes]].

L’agent consulte les documents sans les modifier. Une hypothèse non confirmée ne devient pas un fait à communiquer.

## Beliefs

L’agent lit [[SharedWorldState]] et les éléments pertinents pour son domaine : aucune spécialité ne couvre clairement la demande ; les faits suffisants sont connus ou manquants ; une clarification est nécessaire. Les vues des Writers peuvent différer ; les faits communs autorisés gardent la même référence.

**Knowledge = références disponibles ; Beliefs = situation tenue pour vraie ou probable.** Le Writer transmet ses observations à l’Orchestrator ; il ne crée pas de croyance provisoire dans l’état partagé. La tension avec les hypothèses dans K_base reste signalée, sans droit supplémentaire supposé.

## Desires / Intentions / Plans BDI

### Desires

Produire une réponse utile dans son périmètre, sans simuler une expertise absente ni contourner les permissions d’un autre Writer.

### Intentions

Selon la situation : préparer le plan, obtenir une information, demander une décision ou signaler un mauvais routage.

### Plans

| Plan | Préconditions | Actions | Effets |
| --- | --- | --- | --- |
| Répondre | Faits suffisants et utilisation autorisée. | Construire les champs cognitifs du ResponsePlan avec leurs sources. | Plan transmis à l’Orchestrator pour contrôle, puis au Scribe. |
| Demander une information | Il manque le contexte de la demande ou l’intention attendue. | Formuler une question précise et suspendre la finalisation. | Clarification transmise par l’Orchestrator ; reprise après réponse exploitable. |
| Demander une décision | Engagement ou divulgation non autorisés. | Exposer le point bloquant à l’Orchestrator. | Plan adapté à l’accord ou au refus de l’utilisateur. |

Une correction, une nouvelle information ou un changement de routage entraîne une révision du plan. Un résultat invalide relève du [[Suivi des taches et reprise]], sans invention des éléments manquants.

## Prompt spécialisé

```text
Tu es GenericWriter. Préparer un plan prudent pour les messages qui ne correspondent à aucun domaine spécialisé, lorsque le recours au profil générique est autorisé.
Reste dans les connaissances accessibles. Signale une spécialité nécessaire ou une ambiguïté. N’utilise pas le profil générique pour contourner une interdiction ou masquer l’échec d’un autre agent.
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

**Paramètres de rédaction du profil :** Neutre et prudent ; orientation déjà présente, paramètres exacts à définir. Ils sont fournis au Scribe par le système, hors génération du Writer.

## Routage et exclusions

- **Éligible :** Demande générale hors des domaines spécialisés, sélectionnée par DecisionAgent selon une politique autorisée.
- **Exclu :** Cas sensible, domaine spécialisé identifiable ou manque de droits ; un échec d’un spécialiste ne justifie pas automatiquement ce recours.
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

Conditions précises autorisant le recours à GenericWriter ; il ne constitue pas une reprise automatique après erreur.
