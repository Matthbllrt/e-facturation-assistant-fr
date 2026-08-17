# Références et licences tierces

Ce projet ne contient aucun code copié depuis un projet tiers. Le protocole
Dyson (authentification MyDyson, déchiffrement des identifiants locaux, sujets
et messages MQTT, champs d'état) a été **étudié** dans les projets communautaires
ci-dessous, puis réimplémenté en Kotlin.

| Projet | Licence | Ce qui en a été appris |
|---|---|---|
| [libdyson-wg/libdyson-neon](https://github.com/libdyson-wg/libdyson-neon) | MIT — Copyright (c) 2021 Xiaonan Shen | Dialecte MQTT : sujets `command` / `status/current`, messages `REQUEST-CURRENT-STATE`, `STATE-SET`, `ENVIRONMENTAL-CURRENT-SENSOR-DATA`, noms et unités des champs, dérivation de l'identifiant depuis le mot de passe Wi-Fi |
| [libdyson-wg/ha-dyson](https://github.com/libdyson-wg/ha-dyson) | MIT — Copyright (c) 2021 Xiaonan Shen | Correspondance modèles / capacités, comportement hors ligne |
| [libdyson-wg/opendyson](https://github.com/libdyson-wg/opendyson) | MIT — Copyright (c) 2024 The libdyson Working Group | API de compte moderne (`/v3/manifest`, `mqttRootTopicLevel`), clé AES fixe des `localBrokerCredentials` |

Ces trois projets sont sous licence MIT, qui autorise cette réutilisation des
connaissances protocolaires. Merci à leurs auteurs et contributeurs.

Dyson est une marque déposée de Dyson Technology Limited. Ce projet n'est ni
affilié à Dyson, ni approuvé par Dyson.

## Dépendances

Toutes sous licence Apache 2.0, à l'exception d'Eclipse Paho (EPL 1.0 / EDL 1.0) :
AndroidX (Core, Lifecycle, Activity, Compose, Navigation, Glance, DataStore,
WorkManager), Kotlin et kotlinx (Coroutines, Serialization), OkHttp,
Eclipse Paho MQTT client.
