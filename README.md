# Mosaic

Coffre local privé pour **une** conversation Instagram, sous un alias local.
Application Android native, sans serveur, sans compte, sans télémétrie.

**Installation et prise en main : [`release/README.md`](release/README.md).**

## En deux lignes

Mosaic suit une seule personne, l'affiche sous un alias (« Louis » par défaut),
conserve localement une copie chiffrée de ses messages même s'ils disparaissent
ensuite d'Instagram, et se verrouille par biométrie ou par code.

## Les deux modes, et pourquoi il y en a deux

| | Mode A — API officielle | Mode B — notifications Android |
|---|---|---|
| Compte requis | Professionnel (Business/Creator) | N'importe lequel |
| Identifiants Instagram | Aucun (jeton collé par l'utilisateur) | Aucun |
| Historique antérieur | 20 derniers messages du fil | Aucun |
| Réponse depuis Mosaic | Send API, dans la fenêtre Meta de 24 h | Réponse rapide de la notification, si Instagram en propose une |
| Serveur | `graph.instagram.com` | Aucun |

Meta ne délivre pas de jeton de messagerie pour un compte Instagram personnel :
c'est la raison d'être du mode B. Les limites de chaque mode sont détaillées, avec
les sources, dans [`docs/INSTAGRAM.md`](docs/INSTAGRAM.md) — **y compris la
suppression automatique de la conversation Instagram, qui a été demandée, qui
n'existe pas dans l'API officielle, et qui n'est donc pas implémentée.**

Aucun mot de passe, cookie, scraping, API privée ni automatisation de clics
n'est utilisé nulle part.

## Confidentialité

- Base SQLCipher, passphrase de 32 octets scellée par le Keystore Android
  (StrongBox si disponible).
- Identité Instagram réelle, jeton et vérificateur de PIN dans un coffre
  AES-256-GCM séparé ; l'identité n'est révélée qu'après une authentification
  distincte de l'ouverture de l'application.
- `allowBackup=false`, sauvegarde cloud et transfert d'appareil exclus.
- `FLAG_SECURE` actif dès la première frame, libellé neutre dans les Récents.
- Aucune notification par défaut ; l'option activable est sans contenu.
- Aucun analytics, aucun crash reporter, aucun tracker. Aucun contenu, alias ou
  jeton en log — vérifié par un test qui parcourt les sources.

Modèle de menace complet et revue : [`docs/SECURITY.md`](docs/SECURITY.md).

## Stack

Kotlin · Jetpack Compose · Room + SQLCipher · Android Keystore · BiometricPrompt ·
WorkManager · DataStore · Retrofit/OkHttp (mode A uniquement) ·
minSdk 31 · targetSdk 36.

Le graphe de dépendances tient dans un seul fichier
([`MosaicGraph.kt`](app/src/main/java/app/mosaic/privatevault/MosaicGraph.kt)) :
pas de framework d'injection, et un seul endroit où vérifier ce qui a accès au
coffre.

## Construire

Nécessite un JDK 17+ et le SDK Android (`platforms;android-36`,
`build-tools;36.0.0`). Renseigner `sdk.dir` dans `local.properties`.

```bash
./gradlew :app:testDebugUnitTest      # 118 tests
./gradlew :app:lintRelease            # aucun problème
./gradlew :app:assembleRelease        # APK universel
./gradlew :app:bundleRelease          # AAB
```

La signature utilise par défaut la clé de développement versionnée. La remplacer
avant toute publication : [`docs/SIGNING.md`](docs/SIGNING.md).

## Tests

118 tests unitaires JVM, tous verts, plus une suite instrumentée qui vérifie le
chiffrement réel de la base sur appareil. Détail de la couverture et de ce qui
n'a **pas** pu être exécuté ici : [`docs/TESTING.md`](docs/TESTING.md).

## Documentation

- [`docs/INSTAGRAM.md`](docs/INSTAGRAM.md) — ce que l'API Meta permet, ce
  qu'elle ne permet pas, et les replis retenus
- [`docs/SECURITY.md`](docs/SECURITY.md) — modèle de menace, permissions, revue
- [`docs/TESTING.md`](docs/TESTING.md) — couverture des tests
- [`docs/SIGNING.md`](docs/SIGNING.md) — clés et publication
- [`release/README.md`](release/README.md) — installation, en français
