# Dyson Glass Controller

Contrôle d'un purificateur Dyson Wi-Fi directement depuis un widget Android,
sans ouvrir d'application et sans serveur tiers.

Application Android native — Kotlin, Jetpack Compose, Glance.

## Ce que ça fait

- Widgets 4x2 et 4x4 avec rendu du Dyson, capteurs et commandes directes
- Contrôle **local** en MQTT sur le Wi-Fi, aucune dépendance au cloud après la configuration
- Marche/arrêt, vitesse 1-10, auto, oscillation, mode nuit, chauffage, consigne,
  direction du flux — chaque commande n'apparaît que si le modèle la prend en charge
- Température, humidité, PM2.5, PM10, COV, NO₂, qualité de l'air, état des filtres
- Écran de contrôle complet avec rendu animé de l'appareil

## Architecture

```
ui/          Compose (onboarding, contrôle, appareil, widget, réglages) + artwork partagé
widget/      Glance : widgets 4x2 / 4x4, actions, configuration
work/        WorkManager : exécution des commandes du widget, rafraîchissement périodique
domain/      Modèles (DysonState, DysonCapabilities, DysonCommand) et DysonRepository
data/        MQTT local, API MyDyson, découverte mDNS, stockage (Keystore + DataStore)
```

L'interface ne connaît jamais un nom de champ MQTT : tout passe par `DysonRepository`,
`DysonState` et `DysonCapabilities`.

### Connexions courtes plutôt que permanentes

Un appui sur le widget ouvre une session MQTT locale, envoie la commande, attend
la confirmation de l'appareil, met le widget à jour, puis ferme la connexion.
Aucune connexion permanente n'est maintenue pour le widget. L'écran de contrôle
de l'application ouvre une session vivante, mais seulement tant qu'il est affiché.

Le widget se repeint immédiatement à partir d'un état optimiste, que la réponse
réelle de l'appareil vient confirmer ou corriger.

### Sécurité

- L'identifiant MQTT local est chiffré en AES-GCM par une clé du **Keystore Android**
- Le mot de passe MyDyson sert uniquement à la vérification et n'est jamais stocké
- Aucun secret dans les logs (`Redact`), aucun backup, aucun serveur tiers
- Permissions : `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`
  (plus `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` requises par WorkManager)

## Appareils pris en charge

Pure Cool Link (475, 469) · Purifier Cool (438, 520) · Pure Hot+Cool Link (455) ·
Purifier Hot+Cool (527) · Purifier Humidify+Cool (358) · Purifier Big+Quiet (664),
variantes régionales E/K/M incluses. Les robots aspirateurs ne sont pas gérés.

## Installation

Voir [INSTALL.md](INSTALL.md).

## Tests

```bash
./gradlew testDebugUnitTest
```

Couvre l'analyse des états MQTT, la détection des capacités, la génération des
commandes, le stockage et le rendu de l'artwork du widget.

## Crédits

Protocole étudié dans les projets MIT de la libdyson Working Group — voir
[THIRD_PARTY.md](THIRD_PARTY.md). Projet non affilié à Dyson.
