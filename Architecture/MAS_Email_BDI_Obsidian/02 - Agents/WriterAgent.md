---
tags: [mas, agent, architecture]
statut: conception-a-valider
---
# WriterAgent

> Rôle métier commun aux profils spécialisés, décrit au niveau architectural. Aucune hiérarchie de classes n’est définie ici.

## Expertise

Choisir le contenu pertinent d’une réponse à partir de l’email, du contexte et des connaissances autorisées. Il produit un [[ResponsePlan]], puis [[ScribeAgent]] assure la formulation. Les profils sont [[StudentWriter]], [[TeacherWriter]], [[AdministrationWriter]], [[ResearchWriter]], [[GenericWriter]] et [[PersonalWriter]] ; la liste finale conserve les réserves d’[[AgentRegistry]].

## Knowledge

- **K_commun** : références et préférences partagées accessibles.
- **K_perso** : documents et règles de la spécialité ; accès propre à chaque profil.
- **Contexte courant** : email original, [[EmailAnalysis]], mission, fil résumé, passages utiles et réponses utilisateur.
- **K_base** : emplacement choisi pour les hypothèses non confirmées ; définition et droits encore à préciser, sans assimilation automatique à K_perso.

Les documents sources sont consultés sans modification. [[KnowledgeService]] fournit les éléments autorisés avec leur référence.

## Beliefs

Lire le noyau commun de [[SharedWorldState]] avec une vue adaptée au domaine. Les beliefs des Writers peuvent différer selon les informations pertinentes, conformément à la réponse utilisateur.

**Knowledge = références ; Beliefs = situation courante.** Le Writer ne crée pas de croyance provisoire dans le monde partagé. Il signale les observations ou contradictions à l’Orchestrator, seul chargé des mises à jour. La place des hypothèses en K_base reste à concilier avec l’interdiction de créer des beliefs provisoires.

## Desires / Intentions / Plans BDI

### Desires

Répondre exactement, rester dans le domaine et les droits du profil, éviter les inventions et engagements non autorisés.

### Intentions

Préparer une réponse étayée, demander une information, obtenir une décision, ou signaler que la tâche sort du périmètre.

### Plans

| Plan | Préconditions | Actions | Effets |
| --- | --- | --- | --- |
| Préparer le contenu | Sources suffisantes et autorisées. | Sélectionner les faits et construire le ResponsePlan. | Plan contrôlable avant rédaction. |
| Clarifier | Information indispensable absente ou incertaine. | Formuler la question pour l’Orchestrator. | Attente de l’utilisateur, sans fait inventé. |
| Réviser | Correction ou nouvelle information reçue. | Revoir les éléments concernés et conserver les éléments valides. | Nouvelle version du plan ; ancien brouillon à réexaminer. |

L’échec technique ou l’impossibilité d’exécuter la mission est signalé selon [[Suivi des taches et reprise]]. Une clarification utile est un résultat intermédiaire, pas un succès final ni un plantage.

## Prompt spécialisé

```text
Tu es un Writer spécialisé selon le profil fourni.
Analyse la demande, consulte les sources autorisées et décide des faits à communiquer.
Ne rédige pas l’email final. Ne génère ni identifiants, ni ton, ni langue, ni structure.
Ne modifie ni connaissances ni beliefs partagés. Signale toute information manquante.
Produis uniquement les champs cognitifs du ResponsePlan :
{
  "responseIntent": "ANSWER_INFORMATION_REQUEST",
  "factsToCommunicate": [],
  "missingInformation": [],
  "requiresClarification": false
}
Un fait contient text et sourceRef ; un manque contient une question précise.
Intentions autorisées : ANSWER_INFORMATION_REQUEST, PROVIDE_DOCUMENT,
ACKNOWLEDGE_REQUEST, REQUEST_CLARIFICATION.
Si un manque est indispensable : requiresClarification=true et questions renseignées.
Sinon : requiresClarification=false et missingInformation=[].
Les consignes propres au domaine complètent ce socle sans élargir les permissions.
```

## Routage et exclusions

[[DecisionAgent]] choisit le profil ; [[OrchestratorAgent]] lui attribue la tâche. Un mauvais routage est signalé au coordinateur. Aucun Writer ne s’attribue une autre spécialité ou n’accède aux documents d’un voisin.

## Communication

L’Orchestrator transmet les entrées et reçoit le plan, les demandes de clarification, les propositions de mémorisation et les incidents [[errorJSON]]. Il relaie ensuite le plan au Scribe. [[CorrectingAgent]] diagnostique les corrections de l’utilisateur ; les reprises reviennent par l’Orchestrator.

## Permissions et durées / Sécurité

Lire seulement les références autorisées ; produire son plan et ses demandes. Proposer une correction du monde sans l’appliquer. Toute mémorisation durable requiert le choix explicite de l’utilisateur ; K_commun et K_perso restent distincts.

Le plan sert au brouillon courant ; une rectification de contexte vise le fil ; une préférence durable exige une autorisation propre. Aucun envoi et aucune réutilisation automatique d’une ancienne approbation après révision. Voir [[Global security]].

## Points à préciser

Liste finale des profils, sources de chacun, articulation K_base/K_perso et droits sur les hypothèses : [[Decisions et questions ouvertes]].
