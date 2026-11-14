# MAS Email — contrats JSON et ParserAgent V3.0

Application Java 21 de préparation de brouillons d'email avec un LLM local.
L'application Gmail lit les messages, prépare leur réponse et l'envoie après validation explicite.
Le banc de test séparé permet de suivre le pipeline avec des exemples.

La phase 2.1 intègre les sept contrats JSON validés, leurs schémas et leurs contrôles Java.
La phase 3.1 intègre le ParserAgent V2.6 et le champ `requestedActions`.
La phase 3.1.1 retire `missingInformation` d'`EmailAnalysis` et porte le délai du Parser à 300 secondes.
Le branchement du Parser et du DecisionAgent ainsi que les tests avec Ministral 8B figurent dans le [guide Ollama](docs/ollama.md).

Les Writers `AcademicWriter`, `ResearchWriter`, `FinanceWriter`, `PersonalWriter`
et `GenericWriter` partagent le prompt WriterAgent V1.0. L'appelant peut construire
le registre de routage avec `DecisionInput.forAuthorizedWriters(analysis, ids)`.
Après validation du routage, `OrchestratorAgent.executeWriter(...)` reçoit le
Writer sélectionné et un `WriterInput` construit à partir de l'analyse. Le Writer
envoie trois blocs JSON compacts au LLM, `emailAnalysis`, `writerProfile` et
`miniWorlds`. Le validateur de plan fourni par l'appelant vérifie que chaque
fait du `ResponsePlan` est étayé par une source autorisée.
[Profils spécialisés](src/main/resources/writer_profiles.json) · [Ressources MiniWorlds](src/main/resources/miniworlds/)

Le Writer renseigne le ton et la structure à partir de son profil. Après
validation du plan, `AgentFactory.createScribe(config)` permet de produire
un `DraftResponse` au statut `DRAFT`. Le test Ollama des quatre cas de
référence affiche les brouillons pour relecture. [Guide Ollama](docs/ollama.md#vérification-explicite-du-scribeagent)

Pour découvrir le projet, consulter le [guide de lecture du code](docs/guide-code.md) :
rôle de chaque fichier Java et de chaque fichier de tests, liens entre les composants,
exemple de parcours d'un plan et ordre de lecture conseillé.

## Compiler et tester

Prérequis : **JDK 21** et **Maven 3.6.3 ou supérieur**. Vérifier que `mvn -version`
utilise bien Java 21. Le premier lancement récupère les dépendances Maven ; aucun modèle
LLM n'est téléchargé.

```sh
mvn clean verify
mvn test
```

Le premier appel compile les sources, exécute JUnit et produit le JAR de la
bibliothèque et le JAR exécutable `target/mas-email-0.1.0-SNAPSHOT-app.jar`.
Les rapports sont dans `target/surefire-reports/`. Les fichiers temporaires des tests
sont créés sous `target/` puis supprimés par JUnit.

Sur la machine de développement utilisée pour cette phase, Java 17 était dans le PATH,
mais Java 21 et le Maven intégré à IntelliJ étaient déjà installés. Commande PowerShell
équivalente, sans modifier la configuration globale :

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot'
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.1.3\plugins\maven\lib\maven3\bin\mvn.cmd' '-Dmaven.repo.local=.tools/m2' -B -ntp clean verify
```

`.tools/m2` est un cache local facultatif, ignoré par Git. Après téléchargement,
`-o` permet de travailler hors ligne. Aucun chemin propre à cette machine n'est codé
dans les sources ou le POM.

Dépendances fixées dans le POM : Jackson BOM 2.20.2, NetworkNT JSON Schema Validator 1.5.9,
Jakarta Mail via Eclipse Angus 2.0.5 et JUnit Jupiter 5.13.4. NetworkNT permet d'appliquer les schémas Draft 2020-12 fournis
sans créer un validateur de schéma maison ; Jackson assure toujours la sérialisation.
[Documentation de NetworkNT 1.5.9](https://github.com/networknt/json-schema-validator/tree/1.5.9).
Références : [Jackson 2.20](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.20),
[JUnit 5](https://docs.junit.org/5.13.4/user-guide/),
[Maven Compiler Plugin](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.13.0/).
Aucun framework applicatif supplémentaire.

## Utiliser Gmail

```sh
java -jar target/mas-email-0.1.0-SNAPSHOT-app.jar
```

Au premier démarrage, l'assistant demande votre adresse Gmail, votre nom facultatif et
les informations de vos six espaces : Commun, Académique, Recherche, Finance, Personnel et
Générique. Les espaces peuvent rester vides. Une ligne décrit un sujet précis et sa valeur :
`date publication notes INF312` → `12 décembre 2026`. Le sujet devient une clé normalisée
`date_publication_notes_inf312`. Précisez toujours le cours, dossier ou événement concerné.
**Enregistrer mes informations** confirme leur stockage local ; **Annuler** ne publie aucun profil.
**Modifier les connaissances** permet ensuite d'ajouter, modifier ou supprimer ces informations
dans chacun des six espaces. L'enregistrement remet à zéro le brouillon affiché pour que le
prochain calcul utilise les connaissances à jour.

1. Activez la validation en deux étapes sur votre compte Google et créez un
   [mot de passe d’application](https://support.google.com/accounts/answer/185833?hl=fr).
   Cliquez sur **Connexion / Actualiser Gmail** et saisissez le code généré par Google.
2. Sélectionnez un des 50 derniers emails de la boîte de réception et cliquez sur
   **Ouvrir l’email sélectionné**. La lecture se fait sans marquer le message comme lu.
3. Démarrez Ollama, renseignez un modèle installé, puis cliquez sur **Générer le brouillon**.
   Répondez aux clarifications et demandez des corrections si nécessaire.
4. Cliquez sur **Valider le brouillon**, puis sur **Envoyer la réponse validée**.
   La fenêtre de confirmation affiche le destinataire, l'objet et le texte exacts avant tout envoi.
   **Annuler** n'envoie rien. Le destinataire vient de `Reply-To` ou de l'expéditeur ; le LLM ne le choisit pas.

**Modifier l’email entrant** ouvre un formulaire pour changer son objet et son texte après
chargement depuis Gmail. La modification est locale : ni le message Gmail ni l'adresse de
réponse ne changent. Enregistrer une modification efface le brouillon affiché et sa validation ;
il faut générer puis valider une nouvelle réponse. Annuler conserve le travail en cours.

**Changer de compte Gmail** permet de saisir une autre adresse. Le mot de passe d’application précédent
est effacé de la session et la boîte affichée, le message chargé et le brouillon sont remis à zéro.
Cliquez ensuite sur **Connexion / Actualiser Gmail** pour saisir le mot de passe d’application du nouveau compte.
Un compte ajouté démarre avec des connaissances vides ; utilisez
**Modifier les connaissances** pour les renseigner. Revenir à une adresse déjà utilisée restaure
ses connaissances. Le dernier compte choisi est conservé au redémarrage. Annuler ne change rien.

La connexion utilise Jakarta Mail avec [Eclipse Angus](https://eclipse-ee4j.github.io/angus-mail/) :
IMAPS `imap.gmail.com:993` et SMTP `smtp.gmail.com:587` avec STARTTLS obligatoire et vérification
du certificat et du nom de serveur. [Paramètres officiels Gmail](https://developers.google.com/workspace/gmail/imap/imap-smtp).
Le mot de passe d’application reste en mémoire pendant la session. Après une déconnexion,
un changement de compte ou un redémarrage, saisissez le mot de passe d’application du compte actif.

Les données sont dans `data/user/` (modifiable avec `-Dmas.userDirectory=...`) :

- `account.json` contient l'identité et la référence au jeu de connaissances actif, sans secret ;
- `knowledge/<id>/` contient les six MiniWorlds JSON et les connaissances confirmées ultérieurement ;
- `accounts/<identifiant>/account.json` conserve la configuration des comptes précédemment utilisés ;
- `runs/<identifiant-compte>/<date-id>/` contient `agents.log`, les états, la validation et `mail-delivery.json` lors d'un envoi.

Les anciens journaux créés directement sous `runs/<date-id>/` restent en place.

Les sauvegardes du formulaire publient un nouveau jeu complet de MiniWorlds atomiquement ;
les anciennes versions restent sur disque. Les données fictives de Camille, d'INF312 et du bowling
ne sont **pas chargées comme connaissances dans le mode Gmail**. Elles restent disponibles dans le banc de test.
Les exemples des prompts guident le format, sans constituer une source de faits personnels.

Une correction invalide l'approbation du brouillon. Un envoi en cours est enregistré avant l'appel
SMTP ; une erreur ou interruption laisse un état `UNKNOWN` ou `SENDING` et bloque un second envoi
dans cette exécution. Vérifiez les messages envoyés dans Gmail ; aucune relance automatique n'est effectuée.

Limites V1 : un seul compte actif à la fois, boîte INBOX, 50 messages récents, réponses texte à un
destinataire unique, sans pièces jointes. Le HTML reçu est converti en texte sans charger d'images distantes.
Le texte extrait est limité à 1 Mo. Il n'y a ni synchronisation continue, ni suppression de messages.
L'utilisateur reste responsable de relire les faits et le destinataire avant validation.

Les tests Gmail utilisent des fournisseurs Jakarta simulés et un faux serveur Ollama HTTP local.
Ils couvrent aussi le parcours Swing et l'annulation de l'envoi. Aucun compte Gmail réel n'est
contacté par `mvn test`. Pour vérifier une vraie connexion, utilisez votre compte depuis l'interface ;
un envoi réel nécessite toujours votre confirmation dans l'application.

## Tester depuis le banc de test

```sh
java -jar target/mas-email-0.1.0-SNAPSHOT-app.jar --test-bench
```

- **Simulation INF312** utilise des réponses fixes et l'email fourni dans la
  fenêtre. Cliquez sur **Générer le brouillon** pour vérifier les quatre étapes
  et le journal sans Ollama.
- **Simulation clarification** suspend le même exemple sur la date de
  publication. Saisissez une date dans le formulaire affiché, choisissez
  la portée et cliquez sur **Répondre à la clarification**. Les données enregistrées en simulation
  sont isolées dans `data/simulation-knowledge/`.
- **Ollama local** utilise l'adresse et le modèle affichés dans la fenêtre.
  Démarrez Ollama, renseignez un modèle déjà installé, puis collez votre email.
  L'adresse initiale est `http://127.0.0.1:11434` et le modèle initial est
  `qwen3.5:4b-q4_K_M`. [Configuration Ollama](docs/ollama.md)

Chaque exécution crée `data/runs/<date-id>/agents.log`. **Ouvrir le journal**
affiche le fichier dans l'éditeur associé sur la machine. Le fichier UTF-8
contient les sorties structurées des agents en JSON indenté, les durées et
les erreurs ou clarifications éventuelles. Il peut contenir des extraits du
message entrant et le texte du brouillon. En cas de clarification, la fenêtre
affiche les questions et reprend à l'étape suspendue après la réponse.
Pour un fait demandé par le Writer, choisissez **K-Commun**, **K-Particulier**
ou **Répondre sans enregistrer**. Les connaissances confirmées sont écrites
dans `data/knowledge/` et relues aux exécutions suivantes. Une clarification
de routage sert uniquement à la session. Une réponse de session ne modifie
aucun fichier de connaissances. Le journal et le brouillon peuvent contenir
les faits utilisés. Le brouillon est à relire par l'utilisateur.

Sous le brouillon, cliquez sur **Valider le brouillon** pour enregistrer votre
approbation locale de cette version dans le journal et dans `draft-approval.json`.
Une nouvelle correction rend cette approbation caduque. Le banc de test ne propose
pas d'envoi ; l'envoi réel est réservé à l'application Gmail décrite ci-dessus.
Vous pouvez aussi saisir une demande sur plusieurs lignes puis cliquer sur
**Corriger le brouillon**. Le journal affiche le plan de correction et l'étape relancée.
Pour une nouvelle information, vérifiez ou modifiez les valeurs proposées
puis choisissez la session, K-Commun ou K-Particulier avant de valider.
Chaque exécution accepte au plus trois corrections, demandes d'agents comprises.
Le mode simulation applique une correction de style prédéfinie. La trace
locale `execution.json` contient le message d'origine et les sorties courantes
nécessaires à la reprise depuis chaque étape.

À la lecture d'une sortie LLM de correction, les majuscules ASCII des clés
mémoire sont normalisées en minuscules. Le schéma de génération du CorrectingAgent est plat,
avec ses six champs obligatoires, sans `oneOf` combiné aux propriétés communes : cette combinaison
peut être mal interprétée par les décodeurs JSON. Le schéma payload complet et le constructeur
Java contrôlent toujours les associations cible/type, la validation utilisateur et les propositions.
Un objet contenant uniquement `targetAgent` reste rejeté, sans fabrication des champs manquants
ni relance automatique. Les libellés descriptifs seuls peuvent être complétés comme décrit ci-dessous.

Pour une proposition
`ORCHESTRATOR / UPDATE_KNOWLEDGE` nécessitant une validation, Java peut compléter
les libellés `instruction` et `reason` absents avec des textes de validation
standards. Les valeurs, la portée et la demande de validation restent
obligatoires. Le contrat JSON persistant conserve ses six champs obligatoires.
Une erreur de correction conserve le dernier brouillon encore valide pour
permettre son approbation ou une nouvelle demande explicite, dans la limite
des trois corrections.

Le MiniWorld académique de démonstration déclare une portée structurée `scope` limitée à INF312
(avec son nom explicite comme alias). Pour une question concernant UEMF21,
un cours non identifié ou plusieurs cours dont un n'est pas couvert, le Writer
demande les réponses manquantes avant tout appel LLM : les dates d'INF312 et
les exemples du prompt ne peuvent pas répondre à ces questions. Une réponse
utilisateur disponible sous la clé exacte demandée permet la reprise ; par
exemple `date_publication_notes_uemf21` reste distincte de
`date_publication_notes_inf312`. Une notification hors de cette portée peut
toujours être traitée, mais sans l'état ni les réponses du cours INF312.
Ce contrôle protège la portée déclarée ; il ne valide pas sémantiquement tous
les faits produits par le LLM. Les connaissances persistées restent indexées
par clé : une clé générique partagée entre plusieurs cours reste ambiguë.
Les autres MiniWorlds ne déclarent pas encore de portée structurée.

## Organisation des fichiers créés

Les six packages existants sont conservés : `agent`, `contract`, `result`, `orchestration`, `llm` et `world`.
Le [guide du code](docs/guide-code.md) décrit chaque fichier.

Références de cette phase :

- contrats Obsidian : `Architecture/MAS_Email_BDI_Obsidian/04 - Contrats JSON/` ;
- schémas validés : `docs/schemas/{llm,payload,message}/`, embarqués dans le JAR par Maven ;
- exemples fournis : `docs/examples/{valid,invalid}/`, utilisés directement par les tests ;
- [bilan de migration et limites](docs/phase-2.1.md) ;
- [branchement Ollama](docs/ollama.md).

## Responsabilités et patterns effectivement implémentés

| Élément | Responsabilité | Pattern |
| --- | --- | --- |
| `AgentFactory`, `AgentConfig` | Construire des agents typés, vérifier rôle et identité, attribuer leur accès au MiniWorld. Plusieurs Writers partagent exactement la même classe. | Factory simple |
| `OrchestratorAgent` | Exécuter une étape explicite, contrôler son résultat, créer son message, persister l'état, suspendre et autoriser une reprise. Aucune dépendance LLM. | Mediator |
| `AgentBehavior<I,O>` | Injecter le traitement d'un agent sans modifier son enveloppe d'exécution. La stratégie par défaut renvoie `NOT_IMPLEMENTED`. | Strategy |
| `PlanValidationStrategy<T>`, `ResponsePlanValidator` | Appliquer des contrôles Java remplaçables avant de laisser passer une production. | Strategy |
| `WorldRepository<T>`, `FileWorldRepository<T>` | Abstraire et réaliser le stockage d'un seul World. | Repository |
| `LlmGateway`, `FakeLlmGateway`, `OllamaLlmGateway` | Isoler les agents du moteur ; choisir le simulateur ou le serveur Ollama par injection. | Adapter |
| `JsonCodec`, DTO, `MessageFactory` | Valider les contrats et séparer contenu métier et métadonnées applicatives. | Pas de pattern supplémentaire |
| `AgentResult<T>`, `Errors` | Distinguer succès, clarification et incident avec des types scellés et des traces Java. | Pas de pattern supplémentaire |

Pas de Chain of Responsibility, de conteneur d'injection, de bus ou de microservices.
Les packages `contract`, `result` et `world` constituent le modèle ; `orchestration`
fournit le point d'entrée à un futur contrôleur MVC. Aucune vue n'est développée.

## Contrats et résultats

Les DTO sont des records immuables, validés à la construction, y compris par Jackson.

| Contrat | Sortie LLM | Ajout / contrôle Java |
| --- | --- | --- |
| EmailAnalysis 3.0 | `EmailAnalysis.Content` avec informations demandées, actions demandées et citations | `emailId` et `threadId` |
| RouteDecision | `RouteDecision.Content` : candidats, sélection/statut, raisons, confirmation/question, confidence=null | emailId ; appartenance au registre autorisé |
| ResponsePlan | `ResponsePlan.Content` : intention, faits sourcés, manques, clarification | planId, agentId, emailId, structure/langue/ton ; validation des sources |
| DraftResponse | `DraftContent` : subject/body | emailId, planId, DRAFT ; lien au plan courant et fidélité |
| CorrectionPlan | Payload entier, sans métadonnées | couples cible/action, conditions READY/NEEDS_CLARIFICATION |
| DiscussionPlan | Jamais généré directement | Normalisation des questions du JSON métier, tâche, proposition mémoire |
| ErrorPlan | Jamais généré | Incident avec stade, provenance, résultat attendu, problème observé, STOPPED |

`AgentMessage<T>` et `MessageMetadata` portent la version de chaque contrat.
`EMAIL_ANALYSIS` utilise `3.0` et `CORRECTION_PLAN` utilise `2.0`. Les cinq autres contrats utilisent `1.0`.
`JsonCodec` rejette les versions non prises en charge. `MessageFactory` génère UUID et date, contrôle
la corrélation des discussions/incidents et l'identité du Writer.

`JsonCodec` refuse champs inconnus/absents, doublons, tokens supplémentaires,
mauvais types et conversions implicites. Les nulls sont acceptés seulement aux endroits
autorisés ; le champ doit toujours être présent. Les schémas fournis vérifient aussi
les formats UUID/date. Les constructeurs distinguent une violation métier
(`INVALID_OUTPUT`) d'un format incorrect (`INVALID_JSON`).

`AgentResult<T>` conserve ses branches `Success<T>`, `DiscussionPlan<T>` et
`ErrorPlan<T>`. Une sortie initiale du Writer peut contenir une demande de clarification ;
le coordinateur la convertit avant de poursuivre. Aucun second appel LLM.
`AgentResult.map` transforme exclusivement le succès et préserve tous les champs des autres branches.

Les incidents Java utilisent des messages contrôlés, sans recopier JSON, prompts,
secrets ou chemins. Une trace absente reste `null`. Une réponse LLM vide produit
`EMPTY_RESULT`, un résultat Java absent `NO_RESULT` et un timeout identifiable
`LLM_TIMEOUT`. Aucun code ne déclenche de relance automatique.

## Coordination minimale et reprise

`OrchestratorAgent.execute` exécute une étape fournie explicitement par le contrôleur.
Il n'assemble pas le workflow email complet et n'utilise pas de LLM.

- `RUNNING` est persisté avant l'exécution ;
- un résultat validé devient `COMPLETED` ;
- une clarification devient `WAITING_USER` et bloque les étapes suivantes ;
- un incident devient `FAILED` ;
- une réponse ou autorisation explicite de retry prépare `READY`, avec une nouvelle tentative.

`SuspendedDiscussion` conserve dans `SharedWorld.detail` le message initial, la demande
enveloppée avec ses questions, puis la réponse et le DTO de reprise. Ces données survivent
au redémarrage. `resumeWithAnswer(UserDiscussionAnswer)` exige le bon `requestMessageId`.
Le chemin existant `resumeWithAnswer(taskId, answer)` reste disponible pour le contrôleur de confiance.

La reprise ne relance rien : le contrôleur complète les entrées et appelle à nouveau
`execute`. Le choix mémoire NONE produit NOT_STORED. K_PERSO/K_COMMUN sont reconnus
par les DTO, mais leur traitement reste bloqué dans ce coordinateur tant qu'un service
d'écriture autorisé n'est pas raccordé. Aucun succès de stockage n'est simulé.

Au redémarrage, un état RUNNING résiduel devient un incident. Une sauvegarde échouée
bloque la suite. Le format de `SharedWorld` et ses permissions restent inchangés ;
les anciens snapshots d'attente contenant seulement une question textuelle nécessitent
une migration explicite. L'historique complet et la reconstruction de toutes les entrées
du workflow restent hors périmètre.

## Worlds et permissions

`FileWorldStore` est réservé au code de composition de confiance :
- transmettre `sharedWorld()` uniquement au coordinateur ;
- transmettre `AgentFactory` uniquement au code de construction ;
- chaque agent reçoit un `WorldRepository<MiniWorld>` lié à son identité, sans méthode
  permettant de choisir un autre propriétaire ou chemin ;
- les Writers disposent d'un accès en lecture à leur MiniWorld ;
- les autres agents disposent d'une lecture seule.

Le propriétaire est vérifié à la lecture et à l'écriture. Les noms de fichiers
encodent l'identifiant en hexadécimal pour éviter traversées de chemins, noms Windows
réservés et collisions entre identifiants qui ne diffèrent que par la casse.
Les liens symboliques et les redirections détectables par résolution du chemin sont refusés.

Une écriture utilise un fichier temporaire dans le même dossier, force son contenu
sur disque, puis remplace la destination par déplacement atomique. Si le système de
fichiers ne permet pas cette opération, l'écriture échoue ; aucun remplacement
non atomique n'est tenté. Un fichier corrompu produit une erreur de lecture, jamais
un World vide silencieusement recréé.

La V1 suppose un seul processus et une seule instance de coordinateur par dossier.
Ce cloisonnement est une permission applicative, pas un bac à sable pour du Java hostile :
du code ayant un accès direct au disque ou au `FileWorldStore` reste de confiance.
La persistance n'apporte ni transactions multi-fichiers ni garantie complète contre
une panne matérielle après renommage.

## Extension LLM et BDI

Tous les comportements génératifs reçoivent le port `LlmGateway` dans `AgentContext`.
`LlmRequest<T>` admet uniquement les cinq contenus LLM de la table ci-dessus, jamais
les payloads enrichis par Java. `FakeLlmGateway` et `OllamaLlmGateway` utilisent la même
validation. Le second reçoit les schémas LLM fournis dans son champ HTTP `format`.
Le helper approximatif par réflexion `RecordJsonSchema` a été remplacé par `ContractSchemas`.

Le modèle et l'adresse sont configurables. Le délai couvre l'échange HTTP complet.
`AgentFactory.createParser(config)` branche l'extraction V3.0 et
`AgentFactory.createDecision(config)` branche le routage V1.2. Les stratégies
explicitement créées avec `AgentBehavior.unconfigured()` renvoient `NOT_IMPLEMENTED`.

Les points d'extension BDI existants sont conservés : beliefs dans WriterInput,
objectifs/contraintes dans AgentConfig, intentions explicites et ResponsePlan.
Aucun moteur de délibération n'est ajouté.

Trois validateurs réutilisent `PlanValidationStrategy<T>` :

- RouteDecisionValidator vérifie l'email et les Writers autorisés/éligibles/disponibles fournis par l'application ;
- ResponsePlanValidator exige un contrôle de source pour chaque fait ; sans résolveur, il les refuse ;
- DraftResponseValidator vérifie le plan courant et appelle un contrôle de fidélité fourni par l'application.

La méthode sémantique de vérification n'étant pas décidée dans les spécifications,
ces stratégies ne prétendent pas vérifier la vérité automatiquement.

## Décisions et ambiguïtés documentaires

Les décisions validées du 13 novembre 2026 remplacent les DTO minimalistes de phase 1 :
routage LLM en V1, clarifications dans le JSON initial, sept contrats et enrichissement Java.

Deux incohérences de référence sont documentées sans modifier les sources :

- certains liens Obsidian visent `docs/contracts/` ; les fichiers fournis sont dans `docs/` ;
- l'exemple valide `response-plan.clarification.json` porte ResearchWriter dans les métadonnées
  et AdministrationWriter dans le payload. Il passe la validation structurelle ; Java refuse
  de créer un nouveau message avec cette incohérence d'identité.

Les vocabulaires intention/thèmes/types d'entités/langue/ton/structure restent ouverts
là où aucun enum n'est validé. Restent à définir : vérification sémantique des sources
et du brouillon, règles de stockage des connaissances, calibration future et politique
complète de traces. Voir le [bilan de phase 2.1](docs/phase-2.1.md).

## Vérification et limites de la phase

Lancer `mvn clean verify` sous Java 21. Les tests couvrent les exemples fournis, les schémas,
les mutations de champs, les combinaisons métier, la frontière LLM/Java, le versionnement,
les permissions, les incidents, la suspension/reprise et le transport HTTP simulé.
Le [bilan de phase 2.1](docs/phase-2.1.md) retrace la validation initiale des contrats.

La suite automatique utilise des tests HTTP simulés sur localhost. Les essais
avec un modèle local s'activent explicitement. [Commandes Ollama](docs/ollama.md#vérification-explicite-du-parser-avec-ministral-8b)

Le moteur BDI complet reste à développer. L'intégration Gmail V1 est disponible dans l'application utilisateur.
