# Instagram Evidence Recovery

Outil **local** de récupération, de centralisation et de conservation de preuves
pour **votre propre compte Instagram**.

Il s'adresse au cas suivant : vous êtes propriétaire d'un compte Instagram, vous
en connaissez le nom d'utilisateur, mais vous n'avez plus accès au compte, ni à
l'adresse e-mail associée, ni éventuellement au numéro de téléphone. L'outil
rassemble tout ce à quoi vous avez **légalement accès**, l'analyse, l'horodate,
en vérifie l'intégrité et produit un rapport exploitable.

Tout reste sur votre ordinateur. Aucune conversation, aucun fichier et aucune
métadonnée n'est envoyé vers un serveur, une API, un service cloud ou un modèle
de langage.

---

## Ce que l'outil fait

| Fonction | Détail |
|---|---|
| Dossier d'enquête | Un dossier par compte : `cases/<nom_utilisateur>/`, entièrement portable |
| Données publiques | Lecture de la page publique du profil, sans identifiant ni cookie, ou saisie manuelle (HTML, JSON, capture, PDF) |
| Import d'export Instagram | ZIP, dossiers décompressés, JSON, HTML — détection résistante aux renommages de Meta |
| Conversations | Reconstruction complète des fils, affichage type messagerie, recherche multicritère, source originale de chaque message |
| Abonnés / abonnements | Listes complètes, tri par date réelle ou alphabétique, autres relations (demandes, blocages, amis proches) |
| Éléments manquants | Détection d'indices d'absence avec niveau de confiance — **jamais** de contenu reconstitué |
| Comparaison d'exports | Deux exports de dates différentes → apparitions, disparitions, messages ajoutés ou absents |
| Chronologie | Timeline par jour, chaque événement avec sa source et son niveau de confiance |
| Recherche globale | Un terme cherché simultanément dans messages, personnes, événements, fichiers, médias, preuves, métadonnées |
| Sources locales | Captures, vidéos, sauvegardes de téléphone, e-mails, PDF, TXT, CSV, JSON — avec SHA-256 et provenance déclarée |
| Android | Guide de récupération sur votre propre appareil + import ADB du stockage partagé |
| Mode preuve | Fichiers sources en lecture seule, `manifest.json` d'empreintes SHA-256, vérification d'intégrité |
| Rapport final | HTML, PDF, JSON et CSV, en 12 sections, chaque élément renvoyant à sa source |

## Ce que l'outil ne fait jamais

- Aucune tentative de piratage, d'exploitation de faille ou de contournement
  d'authentification.
- Aucun vol de cookie ou de session, aucun identifiant d'autrui.
- Aucun accès aux serveurs privés de Meta, aucune API privée.
- Aucun accès aux messages privés d'un tiers.
- Aucun rooting du téléphone, aucun accès au bac à sable protégé d'Instagram.
- **Aucune information inventée.** Si une date n'existe pas dans la source,
  l'outil affiche « Date inconnue ». Si un contenu est absent, il est présenté
  comme absent, avec son niveau de confiance, jamais comme un contenu retrouvé.

Si Instagram limite la consultation publique automatisée, l'outil enregistre le
blocage tel quel et propose des voies manuelles. Il ne contourne rien.

---

## Installation (Windows)

1. Installez [Python 3.9 ou supérieur](https://www.python.org/downloads/windows/)
   en cochant **« Add Python to PATH »**.
2. Double-cliquez sur **`setup.bat`** (une seule fois).
3. Double-cliquez sur **`start.bat`**. Le navigateur s'ouvre sur
   `http://127.0.0.1:8734/`.

Pour arrêter : fermez la fenêtre noire.

### Exécutable autonome (facultatif)

Après `setup.bat`, lancez **`build_exe.bat`**. Vous obtenez
`dist\InstagramEvidenceRecovery.exe`, qui fonctionne sans installer Python. Les
dossiers d'enquête sont créés dans un sous-dossier `cases` placé à côté de
l'exécutable.

### Linux / macOS

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python run.py
```

---

## Premier lancement

1. Saisissez le nom d'utilisateur : `@nom_utilisateur`.
2. Cliquez sur **Créer le dossier** → `cases/nom_utilisateur/` est créé.
3. Onglet **Profil** : lancez la collecte publique (ou collez le HTML de la page).
4. Onglet **Imports** : importez votre archive Instagram (ZIP ou dossier).
5. Onglet **Sources** : ajoutez captures, e-mails, PDF, sauvegardes de téléphone.
6. L'analyse démarre automatiquement à chaque import.
7. Le **Tableau de bord** affiche le niveau de récupération atteint.

L'onglet **Récupérer davantage** indique en permanence ce qui manque et comment
l'obtenir.

### Obtenir une archive Instagram

Depuis le compte (donc après récupération de l'accès) :
**Paramètres → Espace comptes → Vos informations et autorisations →
Télécharger vos informations**. Choisissez le format **JSON** et « toutes les
données » : c'est la source la plus complète.

Cherchez aussi une archive déjà téléchargée par le passé : dossier
Téléchargements d'anciens ordinateurs, `Download/` du téléphone, Google Drive,
pièces jointes d'e-mails, disques et cartes SD.

---

## Organisation d'un dossier d'enquête

```
cases/nom_utilisateur/
├── case.json        description du dossier
├── case.db          base SQLite locale (toutes les données normalisées)
├── manifest.json    empreintes SHA-256 figées (mode preuve)
├── sources/         copies intégrales et intactes des fichiers fournis
├── extracted/       contenu décompressé des archives
├── public/          collectes publiques horodatées
├── exports/         exports intermédiaires
└── reports/         rapports générés (HTML, PDF, JSON, CSV, ZIP)
```

Les fichiers de `sources/` ne sont **jamais** modifiés. Toute analyse produit une
copie normalisée en base ; la donnée brute d'origine de chaque message est
conservée intégralement et consultable via « Afficher la source originale ».

---

## Mode preuve

Une fois vos imports terminés, activez le **mode preuve** :

- les fichiers sources passent en lecture seule ;
- tout import et toute suppression sont refusés ;
- `manifest.json` fige nom, chemin, SHA-256, taille et date d'import de chaque
  fichier, plus une empreinte du manifeste lui-même ;
- la vérification d'intégrité recalcule toutes les empreintes et signale le
  moindre fichier modifié ou disparu depuis l'import.

---

## Niveaux de confiance

Chaque événement de la chronologie et chaque élément manquant porte un niveau :

| Niveau | Signification |
|---|---|
| **CONFIRMÉ** | La donnée figure telle quelle dans une source vérifiée |
| **PROBABLE** | Plusieurs indices structurels concordants, sans certitude |
| **POSSIBLE** | Indice unique ou anomalie statistique |
| **INCONNU** | Indice insuffisant pour conclure |

Exemple de formulation employée par l'outil, jamais remplacée par une date
inventée :

> « Présent dans l'export du 27/10/2025 et absent de l'export du 14/11/2024 :
> ajout intervenu entre ces deux dates. »

---

## Confidentialité

- Le service n'écoute que sur `127.0.0.1` et refuse toute connexion distante.
- Aucune donnée n'est transmise à un tiers.
- La seule requête réseau possible est la consultation de l'URL publique du
  profil, déclenchée explicitement par vous depuis l'onglet Profil.
- L'analyse fonctionne intégralement hors ligne.

---

## Développement

```bash
python3 -m pytest tests/ -q     # 57 tests
python3 run.py --port 8734      # service local
```

Structure du code :

```
app/
├── config.py            constantes et chemins
├── db.py                schéma SQLite et accès
├── cases.py             dossiers d'enquête
├── utils.py             dates, encodages, empreintes
├── people.py            fusion des personnes
├── integrity.py         mode preuve, manifeste, vérification
├── sources_local.py     sources locales et éléments de preuve
├── jobs.py              tâches de fond (gros imports)
├── importers/           détection, ingestion, parseurs (messages, relations, activité)
├── analysis/            lacunes, comparaison, chronologie, recherche, statistiques
├── publicdata/          collecte publique
├── androidmod/          module Android (ADB + guide)
├── reporting/           construction et rendu du rapport
└── api/                 routes HTTP locales
frontend/                interface (HTML/CSS/JS, sans build)
tests/                   suite pytest + générateur d'exports factices
```

## Limites connues

- Les horodatages des exports JSON sont en UTC. Ceux des exports HTML sont
  écrits par Meta sans fuseau : ils sont repris tels quels et interprétés en
  UTC, ce qui peut décaler de quelques heures.
- Aucune reconnaissance de texte n'est appliquée aux images : décrivez leur
  contenu dans les observations pour les rendre interrogeables.
- Les messages supprimés côté Instagram et absents de tout export ne sont pas
  récupérables : Meta ne les restitue pas.
- Les messages privés ne sont pas lisibles depuis le stockage d'un téléphone
  Android non rooté ; seul l'export officiel les contient.
