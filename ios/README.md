# RadarDeal — iOS

Portage iOS de [RadarDeal Android](../README.md) : mêmes veilles, même moteur de détection,
même charte graphique, mêmes limites (aucun contournement d'une protection Vinted, aucune
donnée qui quitte l'appareil).

| | |
|---|---|
| Bundle id | `com.radardeal.app` |
| Version | 1.0.0 (build 1) |
| Cible minimale | iOS 17.0 |
| Langage | Swift 5.9 / SwiftUI / SwiftData |
| Dépendances externes | **aucune** |

> **À lire avant tout : [§ 6 — Ce qui a été vérifié](#6-ce-qui-a-été-vérifié-et-ce-qui-ne-la-pas-été)
> et [§ 7 — Vendre une app iOS](#7-vendre-une-app-ios-la-réalité).** Deux points changent la
> donne par rapport à Android : la couche interface n'a **jamais été compilée** (il n'y a pas de
> Mac dans l'environnement où ce code a été écrit), et un fichier iOS installable ne peut pas
> être vendu comme l'APK l'est sur Etsy.

---

## 1. Structure

```
ios/
├── project.yml                 spec XcodeGen — le .xcodeproj est généré, pas versionné
├── RadarDealCore/              paquet Swift : le moteur, sans une ligne d'UI
│   ├── Sources/RadarDealCore/
│   │   ├── Models.swift        Watch, ScanFrequency, ScanStatus, Listing, RawListing…
│   │   ├── VintedQuery.swift   URL de recherche → paramètres du catalogue
│   │   ├── VintedParser.swift  réponse Vinted → RawListing (ne jette jamais)
│   │   ├── ScanDiff.swift      diff nouveautés / baisses de prix
│   │   ├── DealEngine.swift    médiane robuste et notation "bonne affaire"
│   │   ├── KnownIDCache.swift  ids connus en mémoire, hors base
│   │   └── Formatters.swift    prix, durées, temps relatif
│   └── Tests/                  74 tests
└── App/RadarDeal/              l'application
    ├── RadarDealApp.swift      @main + AppGraph (tout en lazy)
    ├── Design/                 Theme.swift, Components.swift
    ├── Screens/                10 écrans SwiftUI
    ├── Services/               Store (SwiftData), VintedSession, RadarEngine,
    │                           RadarCoordinator, Notifier, SettingsStore
    └── Resources/Assets.xcassets
```

Le moteur est isolé dans un paquet Swift séparé pour une raison précise : c'est la partie qui
peut être compilée et testée **sans Xcode et sans Mac**, donc la partie qu'on peut réellement
garantir. Tout ce qui dépend de SwiftUI, SwiftData ou WebKit vit dans `App/`.

---

## 2. Compiler

Prérequis : **macOS 14+**, **Xcode 15.4 ou plus**, et un identifiant Apple Developer (gratuit
pour installer sur son propre iPhone, à 99 €/an pour publier).

```bash
brew install xcodegen

cd ios
xcodegen generate          # écrit RadarDeal.xcodeproj à partir de project.yml
open RadarDeal.xcodeproj
```

Dans Xcode : onglet **Signing & Capabilities** → cocher *Automatically manage signing* →
choisir votre équipe. Puis ⌘R sur un simulateur ou sur un iPhone branché.

`project.yml` ne contient volontairement **aucun `DEVELOPMENT_TEAM`** : un identifiant d'équipe
Apple est un secret de compte, il n'a rien à faire dans un dépôt Git. C'est la même règle que
pour le keystore Android, qui reste hors dépôt.

### Sans XcodeGen

`xcodegen` n'est pas obligatoire : un nouveau projet *App* dans Xcode, cible iOS 17, bundle id
`com.radardeal.app`, puis glisser `App/RadarDeal` dans le projet et ajouter `RadarDealCore`
via *File → Add Package Dependencies → Add Local*. `project.yml` sert alors de référence pour
les réglages (Info.plist, modes de fond, identifiant de tâche BGTaskScheduler).

## 3. Tester le moteur

Le paquet `RadarDealCore` est testable partout où Swift tourne — macOS **et Linux** :

```bash
cd ios/RadarDealCore
swift test
```

---

## 4. Ce qu'iOS autorise réellement

C'est le point le plus important de ce portage, et il n'est pas négociable : **iOS n'a pas
d'équivalent du service de premier plan Android.** Sur Android, RadarDeal fait tourner un
`ForegroundService` avec une notification permanente, et scanne toutes les 3 secondes
indéfiniment. Rien de tel n'existe sur iOS.

| Situation | Android | iOS |
|---|---|---|
| App ouverte à l'écran | 3 s (Ultra) | **3 s — identique** |
| App en arrière-plan | 3 s, en continu | quelques minutes de sursis, puis suspension |
| App suspendue | 3 s, en continu | `BGAppRefreshTask` — **au bon vouloir du système** |
| Téléphone verrouillé la nuit | continue | quasiment rien |

`BGAppRefreshTask` n'est pas un minuteur. On demande `earliestBeginDate`, iOS décide. Dans les
faits le système apprend vos habitudes d'usage et accorde des réveils dans cet ordre de grandeur
(**observation générale de la plateforme, pas une mesure faite sur ce projet**) :

- app utilisée plusieurs fois par jour, iPhone chargé et sur Wi-Fi : plusieurs réveils par heure ;
- app rarement ouverte, ou mode économie d'énergie actif : quelques réveils par jour, parfois aucun ;
- *Actualisation en arrière-plan* désactivée dans Réglages : **zéro**.

**Conséquence à écrire noir sur blanc dans la fiche produit :** sur iOS, la fréquence Ultra est
une fréquence *pendant que l'app est ouverte*. Une bonne affaire publiée à 3 h du matin ne sera
pas notifiée à 3 h 00 min 03 s. L'application le dit elle-même à l'utilisateur au premier
lancement plutôt que de le laisser le découvrir.

Ce qui a été fait pour en tirer le maximum, sans mentir sur le résultat :

- le coordinateur boucle à la même cadence qu'Android quand l'app est active (plancher 2,5 s,
  plafond Ultra 5 s, croissance ×1,25 en l'absence de nouveautés) ;
- `BGAppRefreshTask` (`com.radardeal.app.refresh`) est reprogrammée à chaque exécution ; un
  réveil accordé sert à un scan complet des veilles actives, notifications comprises ;
- les notifications sont locales (`UNUserNotificationCenter`), donc sans serveur de push, donc
  **0 €/mois** comme sur Android. Il n'existe pas de push gratuit sans backend.

## 5. Ce que le portage ne fait pas

Les mêmes interdits que sur Android, pour les mêmes raisons :

- aucun contournement de CAPTCHA, de Cloudflare, d'un 403 ou d'un 429 ;
- aucune rotation de proxy, aucun vol de cookie ;
- aucun achat, aucune offre, aucun message, aucune réservation automatique.

Quand Vinted demande une vérification, l'app s'arrête sur `PAUSED_VERIFICATION` et affiche :
*« Une vérification Vinted est nécessaire. Ouvrez Vinted, terminez la vérification puis relancez
la surveillance. »* Sur 429, la pause est longue et croissante.

La session Vinted est celle de l'utilisateur : il se connecte dans un `WKWebView`, et les
cookies sont lus depuis `WKWebsiteDataStore` — le même magasin que Safari View, jamais recopié
ailleurs. Aucune donnée ne sort de l'iPhone : pas d'analytics, pas de tracker, pas de compte
RadarDeal.

---

## 6. Ce qui a été vérifié, et ce qui ne l'a pas été

Ce dépôt a été écrit dans un conteneur Linux. Il n'y a **ni macOS, ni Xcode, ni simulateur
iOS** dans cet environnement, et cela limite ce qui peut être affirmé.

### Vérifié

| Quoi | Comment | Résultat |
|---|---|---|
| `RadarDealCore` compile | `swift build`, Swift 6.0.3 sur Linux | ✅ sans avertissement |
| Le moteur est correct | `swift test` — 74 tests | ✅ `Executed 74 tests, with 0 failures` |
| Les 19 fichiers de `App/` sont syntaxiquement valides | `swiftc -parse` sur chaque fichier | ✅ aucune erreur |
| L'icône est un PNG 1024×1024 valide | rendue et relue | ✅ |

Les tests portent sur ce qui casse en vrai : parsing d'une réponse Vinted tronquée, malformée,
avec un prix en chaîne / en nombre / en objet, sans photo, sans URL ; diff nouveautés vs baisses
de prix ; médiane robuste et seuil de 8 comparables ; construction des URL de catalogue depuis
une URL de recherche collée. Les mêmes cas que la suite Android, portés un par un.

Deux vrais bugs multiplateformes ont été trouvés **parce que** ce code a été compilé plutôt que
livré à l'aveugle :

1. `CFGetTypeID` est propre à Darwin : la distinction booléen/nombre du parseur ne compilait pas
   ailleurs. Remplacée par `objCType == "c"`, qui vaut sur les deux Foundation.
2. `URLComponents.queryItems` décode les valeurs sur Darwin mais pas dans
   swift-corelibs-foundation, et `percentEncodedQueryItems` les double-encode sur Linux
   (`air%20max` → `air%2520max`). La chaîne de requête est donc découpée à la main, ce qui donne
   un comportement unique — et le même que l'implémentation Android.

### Non vérifié

**La couche application n'a jamais été compilée.** SwiftUI, SwiftData, WebKit, UserNotifications
et BackgroundTasks n'existent pas sur Linux ; `swiftc -parse` valide la syntaxe et **rien
d'autre** : ni les types, ni les noms d'API, ni les règles de concurrence stricte. Il faut donc
s'attendre à devoir corriger des erreurs de compilation au premier ⌘B sur un Mac.

Ne sont vérifiés ni par un test ni par une exécution :

- l'affichage réel des 10 écrans, l'icône dans le springboard, l'écran de lancement ;
- le schéma SwiftData et sa migration (l'équivalent iOS de la migration Room v1→v2 testée
  côté Android n'a **pas** été exercé — ce portage est une v1.0.0, il n'a pas encore d'ancien
  schéma à migrer, mais la mécanique reste à éprouver le jour où il en aura un) ;
- la connexion Vinted dans `WKWebView` et la lecture des cookies ;
- le comportement réel de `BGAppRefreshTask` sur un appareil ;
- les notifications locales et les autorisations associées ;
- **le parsing d'une réponse Vinted réelle** — exactement comme sur Android, les tests utilisent
  des payloads d'exemple. Si Vinted change la forme de sa réponse, le parseur renvoie
  « 0 annonce analysée » au lieu de planter, mais il faudra le mettre à jour.

## 7. Vendre une app iOS : la réalité

Le modèle Etsy de la version Android — vendre un fichier `.apk` téléchargeable, paiement unique,
0 €/mois — **ne se transpose pas**. Trois faits, indépendants de la qualité du code :

1. **Aucun fichier iOS installable ne se vend.** Il n'y a pas d'équivalent de l'APK. Un
   utilisateur ne peut pas « installer un fichier » sur son iPhone. Les seules voies sont :
   - **App Store** — 99 €/an, revue Apple, commission sur les ventes ;
   - **TestFlight** — gratuit, mais chaque build expire au bout de 90 jours et chaque testeur
     doit être invité un par un ; ce n'est pas un canal de vente ;
   - **Ad Hoc** — 100 appareils maximum, et vous devez collecter l'UDID de chaque acheteur,
     resigner et renvoyer un build à chacun ;
   - **distribution alternative dans l'UE** (DMA) — soumise à des frais et à des conditions qui
     n'ont d'intérêt qu'à volume élevé.
2. **La revue App Store est un risque sérieux, pas une formalité.** Une app tierce qui interroge
   Vinted avec la session de l'utilisateur touche à deux règles régulièrement invoquées : la
   **4.2** (fonctionnalité minimale / app qui n'est qu'une surcouche d'un site) et la **5.2.2**
   (utilisation du contenu d'un tiers sans autorisation). Un refus est un scénario plausible.
3. **Ultra 3 s n'existe pas en arrière-plan** — voir § 4. Vendre iOS avec la promesse Android
   serait vendre autre chose que ce que le produit fait.

Ces trois points sont des contraintes de plateforme. Le projet iOS est complet et prêt à être
ouvert dans Xcode ; ce qu'il ne peut pas faire, c'est être vendu comme un fichier sur Etsy.

---

## 8. Parité avec Android

| | Android 1.1.0 | iOS 1.0.0 |
|---|---|---|
| Veilles URL et critères | ✅ | ✅ |
| Ultra / Rapide / Standard / Éco | ✅ | ✅ (app ouverte) |
| Ultra limité à une veille | ✅ | ✅ |
| Cache d'ids en mémoire | ✅ | ✅ |
| Notification avant persistance | ✅ | ✅ |
| Backoff 2,5 / 5 / 10 / 30 s | ✅ | ✅ |
| Pause longue sur 429 | ✅ | ✅ |
| `PAUSED_VERIFICATION` sur CAPTCHA | ✅ | ✅ |
| Resync complète toutes les 90 s | ✅ | ✅ |
| Notation bonne affaire (médiane) | ✅ | ✅ |
| Favoris, historique de prix | ✅ | ✅ |
| Surveillance en continu, écran éteint | ✅ | ❌ *impossible sur iOS* |
| Observateur DOM en direct | ✅ | ❌ *non porté* |
| Base | Room (SQLite) | SwiftData |
