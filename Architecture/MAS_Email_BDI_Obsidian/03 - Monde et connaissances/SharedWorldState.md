# SharedWorldState — monde commun

## Choix conservés

Le monde commun fournit la référence des faits et de la situation courante. Les beliefs des Writers sont « un peu différents » selon ta réponse : une vue pertinente par rôle n’implique pas un accès identique à toutes les connaissances.

Le contenu conceptuel minimal d’un belief reste **claim + confidence**. Source, date, version et validation peuvent être suivies dans l’historique d’opérations, sans imposer de nouveaux champs au belief lui-même.

## État courant

- Email et fil en cours, analyse et décision de routage retenues.
- Faits communs autorisés et rectifications utilisateur dans leur portée.
- Plan et brouillon courants, avec référence aux versions.
- Tâche attendue, agent sollicité, attente utilisateur, incident ou reprise : [[Suivi des taches et reprise]].
- Approbation de la version courante et état réel d’envoi.

L’état de workflow est distinct des faits métier : constater un échec n’autorise pas à ajouter une connaissance métier. Les identifiants et traces sont gérés par le système, pas inventés par le LLM.

## Autorité d’écriture

[[OrchestratorAgent]] contrôle seul les modifications du monde partagé. Les transitions opérationnelles suivent les règles fixes. La promotion d’une information en fait commun exige la validation utilisateur ; remplacement ou révocation se font à sa demande.

Parser produit des observations ; Decision une décision ; Writer un plan ; Scribe un brouillon ; Correcting une proposition. Aucun de ces rôles ne modifie directement le monde commun. Voir [[Matrice des actions sur le monde]].

## Hypothèses et limites

Les hypothèses non confirmées sont indiquées dans K_base, mais tu as également répondu « Non » à la création d’un belief provisoire par un Writer. La distinction exacte entre hypothèse privée et belief provisoire reste à clarifier ; aucun droit supplémentaire n’est déduit de ces réponses. Voir [[Decisions et questions ouvertes]].

Un texte d’email n’est pas un fait automatiquement vérifié. Un score de confiance ne vaut ni preuve ni autorisation de divulguer. Les faits contradictoires doivent être signalés ; leur politique de résolution reste ouverte.

## Exécution et traces

Pas de parallélisme introduit, conformément au Canvas modifié. Les révisions restent traçables et les anciennes versions ne deviennent pas les versions courantes par simple arrivée tardive. Une modification du brouillon impose une nouvelle approbation utilisateur.
