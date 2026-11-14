# Utiliser Ollama avec le gateway interchangeable

L'adaptateur [OllamaLlmGateway](../src/main/java/fr/mas/email/llm/ollama/OllamaLlmGateway.java)
implémente l'interface existante `LlmGateway`, comme `FakeLlmGateway`.
Le faux gateway reste disponible pour les tests déterministes.
La phase 2.1 adapte les DTO et leur validation tout en conservant cette interface et l'injection existante.

## Prérequis locaux

Ollama doit être démarré et le modèle choisi déjà présent. Vérifier :

```powershell
ollama list
```

Les exemples utilisent `Supa-AI/ministral-8b-instruct-2410:q4_k_m` pour le Parser
et `qwen3.5:4b-q4_K_M` pour le test historique de rédaction. Utiliser le tag
exact affiché par l'installation.
Le gateway ne télécharge aucun modèle et ne démarre pas de serveur.

L'adresse par défaut est `http://127.0.0.1:11434`.
Un serveur configuré sur un autre port peut être fourni au constructeur.
Le constructeur attend une origine HTTP(S), sans chemin `/api/chat`, identifiants,
paramètres de requête ou fragment.

## Brancher l'adaptateur

L'injection reste celle déjà prévue dans `AgentFactory` :

```java
import fr.mas.email.agent.AgentConfig;
import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRole;
import fr.mas.email.agent.ParserInput;
import fr.mas.email.contract.Contract;
import fr.mas.email.contract.JsonCodec;
import fr.mas.email.contract.MessageFactory;
import fr.mas.email.llm.ollama.OllamaLlmGateway;
import fr.mas.email.orchestration.OrchestratorAgent;
import fr.mas.email.result.TaskContext;
import fr.mas.email.world.FileWorldStore;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

// À placer dans le code de démarrage de l'application.
var json = new JsonCodec();
var worlds = new FileWorldStore(Path.of("data"), json);

try (var ollama = new OllamaLlmGateway(
        URI.create("http://127.0.0.1:11434"),
        "Supa-AI/ministral-8b-instruct-2410:q4_k_m",
        json, new OllamaLlmGateway.ChatOptions(false, 1536))) {
    var config = new AgentConfig("Parser", AgentRole.PARSER, "Extraction email", List.of(), List.of());
    var parser = new AgentFactory(ollama, worlds).createParser(config);
    var orchestrator = new OrchestratorAgent(worlds.sharedWorld(), new MessageFactory(Clock.systemUTC()));
    var input = new ParserInput("email-001", "thread-001", "Bonjour, quelle est la date de la réunion ?");
    var result = orchestrator.execute(parser, input, TaskContext.start(), Contract.EMAIL_ANALYSIS, value -> {});
}
```

Le bloc `try` doit englober la durée d'utilisation des agents : fermer le gateway
arrête son client HTTP. Pour les tests simulés, injecter un
`new FakeLlmGateway(json)` au même emplacement.

Changer de modèle Ollama consiste à changer le nom passé au constructeur.
Changer de moteur consiste à fournir une autre implémentation de `LlmGateway`.
Le modèle doit accepter les messages de chat et les sorties structurées utilisées
par l'adaptateur. Celui-ci utilise un flux désactivé et une température de 0.
`ChatOptions` configure `think` et `num_predict`. Un paramètre Java `null` omet
l'option correspondante de la requête. [API de chat Ollama](https://docs.ollama.com/api/chat) · [Options de génération](https://docs.ollama.com/modelfile#valid-parameters-and-values)

`createParser(config)` utilise un délai de 300 secondes. La surcharge
`createParser(config, timeout)` permet de l'ajuster.

## Conversion d'une requête et de sa réponse

L'adaptateur utilise [POST /api/chat](https://docs.ollama.com/api/chat) :

- les instructions de `LlmRequest` deviennent un message `system` ;
- les données d'entrée deviennent un message `user` séparé ;
- le contrat attendu sélectionne le schéma LLM fourni, envoyé dans le champ `format` ;
- `stream: false` demande une réponse complète ;
- `message.content` est désérialisé dans le DTO attendu par le `JsonCodec` existant.

Les [sorties structurées Ollama](https://docs.ollama.com/capabilities/structured-outputs)
reçoivent les schémas validés de `docs/schemas/llm`, chargés par
[ContractSchemas](../src/main/java/fr/mas/email/contract/ContractSchemas.java).
Les règles conditionnelles et la nullabilité ne sont plus approximées par réflexion.

Les sorties autorisées sont `EmailAnalysis.Content`, `RouteDecision.Content`,
`ResponsePlan.Content`, `DraftContent` et `CorrectionPlan`. Pour le Parser, le
prompt V3.0 est envoyé intégralement en rôle `system` et le schéma de contenu en
`format`, avec son ordre de propriétés. Seul le texte source est envoyé en rôle
`user`. Java vérifie chaque citation littérale et ajoute les identifiants avant de
transmettre l'analyse au coordinateur.

Pour le routage, `AgentFactory.createDecision(config)` charge le prompt
`DecisionAgent.txt` V1.2 avec un délai de 300 secondes. La surcharge
`createDecision(config, timeout)` permet de l'ajuster. Le message `user`
contient `analysis` et `availableWriters`. L'appelant fournit les profils
autorisés, éligibles et disponibles pour la tâche dans `DecisionInput`. Le champ
`format` reçoit le schéma de sortie `docs/schemas/llm/route-decision.schema.json`.
Java ajoute l'identifiant de l'email, valide les IDs retournés et transmet la
décision à l'Orchestrator, qui traite les clarifications du plan.

Jackson construit les DTO ; leurs invariants et les schémas vérifient le JSON.
Les validateurs contextuels contrôlent ensuite les Writers autorisés, les sources et
la fidélité au plan. Les clarifications restent dans le JSON initial, puis Java les
normalise en `DiscussionPlan` sans nouveau prompt.

L'enveloppe Ollama doit signaler une réponse terminée provenant de l'assistant.
Une sortie interrompue ou tronquée n'est pas acceptée comme un succès.
Les métriques techniques et le champ `thinking` éventuel ne sont pas transmis au DTO.
Les métadonnées de message et les `ErrorPlan` restent créés par Java.

## Délais et incidents

Chaque appel effectue une seule invocation HTTP applicative, sans boucle de reprise.
L'agent attend synchroniquement le résultat, ce qui conserve l'exécution séquentielle.
Le mécanisme HTTP interne asynchrone sert à borner l'attente et à l'annuler.

Le délai de `LlmRequest.timeout()` couvre l'échange HTTP, y compris la réception
complète du corps de réponse. Le délai de connexion est plafonné à cinq secondes.
En cas de timeout ou d'interruption, la requête en attente est annulée.
L'annulation côté client ne garantit pas l'arrêt instantané du calcul dans le serveur.

| Incident | Résultat |
| --- | --- |
| Délai dépassé, réponse HTTP 408 ou 504 | `ErrorPlan` / `LLM_TIMEOUT` |
| Serveur inaccessible, modèle absent, autre statut HTTP non 200 ou erreur Ollama | `ErrorPlan` / `LLM_UNAVAILABLE` |
| Enveloppe JSON illisible ou contenu incompatible avec le DTO | `ErrorPlan` / `INVALID_JSON` |
| Combinaison métier interdite, citation absente du texte, génération non terminée ou arrêt par limite de longueur | `ErrorPlan` / `INVALID_OUTPUT` |
| Contenu assistant vide | `ErrorPlan` / `EMPTY_RESULT` |
| Interruption du thread ou incident interne inattendu | `ErrorPlan` / `INTERNAL_ERROR` |

Le statut d'interruption du thread est conservé.
Les redirections HTTP ne sont pas suivies. Les corps d'erreur du serveur, prompts
et textes d'exceptions ne sont pas ajoutés aux messages techniques retournés.
Les références de tâche et de composant passent par le mécanisme `Errors` existant.

## Tests automatiques sans modèle

```sh
mvn test
```

[OllamaLlmGatewayTest](../src/test/java/fr/mas/email/llm/ollama/OllamaLlmGatewayTest.java)
utilise un serveur HTTP simulé sur la boucle locale et un port temporaire.
Il vérifie le protocole, les cinq types de sortie LLM, le changement de modèle, les erreurs,
l'absence de relance applicative, les délais avant et après les en-têtes et
la conservation de l'interruption.

Le [bilan de phase 2.1](phase-2.1.md) donne les résultats de la suite actuelle.
Elle n'exige ni Ollama ni un modèle installé, mais doit pouvoir ouvrir une socket
sur la boucle locale.

## Vérification explicite du Parser avec Ministral 8B

Lancer les cinq scénarios synthétiques ORION, Université, EuroSys, relance
comptable et bowling avec un serveur démarré et le modèle déjà installé.

```sh
mvn -Dtest=ParserAgentOllamaIT -Dollama.integration=true test
```

Le test utilise `Supa-AI/ministral-8b-instruct-2410:q4_k_m`, `temperature=0`,
`think=false`, `num_predict=1536` et un délai de 300 secondes. Les propriétés `ollama.model`,
`ollama.url`, `ollama.timeoutSeconds` et `ollama.numPredict` permettent de
modifier ces réglages. Utiliser `-Dollama.omitThink=true` pour omettre `think`.
Les scénarios vérifient la présence des demandes, actions, échéances et
affirmations importantes en acceptant plusieurs formulations.

### Résultats de la version 3.0

Avec Ollama 0.40.16, le tag Ministral Q4 et le délai par défaut de 300 secondes,
les cinq réponses passent la validation Java. Université, EuroSys, relance
comptable et bowling passent les assertions métier. ORION extrait la demande
de date, mais laisse `senderClaims` vide malgré l'hypothèse sur la disponibilité
de la salle. Le test signale cette omission.

### Résultats historiques de la version 2.6

Lors de la validation V2.6 du 13 novembre 2026 avec Ollama 0.40.16 et le tag Q4
ci-dessus, les cinq réponses ont franchi les contrôles Java avec un délai de
300 secondes. EuroSys et la relance comptable passent les assertions métier.
ORION, Université et bowling contiennent les demandes attendues, mais
`missingInformation` reste vide. ORION rapporte aussi l'hypothèse sur la salle
dans `senderClaims` et l'omet de `uncertainties`. La première campagne a
également rencontré un timeout sur ORION avec le délai de 120 secondes.

Le contrat 3.0 retire ce champ du Parser. `ResponsePlan.missingInformation`
contient les questions qui restent nécessaires après consultation des
connaissances autorisées du Writer.

## Vérification explicite du DecisionAgent

```sh
mvn -Dtest=DecisionAgentOllamaIT -Dollama.integration=true test
```

Les deux scénarios synthétiques couvrent le choix d'un spécialiste d'après les
compétences déclarées et la clarification quand le registre ne contient aucun
profil adapté. Le test utilise les mêmes propriétés Ollama que le Parser.

Avec le prompt V1.2, `qwen3.5:4b-q4_K_M` a réussi les deux scénarios.
Ministral 8B Q4 a choisi le spécialiste quand il était disponible, puis a
sélectionné à tort le Writer comptable dans le scénario sans spécialiste.
Java vérifie l'appartenance au registre. L'adéquation du profil à la demande
est évaluée par le modèle et couverte par ces scénarios.

## Vérification explicite du ScribeAgent

```sh
mvn -Dtest=ScribeAgentOllamaIT -Dollama.integration=true test
```

Le test affiche les brouillons académique, scientifique, personnel et
administratif produits à partir de plans synthétiques. Il utilise Qwen
`qwen3.5:4b-q4_K_M` et un délai de 300 secondes par défaut. Les propriétés
`ollama.model`, `ollama.url` et `ollama.timeoutSeconds` sont configurables.

## Vérification explicite du gateway avec Qwen

Le test [OllamaLlmGatewayIT](../src/test/java/fr/mas/email/llm/ollama/OllamaLlmGatewayIT.java)
est exclu de la sélection de tests par défaut. Pour l'exécuter avec le serveur et
le tag réellement installés :

```sh
mvn -Dtest=OllamaLlmGatewayIT -Dollama.integration=true -Dollama.model=qwen3.5:4b-q4_K_M test
```

Paramètres facultatifs :

- `-Dollama.url=http://127.0.0.1:11434` : origine du serveur ;
- `-Dollama.model=nom:tag` : modèle à utiliser ;
- `-Dollama.timeoutSeconds=120` : délai, incluant le chargement initial du modèle.

Sur la machine de développement, avec le Maven intégré à IntelliJ :

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot'
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.1.3\plugins\maven\lib\maven3\bin\mvn.cmd' '-Dmaven.repo.local=.tools/m2' '-Dtest=OllamaLlmGatewayIT' '-Dollama.integration=true' '-Dollama.model=qwen3.5:4b-q4_K_M' test
```

Ce test demande un court brouillon à partir d'un texte fictif, puis vérifie qu'un
`Success<DraftContent>` valide est retourné. Il n'utilise aucun email réel et
n'envoie rien. Son succès vérifie le raccordement technique au modèle, pas la
qualité générale des réponses métier.

La vérification historique du 13 novembre 2026 utilisait Ollama **0.40.16** et
**qwen3.5:4b-q4_K_M** avec `DraftContent`. [Bilan de la phase 2.1](phase-2.1.md)
