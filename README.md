# MAS Email

MAS Email est une application de bureau Java qui prépare des réponses aux e-mails avec un modèle local via Ollama. Elle analyse le message, choisit un agent spécialisé, élabore un plan de réponse, puis génère un brouillon à relire. En mode Gmail, l’envoi nécessite votre validation du brouillon et une confirmation d’envoi.

## Prérequis

| Composant | Quand il est nécessaire |
| --- | --- |
| Java 21 et un environnement graphique | Pour lancer l’application Swing |
| JDK 21 et Maven 3.6.3 ou supérieur | Pour construire le JAR et exécuter les tests depuis les sources |
| Ollama en cours d’exécution et un modèle téléchargé | Pour générer une réponse à un e-mail de votre choix |
| Compte Gmail et mot de passe d’application Google | Pour lire des messages Gmail et envoyer les réponses confirmées |

Le mode de simulation fonctionne avec Java et l’interface graphique, même sans Ollama ni compte Gmail. Les dépendances Java sont gérées par Maven et incluses dans le JAR exécutable.

## Démarrage rapide

Depuis la racine du projet, vérifiez que `mvn -version` indique Java 21, puis construisez et lancez l’application.

```sh
mvn clean verify
java -jar target/mas-email-0.1.0-SNAPSHOT-app.jar
```

Pour générer des réponses avec Ollama, démarrez le service au préalable. Si le service ne tourne pas déjà, lancez `ollama serve` dans un autre terminal. Le modèle par défaut de MAS Email est `qwen3.5:4b-q4_K_M`. [Documentation Ollama](https://docs.ollama.com/cli) · [Modèle Qwen](https://ollama.com/library/qwen3.5:4b-q4_K_M)

```sh
ollama pull qwen3.5:4b-q4_K_M
ollama list
```

L’application interroge par défaut `http://127.0.0.1:11434`. Dans le menu **Modèle**, choisissez un modèle disponible. Le bouton **Actualiser** recharge la liste obtenue via `ollama list` pour l’adresse configurée. La commande `ollama` doit être dans le `PATH` pour remplir ce menu. Vous pouvez également saisir le nom du modèle dans le champ.

### Tester sans compte

Au démarrage, cliquez sur **Tester sans compte**.

- Pour traiter votre propre e-mail, sélectionnez **Ollama local**, collez le texte et cliquez sur **Générer le brouillon**.
- Pour tester le parcours avec des réponses prédéfinies, choisissez **Simulation INF312** ou **Simulation clarification** et utilisez l’exemple intégré. Le second scénario permet de répondre à une demande de clarification et de choisir si la réponse doit être mémorisée.
- Relisez le brouillon, demandez une correction si nécessaire et cliquez sur **Valider le brouillon** pour enregistrer votre approbation locale. **Ouvrir le journal** affiche le détail de l’exécution.

Pour tester un fil, gardez le même **Objet du fil de test**. Après validation, cliquez sur **Simuler l’envoi**, puis sur **Tour suivant**. Le panneau **Historique du fil** affiche les tours précédents et les réponses simulées. En mode **Ollama local**, collez le nouveau message. En simulation, le message de suivi est chargé automatiquement.

Pour tester la mémoire persistante, lancez **Simulation clarification**, saisissez une date et choisissez **K-Commun** ou **K-Particulier**. Au tour suivant, le Writer réutilise la date enregistrée. Avec **Répondre sans enregistrer**, la clarification est redemandée au prochain tour si la date n’a pas déjà été mémorisée. Le journal indique la portée choisie et les faits retenus par le Writer. Les conversations de test sont séparées de Gmail et les simulations ont leur propre historique.

Pour ouvrir directement le banc de test en ligne de commande, ajoutez `--test-bench` après le nom du JAR.

### Utiliser Gmail

1. Dans votre compte Google, activez la validation en deux étapes et générez un **mot de passe d’application**. Cette option peut dépendre des règles de votre compte ou de votre organisation. [Aide Google](https://support.google.com/accounts/answer/185833?hl=fr)
2. Choisissez **Ouvrir Gmail**. À la première ouverture, renseignez votre adresse et, si vous le souhaitez, votre nom et les connaissances de vos espaces.
3. Cliquez sur **Connexion / Actualiser Gmail** et saisissez le mot de passe d’application. MAS Email affiche jusqu’aux 50 derniers e-mails de la boîte de réception. [Paramètres IMAP et SMTP de Gmail](https://developers.google.com/workspace/gmail/imap/imap-smtp)
4. Sélectionnez un e-mail, ouvrez-le et générez le brouillon. Si une information manque, répondez à la clarification proposée. Vous pouvez ensuite demander une correction.
5. Relisez le texte, cliquez sur **Valider le brouillon**, puis sur **Envoyer la réponse validée**. La fenêtre de confirmation affiche le destinataire, l’objet et le texte qui seront envoyés.

Le mot de passe d’application reste en mémoire pendant la session. **Changer de compte Gmail** permet de sélectionner une autre adresse et de retrouver les connaissances déjà associées à ce compte. **Modifier l’email entrant** permet d’ajuster localement le texte à analyser. **Modifier les connaissances** ouvre les espaces de mémoire du compte.

## Fonctionnement

`ParserAgent → DecisionAgent → WriterAgent → ScribeAgent`

| Étape | Rôle |
| --- | --- |
| ParserAgent | Extrait l’intention, les demandes et les citations du message |
| DecisionAgent | Sélectionne un profil de rédaction ou demande une clarification de routage |
| WriterAgent | Prépare le plan à partir des connaissances accessibles au profil et demande les informations manquantes |
| ScribeAgent | Rédige le brouillon à partir du plan validé |
| CorrectingAgent | Produit un plan de correction sur demande ou lorsqu’un agent signale une incohérence |

L’orchestrateur Java coordonne les étapes, valide les sorties JSON, gère les clarifications et reprend à l’étape concernée. Une exécution accepte jusqu’à trois corrections. Une nouvelle version du brouillon nécessite une nouvelle validation.

Les connaissances peuvent être utilisées pour la session, partagées dans **K-Commun**, ou conservées dans le **K-Particulier** d’un profil. Les profils couvrent les domaines académique, recherche, finance, personnel et général.

La mémoire de conversation Gmail est séparée par compte. Les quatre agents du pipeline reçoivent au plus trois tours précédents. Le résumé est construit en Java à partir des sorties structurées et distingue les brouillons des messages dont l’envoi a été confirmé.

## Configuration et données locales

Les propriétés Java se placent avant `-jar`.

| Propriété | Valeur par défaut | Utilisation |
| --- | --- | --- |
| `ollama.url` | `http://127.0.0.1:11434` | Adresse initiale d’Ollama |
| `ollama.model` | `qwen3.5:4b-q4_K_M` | Modèle initial |
| `mas.userDirectory` | `data/user` | Comptes Gmail, connaissances, historiques et journaux |
| `mas.runsDirectory` | `data/runs` | Journaux du banc de test, avec les connaissances de test dans des dossiers voisins |

Chaque exécution écrit un fichier `agents.log`. Les données et journaux sont locaux et peuvent contenir des extraits d’e-mails, des réponses ou des connaissances saisies. L’envoi Gmail utilise les ports 993 pour IMAP et 587 pour SMTP avec STARTTLS.

## Développement

| Dépendance | Version | Usage |
| --- | --- | --- |
| Jackson BOM | 2.20.2 | Sérialisation et lecture des données JSON |
| NetworkNT JSON Schema Validator | 1.5.9 | Validation des contrats |
| Jakarta Mail avec Eclipse Angus | 2.0.5 | Connexion IMAP et envoi SMTP |
| JUnit Jupiter | 5.13.4 | Tests automatisés |

Exécutez `mvn test` pour lancer la suite standard, qui utilise des services simulés. `mvn verify` exécute les tests et construit le JAR autonome. Les essais sur un serveur Ollama réel se lancent explicitement. [Guide Ollama et tests d’intégration](docs/ollama.md)

- [Guide de lecture du code](docs/guide-code.md)
- [Schémas et contrats JSON](docs/schemas/)
- [Exemples de messages JSON](docs/examples/)
- [Architecture et cahier de conception](Architecture/MAS_Email_BDI_Obsidian/)
- [Historique de la phase 2.1](docs/phase-2.1.md)
