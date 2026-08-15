# Architecture

Ce document explique **pourquoi** RadarDeal est construit ainsi. La priorité annoncée du projet
est *stabilité > fonctionnalités > design > complexité*, et chaque décision ci-dessous s'y
rattache.

---

## 1. Vue d'ensemble

Une seule application, un seul module Gradle (`:app`), un seul processus, une seule `Activity`.

```
                    ┌──────────────────────────────────────────┐
   UI (Compose)     │  RadarScreen · Veilles · Favoris · …      │
                    └───────────────┬──────────────────────────┘
                                    │ StateFlow
                    ┌───────────────┴──────────────────────────┐
   ViewModels       │  RadarViewModel · WatchesViewModel · …    │
                    └───────────────┬──────────────────────────┘
                                    │
   Domaine /        ┌───────────────┴──────────────────────────┐
   monitoring       │  RadarCoordinator → ScanEngine           │
                    │      ScanDiff · DealEngine · VintedQuery │
                    │      VintedParser                        │
                    └──────┬───────────────────────┬───────────┘
                           │                       │
   Données          ┌──────┴────────┐      ┌───────┴──────────┐
                    │ Room (SQLite) │      │ VintedFetcher    │
                    │ DataStore     │      │  HTTP → WebView  │
                    └───────────────┘      └──────────────────┘
                           ▲
   Arrière-plan     ┌──────┴──────────────────────────────────┐
                    │ RadarService (foreground)               │
                    │ RadarWatchdogWorker (WorkManager)       │
                    └─────────────────────────────────────────┘
```

MVVM classique : la couche `monitoring` ne connaît ni Compose ni les ViewModels, et les pièces
qui portent les règles métier (`ScanDiff`, `DealEngine`, `VintedQuery`, `VintedParser`) sont des
objets **purs, sans Android**, ce qui les rend testables en JVM sans émulateur.

---

## 2. Le démarrage ne peut pas échouer

C'est la contrainte numéro un du projet : les versions précédentes plantaient au lancement.

- `RadarDealApp.onCreate()` ne fait **rien** d'autre qu'une ligne de log. Pas d'ouverture de
  base, pas de client réseau, pas de WebView, pas d'initialisation de bibliothèque.
- `AppGraph` est un **service locator écrit à la main** dont chaque membre est `by lazy`. Une
  dépendance ne se construit qu'au premier usage réel ; son échec devient un état d'erreur
  affiché dans l'écran concerné, jamais un crash de processus.
- **Pas de framework d'injection.** Hilt aurait ajouté de la génération de code, un
  `Application` annoté et des points de défaillance à l'initialisation, pour un graphe d'une
  quinzaine d'objets tous singletons. Le coût ne se justifiait pas.
- L'initialiseur automatique de WorkManager est **retiré du manifeste** ; l'application
  implémente `Configuration.Provider`, donc WorkManager ne s'initialise qu'à la première
  planification effective.
- `MainActivity` installe le splash screen, passe en edge-to-edge, et rend la main à Compose.
  Le thème de base hérite d'un thème AppCompat garanti présent sur toutes les versions
  supportées, et fixe un `windowBackground` opaque pour éviter le flash blanc.

`AppLaunchTest` verrouille tout cela : classe `Application` correcte, activité de lancement
`exported` et résolvable, ressources du manifeste réellement présentes et analysables,
`MainActivity` atteignant `RESUMED`, y compris avec un intent de notification malformé.

---

## 3. Lecture de Vinted

### Le choix de fond

Vinted n'expose pas d'API publique documentée pour ce cas d'usage. Trois approches étaient
possibles :

| Approche | Verdict |
|---|---|
| Un backend qui scrape pour tous les utilisateurs | Rejeté : coût mensuel, point de blocage unique, et l'app doit fonctionner à 0 € |
| Parser le HTML de la page de résultats | Fragile : le rendu est piloté par JavaScript et change souvent |
| **Utiliser la session normale de l'utilisateur** | Retenu |

La troisième est aussi la plus honnête : RadarDeal fait, pour le compte de l'utilisateur, la
requête que son propre navigateur ferait en faisant défiler la page de résultats.

### Deux chemins, dans cet ordre

`VintedFetcher` essaie d'abord le chemin le moins coûteux, puis escalade :

1. **`HttpVintedFetcher`** — OkHttp, hors du thread principal, portant les cookies lus dans le
   `CookieManager` d'Android et l'User-Agent de la WebView de l'appareil (pour que la requête
   soit cohérente avec les cookies qu'elle transporte). Rapide, léger, sans WebView en mémoire.
2. **`WebViewVintedFetcher`** — repli. Une WebView hors écran, créée paresseusement, charge la
   page Vinted puis exécute un `fetch()` **same-origin** via un pont JavaScript. La requête part
   alors réellement de la page Vinted, avec exactement la session établie par l'utilisateur.

L'escalade n'a lieu que si elle a un sens : un 429 ou un appareil hors ligne ne déclenchent pas
de seconde tentative, plus coûteuse et inutile.

Garde-fous du chemin WebView : verrou d'exclusion mutuelle, délai maximal global,
`try`/`catch` à chaque étape, navigation confinée au domaine Vinted, et un indicateur
`unavailable` définitif si l'appareil n'a pas de WebView utilisable — l'app cesse alors
simplement d'essayer au lieu de lever une exception à chaque scan.

### Ce qui n'est pas fait

Aucun contournement de CAPTCHA, de Cloudflare, de 403/429 ; aucune rotation de proxy ; aucun vol
de cookie ; aucun achat, offre, message ou réservation automatique. Une vérification Vinted est
**affichée à l'utilisateur** pour qu'il la traite lui-même.

### Analyse défensive

`VintedParser` a une règle absolue : **aucune entrée ne doit lever d'exception**. Vinted a déjà
livré le prix sous forme de nombre, de chaîne et d'objet ; la photo sous forme d'URL directe ou
de miniatures ; l'URL en absolu ou en relatif. Le parser lit toutes ces formes, cherche le
tableau d'annonces même imbriqué, sait récupérer le JSON embarqué dans une page HTML, et ne
conserve une annonce que si elle porte un identifiant. Une forme inconnue produit « 0 annonce
analysée », jamais un crash.

---

## 4. Le moteur de comparaison

`ScanDiff` est une **fonction pure** — pas de base, pas d'horloge, pas d'Android — ce qui permet
de tester au cas par cas les règles qui font le produit :

- **Le premier scan est une référence silencieuse.** Tant que `baselineDone` est faux, tout est
  enregistré et rien n'est marqué « NOUVEAU ». Sans cette règle, créer une veille sur une
  recherche à 52 résultats enverrait 52 notifications.
- **Une donnée manquante n'efface jamais une donnée connue.** Si un scan renvoie une annonce
  sans prix ni photo, les valeurs déjà stockées sont conservées.
- **L'état appartenant à l'utilisateur est intouchable** : `isFavorite` et `firstSeenAt`
  survivent à tous les rescans.
- **Une baisse de prix n'est annoncée qu'une fois**, au scan qui la constate. Le badge « PRIX ↓ »
  reste ensuite visible sur la carte, mais aucune nouvelle notification n'est émise.
- Une annonce qui disparaît des résultats est **conservée**, pas supprimée : elle a pu sortir de
  la première page.

`DealEngine` compare à la **médiane des annonces collectées pour la veille**, jamais à un
« prix du marché ». Il reste muet en dessous de 8 annonces avec prix, écarte les valeurs
aberrantes basses (les lots à 1 €), et ses seuils sont explicites : −35 % pour
« 🔥 EXCELLENT DEAL », −20 % pour « BON PRIX ».

---

## 5. Ordonnancement et arrière-plan

### `RadarCoordinator`

Décide *quand* chaque veille est scannée, et exécute les scans **un par un**, espacés de 1,5 s.
Lancer dix requêtes simultanées vers Vinted serait à la fois discourtois et le moyen le plus
rapide de se faire limiter.

Le back-off est automatique et proportionné :

| Situation | Réaction |
|---|---|
| 429 (`RATE_LIMITED`) | Intervalle doublé à chaque occurrence, jusqu'à ×16 (plafond 30 min) |
| Erreur réseau ou parsing | Doublé jusqu'à ×8 |
| Connexion / vérification nécessaire | Nouvelle tentative au plus tôt dans 5 minutes — seul l'utilisateur peut débloquer |
| Scan réussi | Retour immédiat à l'intervalle nominal |

### `RadarService`

Un vrai foreground service, parce que c'est le **seul** moyen supporté par Android de scanner
toutes les 15 à 60 secondes : les planificateurs système ont un plancher de 15 minutes. D'où la
notification permanente et son action « Arrêter ». Le service s'arrête de lui-même quand plus
aucune veille n'est active, plutôt que de garder une notification pour rien.

### `RadarWatchdogWorker`

Filet de sécurité, pas mécanisme principal. Android **finira** par arrêter un service au long
cours (quota `dataSync` d'Android 15+, gestionnaires de batterie constructeurs, pression
mémoire). Le worker s'exécute toutes les 15 minutes : il effectue un scan de rattrapage — ce qui
plafonne la fraîcheur des données dans le pire cas — et relance le service dès qu'Android
l'autorise. Il ne retourne jamais `Result.failure()`, ce qui annulerait définitivement la
chaîne périodique.

---

## 6. Données

Room avec trois tables :

- `watches` — les veilles.
- `listings` — clé primaire composite `(watchId, itemId)`. Une même annonce trouvée par deux
  veilles est stockée deux fois **volontairement** : ainsi l'état « nouveau », le favori et la
  médiane restent indépendants d'une veille à l'autre.
- `price_points` — l'historique des prix.

`listings` et `price_points` portent une clé étrangère `CASCADE` vers `watches` : supprimer une
veille nettoie tout ce qu'elle a produit. *(La clé sur `price_points` manquait dans la première
version et a été détectée par `RadarDatabaseTest` — sans elle, la base grossissait
indéfiniment.)*

Les données sont bornées : 400 annonces conservées par veille, les favoris étant toujours
épargnés.

`fallbackToDestructiveMigration` est activé : sur ce produit, une future évolution de schéma ne
doit jamais laisser un acheteur avec une application qui refuse de démarrer.

---

## 7. Interface

- **Compose + Material 3 comme socle technique, apparence entièrement personnalisée.** Le thème
  est sombre par choix, pas par « mode sombre » : il n'existe pas de variante claire à garder
  cohérente.
- La palette, l'échelle d'espacement et la typographie vivent dans `ui/theme/`. Contraste de
  graisses fort (nombres en `Black`, corps en `Normal`) : c'est ce qui fait lire la hiérarchie
  instantanément.
- Les composants réutilisables (`RdCard`, `StatTile`, `BadgePill`, `RdFilterChip`, `EmptyState`,
  `StatusBanner`, cartes d'annonce) sont dans `ui/components/`, ce qui garde les écrans courts.
- **Aucune police embarquée** : la police système suffit, et cela épargne plusieurs centaines de
  kilo-octets à un APK de 2,3 Mo.
- Navigation à quatre onglets, sans sous-onglets ni tiroir. L'écran d'accueil et la page Radar
  sont **un seul défilement vertical** : en-tête, compteurs, chips de filtre, flux.
- Chaque état d'erreur a son propre message et, quand c'est possible, son action (« Se connecter
  à Vinted »). Le texte de chaque état vit sur l'enum `ScanStatus`, si bien que le bandeau, la
  carte de veille et la notification disent exactement la même chose.

Les libellés d'interface sont écrits en français directement dans les composables. Le produit
est monolingue ; externaliser deux cents chaînes aurait ajouté une indirection sans bénéfice.
Les chaînes utilisées hors Compose (notifications, service, manifeste) sont, elles, dans
`strings.xml`.

---

## 8. Choix de versions

Les dépendances sont épinglées sur des versions **stables et éprouvées**, pas sur les plus
récentes. `./gradlew lint` signale des mises à jour disponibles ; c'est délibéré. De même,
`targetSdk` reste à 36 (Android 16) alors que le SDK 37 est installé : cibler un niveau d'API
dont les changements de comportement n'ont pas été testés serait un risque gratuit sur un
produit vendu.
