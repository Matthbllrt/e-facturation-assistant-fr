# Performance du moteur de détection

Ce document contient **des mesures réelles**, produites par `./gradlew test` et par une
exécution sur émulateur. Les chiffres sont reproductibles : `EnginePerfTest` les imprime à
chaque exécution sous le préfixe `[RadarPerf]`.

Machine de mesure : conteneur Linux x86_64, 4 vCPU, 15 Go RAM, JDK 21.
Émulateur : Android 9 (API 28), x86_64, **émulation logicielle sans KVM** — voir §6, les
latences réseau y sont donc pessimistes.

---

## 1. Ce que l'audit a trouvé

Avant toute optimisation, le chemin critique « une annonce apparaît → l'utilisateur est
prévenu » était le suivant :

```
service loop (plancher 5 s, dort intervalle/3)
  └─ tick
      └─ scan
          ├─ fetch JSON                    ~réseau
          ├─ parse                          ~1 ms
          ├─ Room: getForWatch()            lecture COMPLÈTE de la table
          ├─ ScanDiff.diff(tout)            toutes les cartes, à chaque scan
          ├─ Room: persistScanResults()
          ├─ Room: trim()
          ├─ Room: getPricesForWatch()      2ᵉ lecture complète
          ├─ DealEngine.assess()            médiane
          └─ notification                   ← seulement ici
```

Six goulots identifiés, tous corrigés :

| # | Problème | Emplacement d'origine |
|---|---|---|
| 1 | Plancher de boucle **5 s**, sommeil = intervalle/3 | `RadarService:133,185` |
| 2 | Lecture Room complète **à chaque scan** | `ScanEngine:82` |
| 3 | 2ᵉ lecture Room + médiane **avant** de notifier | `ScanEngine:129` |
| 4 | `scanNow` **ne prenait pas le mutex** → scans concurrents | `RadarCoordinator:96` |
| 5 | Espacement artificiel **1,5 s** entre veilles | `RadarCoordinator:160` |
| 6 | Intervalle minimum 15 s | `Watch.kt:50` |

> **Précision importante sur l'architecture.** Le moteur n'a jamais rechargé de page ni parsé
> de DOM. Il interroge `https://<host>/api/v2/catalog/items?…` (JSON) via OkHttp, et la WebView
> n'est qu'un repli qui exécute un `fetch()` same-origin — jamais un `reload()`. Le cycle
> « recharger → attendre → parser 50 cartes » n'existait donc pas. Les gains ci-dessous
> viennent de la suppression du travail *autour* de la requête, pas du remplacement d'un
> rendu HTML.

---

## 2. Nouveau chemin critique

```
service loop (dort jusqu'à la prochaine échéance exacte, plancher 250 ms)
  └─ tick  ──── mutex unique, aucun scan concurrent possible
      └─ scan
          ├─ fetch JSON                     ~réseau
          ├─ parse                          ~0,85 ms
          ├─ KnownIdCache.filterUnknown()   ~0,17 ms  ← une table de hachage en mémoire
          │     └─ rien de nouveau ? le scan s'arrête ICI
          ├─ claim() des ids inconnus
          ├─ NOTIFICATION                   ← ici
          └─ (après coup, hors chemin critique)
              ├─ Room: lecture + diff complet
              ├─ persistance + trim
              ├─ baisses de prix
              └─ médiane / badge deal
```

---

## 3. Mesures internes (`EnginePerfTest`)

Meilleur temps sur 12 itérations après échauffement JIT.

| Opération | Mesuré |
|---|---|
| 10 000 recherches d'id connu sur 1000 ids | **0,402 ms** (≈ 40 ns par recherche) |
| Pré-filtre, 1000 cartes, **aucune nouvelle** | **0,166 ms** |
| Diff complet, 100 cartes déjà connues | 0,047 ms |
| Diff complet, 500 cartes déjà connues | 0,232 ms |
| Diff complet, 1000 cartes déjà connues | 0,468 ms |
| Diff complet, 1000 connues **+ 1 nouvelle** | 0,704 ms |
| Analyse JSON d'un catalogue de 96 annonces | 0,846 ms |
| Médiane + notation sur 1000 prix | 0,615 ms |

**Lecture de ces chiffres.** Le cas dominant en production — un scan qui ne rapporte rien de
nouveau — coûte désormais **0,166 ms de travail applicatif** au lieu d'une lecture complète de
la table `listings` suivie d'un diff sur toutes les cartes. Sur une veille à 400 annonces, le
scan « rien de neuf » passe d'une requête SQLite + ~400 allocations d'objets à une boucle de
recherches dans une table de hachage.

Le budget interne complet, entre la réception de la réponse HTTP et l'envoi de la
notification, est donc de l'ordre de **1 à 2 ms** (parse + pré-filtre + construction des
annonces nouvelles). L'objectif « détection en moins de 100 ms pour la partie interne » est
tenu avec deux ordres de grandeur de marge.

---

## 4. Fréquences : avant / après

| | Avant | Après |
|---|---|---|
| Le plus rapide | Turbo 15 s | **⚡ Ultra 3 s** (1 veille) |
| Rapide | 30 s | **🔥 5 s** |
| Standard | 60 s | **● 15 s** |
| Éco | 120 s | **🌙 30 s** |
| Plancher réel de la boucle | 5 000 ms | 250 ms |
| Sommeil entre passes | intervalle / 3 | jusqu'à l'échéance exacte |
| Espacement entre veilles | 1 500 ms fixes | supprimé, Ultra passe en premier |

**Effet cumulé sur la pire latence de détection**, hors temps de publication côté Vinted :

- Avant : intervalle 15 s + granularité de boucle 5 s + travail applicatif ≈ **jusqu'à 20 s**.
- Après, en Ultra : intervalle 3 s (jusqu'à 5 s si la veille est calme) + ~1–2 ms de travail
  applicatif ≈ **3 à 5 s**.

---

## 5. Polling adaptatif et back-off

| Situation | Comportement |
|---|---|
| Ultra, veille productive | 3 000 ms |
| Ultra, veille calme | dérive ×1,25 par scan, plafond **5 000 ms** |
| Non-Ultra, veille calme | dérive jusqu'à ×4 de l'intervalle choisi |
| Nouvelle annonce détectée | retour **immédiat** au plancher |
| Erreur réseau / parsing | 2,5 s → 5 s → 10 s → 30 s |
| HTTP 429 | 60 s, doublé à chaque récidive, plafond 30 min |
| Connexion Vinted nécessaire | nouvelle tentative dans 5 min |
| Vérification Vinted (CAPTCHA) | **arrêt complet** de la veille (`PAUSED_VERIFICATION`) |
| Resynchronisation complète | toutes les 90 s, sans bloquer l'incrémental |

---

## 6. Vérification sur émulateur

Émulateur Android 9 (API 28), x86_64, **sans accélération matérielle**. Le CPU émulé est
environ 20 à 50 fois plus lent qu'un téléphone réel et l'émulateur n'avait **aucun accès
réseau à Vinted** : les latences réseau relevées ici ne veulent donc rien dire, seul le
comportement compte.

### Migration depuis la 1.0.0 — vérifiée sur l'appareil

Une base v1 réelle (schéma exporté de la 1.0.0, avec deux veilles, un favori et deux relevés de
prix) a été déposée dans le stockage de l'application, puis l'application 1.1.0 l'a ouverte :

```
user_version : 2
isUltra col  : isUltra
watches      : 1 | Nike Air Max 95   | interval=30 | isUltra=0
               2 | Levis 501 vintage | interval=60 | isUltra=0
favourite    : 4821337 price=39.0 prev=70.0 fav=1
price points : 2
[Room] database opened (v2)
crash lines  : 0
```

Les deux veilles s'affichent correctement dans l'écran Veilles après migration, avec leurs
critères, leurs intervalles et le compteur d'annonces. **Aucune donnée perdue.**

Une mise à jour APK en place (`adb install -r` de la 1.0.0 signée vers la 1.1.0 signée) a
également été effectuée : installation acceptée, application relancée, aucun crash.

### Mode Ultra — vérifié sur l'appareil

```
watches        : 1 | Nike Air Max 95   | isUltra=0
                 2 | Levis 501 vintage | isUltra=1
ultra holders  : 1
service        : isForeground=true foregroundId=1001
notification   : android.title=String (RadarDeal Ultra actif)
crash lines    : 0
```

- Activer Ultra sur une deuxième veille affiche bien le dialogue « Le mode Ultra ne peut être
  actif que sur une seule veille à la fois » avec l'option **Transférer**.
- Le sélecteur affiche « ⚡ Ultra · 2–3 sec » et l'avertissement
  « Vitesse maximale · consommation élevée ».
- L'instrumentation émet ses traces au format demandé :
  `[RadarPerf] source=POLL request_to_response=… response_to_parsed=… total=… cards=… new=…`

### Ce qui n'a pas pu être mesuré ici

La latence réelle « annonce publiée sur Vinted → notification », qui exige un compte Vinted
réel et un réseau réel. Sur l'émulateur, chaque requête est allée au bout de son timeout
(~45 s, soit le `callTimeout` OkHttp puis l'escalade WebView), ce qui a d'ailleurs permis de
vérifier que le back-off d'erreur fonctionne : les scans se sont espacés au lieu de marteler.

Le KPI interne — réponse reçue → notification envoyée — est instrumenté et lisible dans le
panneau *Ultra Radar Diagnostics* d'un build debug, sur un vrai téléphone.

## 7. Canal DOM en direct — ce qu'il vaut réellement

Le `MutationObserver` demandé est implémenté (`LiveDomObserverScript`, `LiveDomChannel`) et
communique via `WebViewCompat.addWebMessageListener`, avec des messages structurés et une
origine restreinte au domaine Vinted.

**Honnêtement : ce n'est pas lui qui fait la vitesse.** La page de résultats Vinted n'est pas
un flux temps réel — elle n'injecte pas de nouvelles annonces dans un onglet inactif. En
régime stationnaire, l'observateur reste donc silencieux et c'est le moteur de polling JSON
qui trouve les annonces. Le canal apporte deux choses réelles :

1. quand la page re-rend sa grille (défilement infini, changement de route du SPA), les cartes
   sont signalées dès leur entrée dans le DOM plutôt qu'au prochain scan ;
2. il donne à la veille Ultra un **second détecteur indépendant**, si bien qu'un scan manqué
   n'est pas une annonce manquée. Le compteur `Live DOM detections` du panneau de diagnostics
   permet de vérifier sa contribution réelle sur un vrai appareil.

Le rafraîchissement du canal est un `history.replaceState` + `popstate`, **pas** un
`reload()` : recharger détruirait le contexte JavaScript, l'observateur et sa référence.

---

## 8. Consommation

Le compteur `Scans / hour` du panneau de diagnostics donne la mesure directe.

| Mode | Scans/heure théoriques (veille active) | Scans/heure (veille calme, dérive adaptative) |
|---|---|---|
| ⚡ Ultra | 1 200 | ~720 |
| 🔥 Rapide | 720 | ~180 |
| ● Standard | 240 | ~60 |
| 🌙 Éco | 120 | ~30 |

Une requête de catalogue Vinted pèse quelques dizaines de kilo-octets. Ultra est explicitement
présenté dans l'interface comme « Vitesse maximale · consommation élevée », et reste limité à
une seule veille — c'est le principal garde-fou de consommation.

---

## 9. Limites honnêtes

- **Ultra à 3 s n'est tenu que RadarDeal actif au premier plan.** En arrière-plan ou écran
  éteint, Android peut réduire la fréquence, et aucune application ne peut l'en empêcher.
  L'interface le dit explicitement plutôt que de promettre l'inverse.
- **RadarDeal ne peut pas détecter une annonce avant que Vinted ne la rende visible.** Si la
  publication côté serveur prend dix secondes, la détection prend dix secondes. Le seul KPI
  que l'application maîtrise — et qu'elle mesure — est *réponse reçue → notification envoyée*.
- Les mesures de la §3 sont des micro-benchmarks JVM, pas des mesures ART sur téléphone. Les
  ordres de grandeur se transposent, les valeurs absolues non.
- Les chiffres de la §6 viennent d'un émulateur sans accélération matérielle ; ils démontrent
  le fonctionnement, pas la performance réelle sur un appareil récent.
