# RadarDeal — Android

**Moniteur d'annonces Vinted.** L'utilisateur enregistre des recherches ("veilles"), RadarDeal
les rescanne à intervalle régulier et signale immédiatement les nouvelles annonces et les
baisses de prix.

- Application Android native — Kotlin, Jetpack Compose, Material 3.
- Moteur de détection incrémental : **⚡ Ultra 3 s**, Rapide 5 s, Standard 15 s, Éco 30 s.
  Voir [PERFORMANCE.md](PERFORMANCE.md) pour les mesures réelles.
- **Aucun backend.** Aucun serveur, aucune API payante, aucun compte RadarDeal, aucun
  abonnement. Tout est stocké sur le téléphone.
- Paiement unique : rien n'est verrouillé derrière une fonctionnalité premium.

| | |
|---|---|
| Package | `com.radardeal.app` |
| Version | 1.1.0 (versionCode 2) |
| minSdk | 26 — Android 8.0 |
| targetSdk / compileSdk | 36 — Android 16 |
| APK release | `app/build/outputs/apk/release/RadarDeal-Android-v1.1.0.apk` |

---

## 1. Compiler le projet

Prérequis : **JDK 17 ou plus** et le **SDK Android** avec la plateforme 36 et les build-tools 36.

```bash
git clone <url-du-dépôt>
cd <dossier>

# Indiquer où se trouve le SDK Android (non versionné) :
echo "sdk.dir=/chemin/vers/Android/Sdk" > local.properties

./gradlew assembleDebug
```

L'APK de debug est écrit dans
`app/build/outputs/apk/debug/RadarDeal-Android-v1.1.0-debug.apk`.

Rien d'autre n'est nécessaire : pas de clé d'API, pas de fichier de configuration, pas de
service externe. Le wrapper Gradle télécharge lui-même la bonne version de Gradle.

### Installer le SDK sans Android Studio

```bash
# Command-line tools officiels, puis :
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

### Les commandes utiles

```bash
./gradlew test              # tests unitaires (JVM + Robolectric)
./gradlew lint              # analyse statique Android
./gradlew assembleDebug     # APK de debug
./gradlew assembleRelease   # APK de release signé (voir §2)
```

---

## 2. Signature de l'APK release

La clé de signature **n'est pas dans Git** et ne doit jamais y être.

`app/build.gradle.kts` lit un fichier `keystore.properties` à la racine du dépôt (ignoré par
`.gitignore`). Quand ce fichier est absent, `assembleRelease` fonctionne quand même : il
retombe sur l'identité de debug, ce qui permet à n'importe qui de compiler le projet sans
posséder la clé de production.

### Créer une clé (une seule fois, à conserver précieusement)

```bash
mkdir -p ~/.radardeal && chmod 700 ~/.radardeal

keytool -genkeypair -v \
  -keystore ~/.radardeal/radardeal-release.jks \
  -storetype PKCS12 \
  -alias radardeal \
  -keyalg RSA -keysize 4096 \
  -validity 10950 \
  -dname "CN=RadarDeal, O=RadarDeal, C=FR"
```

Puis, à la racine du dépôt :

```properties
# keystore.properties — NE JAMAIS COMMITER
storeFile=/chemin/absolu/vers/radardeal-release.jks
storePassword=…
keyAlias=radardeal
keyPassword=…
```

> **Sauvegardez le fichier `.jks` et son mot de passe.** Perdre la clé signifie que les
> futures mises à jour ne pourront plus s'installer par-dessus la version déjà distribuée :
> Android refuse une mise à jour signée par une autre clé.

### Compiler et vérifier

```bash
./gradlew assembleRelease

$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose \
  app/build/outputs/apk/release/RadarDeal-Android-v1.1.0.apk
```

La sortie attendue confirme les schémas modernes :

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
```

`v1 (JAR signing): false` est normal et voulu : le schéma v1 ne sert qu'en dessous d'Android 7,
et le minSdk du projet est 26.

---

## 3. Comment RadarDeal lit Vinted

RadarDeal **n'a pas de compte de service et n'utilise aucune API privée obtenue par
contournement**. Il se sert de la session Vinted normale de l'utilisateur :

1. L'utilisateur se connecte lui-même sur la **vraie page Vinted**, dans une WebView
   (`Réglages ▸ Connexion Vinted`). L'application ne voit jamais le mot de passe.
2. Android conserve les cookies de session dans son propre magasin, sur l'appareil.
3. Pour chaque scan, RadarDeal demande la page de catalogue correspondant à la veille —
   la même requête que fait le site Vinted quand on fait défiler une page de résultats :
   - **chemin rapide** : requête HTTP (OkHttp) portant ces cookies et l'User-Agent de la
     WebView du téléphone ;
   - **repli** : si Vinted refuse, la requête est rejouée *dans* la WebView, depuis la page
     Vinted elle-même (`fetch` same-origin), ce qui est une requête de navigateur authentique.
4. Les annonces sont comparées à ce qui est déjà connu pour cette veille.

**Ce que RadarDeal ne fait pas**, par conception : aucun contournement de CAPTCHA ou de
protection anti-bot, aucune rotation de proxy, aucun vol de cookies, aucun achat, aucune offre,
aucun message automatique, aucune réservation. Quand Vinted demande une vérification, l'app
l'affiche et demande à l'utilisateur de la faire lui-même :

> **Vérification Vinted nécessaire.**
> Ouvrez Vinted, terminez la vérification puis relancez la surveillance.

Un scan qui échoue est toujours signalé avec sa cause exacte — connexion nécessaire,
vérification nécessaire, limitation de débit, erreur réseau, réponse illisible — jamais par une
erreur générique. Le planificateur ralentit automatiquement une veille qui reçoit un 429.

### Détection des nouveautés

Le **premier scan d'une veille sert de référence** : ses résultats sont enregistrés mais aucun
n'est marqué « NOUVEAU », sinon créer une veille sur une recherche à 52 résultats déclencherait
52 notifications. À partir du scan suivant, seules les annonces réellement inédites sont
signalées. Une baisse de prix n'est annoncée qu'une fois, au scan qui la constate.

### Détection des bonnes affaires

RadarDeal **ne prétend jamais connaître le prix du marché**. Il compare le prix d'une annonce à
la **médiane des annonces qu'il a lui-même collectées pour cette veille**, et reste muet tant
qu'il n'a pas au moins 8 annonces avec un prix. Les libellés le disent explicitement :
« 32 % sous la médiane des annonces détectées ».

---

## 4. Limites Android (à connaître)

Ces limites viennent du système, pas de l'application.

- **Une fréquence de quelques secondes exige un service au premier plan.** Aucun planificateur
  Android n'exécute une tâche de fond plus souvent que toutes les 15 minutes. RadarDeal utilise
  donc un vrai foreground service, avec la notification permanente « RadarDeal actif » et son
  action « Arrêter ».
- **Le mode Ultra (≈ 3 s) n'est tenu que RadarDeal actif au premier plan.** En arrière-plan ou
  écran éteint, Android peut réduire la fréquence, et aucune application ne peut l'en empêcher.
  L'application l'affiche explicitement plutôt que de promettre l'inverse.
- **RadarDeal ne peut pas détecter une annonce avant que Vinted ne la publie.** Le seul délai
  que l'application maîtrise — et qu'elle mesure — est « réponse reçue → notification envoyée ».
- **Android 15+ limite un service de type `dataSync`** à environ 6 heures d'exécution par
  tranche de 24 heures. Les surcouches constructeur (Xiaomi, Samsung, Huawei…) peuvent
  l'interrompre plus tôt.
- Pour cette raison, un worker WorkManager s'exécute en parallèle toutes les 15 minutes : il
  effectue un scan de rattrapage et **relance le service** dès qu'Android l'autorise. La
  surveillance reprend donc seule, à fréquence réduite, au lieu de s'arrêter silencieusement.
- Recommander à l'utilisateur d'autoriser l'application à s'exécuter **sans restriction de
  batterie** est la seule façon d'obtenir une surveillance vraiment continue.
- Sur Android 13+, les notifications exigent une permission accordée par l'utilisateur ; elle
  est demandée une fois, au premier affichage du radar.

---

## 5. Vie privée

Aucune donnée ne quitte le téléphone. Il n'y a pas de serveur RadarDeal à qui les envoyer.

- Veilles, annonces, favoris, historique des prix : base Room locale.
- Préférences : DataStore local.
- Session Vinted : cookies du WebView Android, uniquement transmis à Vinted.
- Sauvegarde cloud et transfert d'appareil **désactivés** (`data_extraction_rules.xml`), pour
  que la session ne soit jamais recopiée hors de l'appareil.
- Aucun tracker, aucune régie publicitaire, aucun analytics.

Les logs détaillés ne sont compilés que dans les builds de debug et ne contiennent que des
compteurs et des codes de statut — jamais un cookie, un jeton ou une donnée personnelle.

---

## 6. Structure du projet

```
app/src/main/java/com/radardeal/app/
├── RadarDealApp.kt          Application — volontairement vide de traitement
├── AppGraph.kt              Service locator, toutes les dépendances en lazy
├── MainActivity.kt          Unique Activity, hôte de Compose
├── core/                    Log et formatage
├── domain/model/            Watch, Listing, ScanStatus, DealRating…
├── data/
│   ├── local/               Room : entités, DAO, base
│   ├── prefs/               DataStore
│   └── repository/          Accès aux données
├── monitoring/              Le moteur : requêtes, parsing, diff, médiane, ordonnancement
├── web/                     Lecture de Vinted (HTTP + repli WebView), session
├── notifications/           Canaux et alertes Android
├── service/                 Foreground service, watchdog, receivers
└── ui/                      theme/, components/, navigation/ et un dossier par écran
```

Voir [ARCHITECTURE.md](ARCHITECTURE.md) pour les décisions techniques et leurs raisons.

Le portage iOS vit dans [`ios/`](ios/) et possède son propre
[README](ios/README.md) — à lire avant d'y toucher : ce que la plateforme autorise et ce qui a
réellement été vérifié y diffèrent nettement d'Android.

---

## 7. Tests

```bash
./gradlew test
```

126 tests unitaires couvrent le cœur du produit :

| Suite | Ce qu'elle protège |
|---|---|
| `AppLaunchTest` | Le démarrage : Application, manifeste, `exported`, ressources, MainActivity jusqu'à RESUMED |
| `ScanDiffTest` | Le scan de référence, la détection des nouveautés, les baisses de prix, la non-perte de données |
| `VintedParserTest` | Toutes les formes de payload connues — et l'absence de crash sur les inconnues |
| `VintedQueryTest` | La validation des URL collées et la conservation des filtres avancés |
| `DealEngineTest` | La médiane et la retenue du détecteur de bonnes affaires |
| `RadarDatabaseTest` | Room : cycle de vie d'une veille, persistance, favoris, cascade de suppression |
| `FormattersTest` | L'absence de « null » dans l'interface |
| `KnownIdCacheTest` | L'index mémoire des ids, y compris la course entre le polling et le canal DOM |
| `EnginePerfTest` | Le coût du chemin critique sur 100 / 500 / 1000 annonces |
| `MigrationTest` | La migration v1 → v2 : aucune veille, aucun favori, aucun prix perdu |
| `UltraSlotTest` | Ultra reste limité à une seule veille, quoi qu'il arrive |

---

## 8. Marque

RadarDeal **n'est ni affilié ni approuvé par Vinted**. C'est un outil de veille personnel.
