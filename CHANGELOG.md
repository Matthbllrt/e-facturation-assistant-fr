# Changelog

Toutes les modifications notables de RadarDeal sont consignées ici.
Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le projet applique le
[versionnage sémantique](https://semver.org/lang/fr/).

---

## iOS [1.0.0] — 2026-08-17

Premier portage iOS, dans [`ios/`](ios/). La version Android n'est pas modifiée.

### Ajouté

- `RadarDealCore`, paquet Swift sans interface : modèles, construction des URL de catalogue,
  parseur Vinted, diff de scan, médiane robuste, cache d'ids en mémoire, formateurs.
  **74 tests, tous verts** (`swift test`, Swift 6.0.3).
- Application SwiftUI : 10 écrans, même charte que la version Android, stockage SwiftData,
  session Vinted via `WKWebView` / `WKWebsiteDataStore`, notifications locales.
- Coordinateur reprenant les constantes du moteur Android : plancher 2,5 s, plafond Ultra 5 s,
  backoff 2,5 / 5 / 10 / 30 s, pause longue sur 429, `PAUSED_VERIFICATION` sur vérification
  Vinted, resync complète toutes les 90 s, Ultra limité à une veille.
- `BGAppRefreshTask` (`com.radardeal.app.refresh`) pour les réveils que le système veut bien
  accorder, spec XcodeGen (`ios/project.yml`) et icône 1024×1024.

### Corrigé

- Distinction booléen/nombre du parseur : `CFGetTypeID` est propre à Darwin, remplacé par un
  test sur `objCType`.
- Lecture de la chaîne de requête faite à la main : `URLComponents` décode différemment selon la
  Foundation utilisée, ce qui donnait `air%2520max` au lieu de `air max`.

### Connu

- La couche application (SwiftUI / SwiftData / WebKit) **n'a pas été compilée** : l'environnement
  de développement était Linux. Seul `RadarDealCore` est vérifié. Détail en
  [ios/README.md § 6](ios/README.md).
- La surveillance continue écran éteint est impossible sur iOS ; Ultra 3 s ne vaut que pendant
  que l'application est ouverte.

---

## [1.1.0] — 2026-08-16

Version consacrée à la **vitesse de détection**. L'architecture, le stockage et le design de la
1.0.0 sont conservés ; seul le moteur de surveillance a été retravaillé.

### Ajouté

- **⚡ Mode Ultra**, limité à une seule veille à la fois, avec transfert explicite si une autre
  veille le détient déjà. La contrainte est appliquée par une transaction en base, pas par
  l'interface.
- Nouveau palier de fréquences : **Ultra 3 s · Rapide 5 s · Standard 15 s · Éco 30 s**
  (auparavant 15 / 30 / 60 / 120 s).
- **Index mémoire des annonces connues** (`KnownIdCache`) : la question « ai-je déjà vu cette
  annonce ? » ne touche plus la base de données.
- **Canal de détection en direct** : WebView persistante, `MutationObserver` et
  `WebViewCompat.addWebMessageListener`, avec messages structurés et origine restreinte à
  Vinted. Sa contribution réelle est mesurable dans le panneau de diagnostics.
- **Polling adaptatif** : plancher tant que la veille produit, dérive progressive quand elle est
  calme, retour immédiat au plancher dès qu'une annonce apparaît.
- **Instrumentation de latence** (`RadarPerf`) et panneau **Ultra Radar Diagnostics**, compilé
  uniquement dans les builds debug.
- État `PAUSED_VERIFICATION` : une vérification Vinted **arrête** la veille au lieu de la
  ralentir. Aucun contournement, aucune requête supplémentaire.
- `PERFORMANCE.md`, avec les mesures avant/après.

### Modifié

- La notification part **avant** la persistance, le diff complet et le calcul de la médiane. Le
  badge « deal » arrive quelques millisecondes plus tard, sur la carte.
- La boucle du service dort jusqu'à l'échéance exacte de la prochaine veille au lieu de ticker
  toutes les 5 secondes minimum.
- Les baisses de prix sont traitées lors des resynchronisations, jamais avant une nouvelle
  annonce.
- Back-off d'erreur 2,5 / 5 / 10 / 30 s ; pause bien plus longue sur HTTP 429.
- Resynchronisation complète toutes les 90 secondes, sans bloquer la détection incrémentale.
- La notification du service affiche « RadarDeal Ultra actif — <veille> surveillé ».

### Corrigé

- **Scans concurrents possibles** : « Scanner maintenant » ne prenait aucun verrou et pouvait
  s'exécuter en même temps qu'un scan planifié. Tous les points d'entrée passent désormais par
  un mutex unique.
- Suppression de l'espacement artificiel de 1,5 s entre veilles ; Ultra est planifié en premier.
- Suite de tests : une animation Compose infinie empêchait la boucle principale de Robolectric
  de devenir inactive, faisant passer `AppLaunchTest` de 33 secondes à plus de 30 minutes.

### Base de données

- Schéma **v2** : ajout de la colonne `isUltra`, par une vraie migration `ALTER TABLE`.
- `fallbackToDestructiveMigration` **retiré**. Une mise à jour depuis la 1.0.0 conserve les
  veilles, les favoris, l'historique et les prix — vérifié par `MigrationTest`, qui construit
  une vraie base v1 et contrôle les données après migration.

### Qualité

- 126 tests unitaires (contre 96), tous verts sur les variantes debug et release.

---

## [1.0.0] — 2026-08-15

Première version publiable. Le projet a été **entièrement reconstruit depuis zéro** avec une
chaîne Android standard (Android Gradle Plugin, Kotlin, KSP, Android SDK officiel) après les
tentatives précédentes d'assembler un APK à la main, qui produisaient « Application non
installée » puis des plantages systématiques au démarrage.

### Ajouté

**Veilles**
- Création d'une veille par critères (mot clé, marque, prix maximum) ou en collant une **URL de
  recherche Vinted**, ce qui conserve tous les filtres avancés (catégorie, taille, état,
  couleur, prix).
- Validation de l'URL au fil de la frappe, avec un message précis pour chaque cas d'erreur
  (domaine étranger à Vinted, adresse d'annonce au lieu d'une recherche, URL invalide).
- Quatre fréquences : Turbo 15 s, Rapide 30 s, Standard 1 min, Éco 2 min.
- Actions par veille : mettre en pause, scanner maintenant, modifier, supprimer.

**Détection**
- Le premier scan d'une veille sert de **référence** : ses résultats sont enregistrés sans être
  signalés comme nouveaux.
- Détection des nouvelles annonces par comparaison d'identifiants.
- Détection des baisses de prix, avec conservation du prix précédent et de l'historique complet.
- Moteur local de bonnes affaires fondé sur la **médiane des annonces détectées pour la veille**,
  silencieux en dessous de 8 annonces avec prix. Badges « 🔥 EXCELLENT DEAL » (−35 %) et
  « BON PRIX » (−20 %).

**Interface**
- Thème sombre premium sur mesure (fond `#070B10`, accent turquoise `#00C7A5`), cartes à grands
  visuels, typographie à fort contraste de graisses, animations discrètes.
- Onboarding de trois écrans, affiché au premier lancement uniquement.
- Navigation à quatre onglets : Radar, Veilles, Favoris, Réglages.
- Écran Radar : salutation, état du radar, trois compteurs, chips de filtre (Tout, Nouveau,
  Deals, Prix ↓, Favoris) et flux d'annonces.
- Fiche d'annonce détaillée avec l'historique des prix relevés.
- Favoris persistants, épargnés par l'effacement de l'historique.
- Écran Réglages : notifications, son, vibration, fréquence par défaut, cible d'ouverture des
  annonces, session Vinted, effacement de l'historique, mentions légales.
- Icône adaptative dédiée (radar turquoise sur fond noir), avec variante monochrome pour les
  icônes thématiques d'Android 13+.

**Surveillance en arrière-plan**
- Foreground service avec notification permanente « RadarDeal actif », son sous-titre indiquant
  le nombre de recherches surveillées, et une action « Arrêter ».
- Worker WorkManager de secours toutes les 15 minutes : scan de rattrapage et redémarrage du
  service quand Android l'a interrompu.
- Reprise de la surveillance après un redémarrage du téléphone ou une mise à jour de l'app,
  uniquement si l'utilisateur l'avait laissée active.
- Ralentissement automatique et progressif d'une veille limitée par Vinted (429), avec retour
  immédiat à la fréquence nominale dès qu'un scan réussit.

**Notifications**
- Alertes séparées pour les nouvelles annonces et les baisses de prix, regroupées par veille.
- Un appui sur l'alerte ouvre directement la fiche de l'annonce concernée.
- Le son et la vibration sont respectés sur toutes les versions supportées, grâce à des canaux
  de notification distincts selon les préférences.

**Session Vinted**
- Écran de connexion utilisant la **vraie page Vinted** dans une WebView. L'application ne voit
  jamais le mot de passe ; les cookies restent dans le magasin local d'Android.
- Lecture du catalogue par requête HTTP portant cette session, avec repli sur une requête
  `fetch` same-origin exécutée dans la WebView quand Vinted refuse la première.
- Déconnexion locale possible depuis les Réglages.

**Robustesse**
- États explicites pour chaque situation : connexion Vinted nécessaire, vérification Vinted
  nécessaire, limitation de débit, erreur réseau, réponse illisible, aucun résultat.
- Analyse des réponses tolérante à toutes les formes connues du payload Vinted et incapable de
  lever une exception sur une forme inconnue.
- Aucune donnée connue n'est effacée par un scan incomplet.
- Logs détaillés compilés uniquement dans les builds de debug, sans donnée personnelle.

**Confidentialité**
- Toutes les données restent sur l'appareil ; aucun serveur, aucun compte, aucun tracker.
- Sauvegarde cloud et transfert d'appareil désactivés pour ne jamais recopier la session hors
  du téléphone.

**Qualité**
- 96 tests unitaires (JVM et Robolectric) couvrant le démarrage de l'application, le moteur de
  comparaison, l'analyse des réponses Vinted, la construction des requêtes, le détecteur de
  bonnes affaires, la base Room et le formatage.
- APK release minifié par R8 (2,3 Mo) et signé avec les schémas de signature v2 et v3.

### Notes techniques

- `minSdk` 26 (Android 8.0), `targetSdk` et `compileSdk` 36 (Android 16).
- Kotlin 2.2.20, AGP 8.13.2, Jetpack Compose (BOM 2026.06.01), Room 2.8.4, WorkManager 2.11.2.
- Aucune dépendance à un service payant, un backend, une clé d'API ou un abonnement.
