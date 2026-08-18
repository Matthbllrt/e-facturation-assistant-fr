# Installation

## 1. Compiler

Prérequis : JDK 17+ et le SDK Android (API 35). Depuis la racine du projet :

```bash
./gradlew assembleDebug
```

Pour une version optimisée (plus légère, ~3 Mo) :

```bash
./gradlew assembleRelease
```

`assembleRelease` produit un APK **non signé** tant qu'aucune clé n'est fournie.
Pour obtenir un `app-release.apk` installable, créez une clé une seule fois :

```bash
keytool -genkey -v -keystore ~/dyson-glass.jks -keyalg RSA \
        -keysize 2048 -validity 10000 -alias dyson

export DYSON_KEYSTORE=~/dyson-glass.jks
export DYSON_KEYSTORE_PASSWORD='votre-mot-de-passe'
export DYSON_KEY_ALIAS=dyson
export DYSON_KEY_PASSWORD='votre-mot-de-passe'

./gradlew assembleRelease
```

Le fichier `.jks` est ignoré par git et ne doit jamais être commité.

## 2. Où trouver l'APK

| Variante | Chemin |
|---|---|
| Debug (signé, prêt à installer) | `app/build/outputs/apk/debug/app-debug.apk` |
| Release signé | `app/build/outputs/apk/release/app-release.apk` |
| Release sans clé | `app/build/outputs/apk/release/app-release-unsigned.apk` |

## 3. Installer sur le Galaxy S24

Par USB, le débogage USB activé (Paramètres → À propos du téléphone →
Informations logiciel → appuyer 7 fois sur « Numéro de version », puis
Options de développement → Débogage USB) :

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sans câble : copiez l'APK sur le téléphone, ouvrez-le avec « Mes fichiers » et
autorisez l'installation depuis cette source lorsque Android le demande.

## 4. Connecter le Dyson

Le téléphone doit être sur le **même Wi-Fi** que le Dyson.

Ouvrez l'application, appuyez sur **CONNECTER MON DYSON**, puis au choix :

- **Compte MyDyson** — saisissez votre e-mail et votre pays, entrez le code reçu
  par e-mail et votre mot de passe MyDyson, puis choisissez l'appareil. Le mot de
  passe n'est pas conservé ; seul l'identifiant local de l'appareil est stocké,
  chiffré par le Keystore Android.
- **Configuration manuelle** — appuyez sur « Rechercher sur le Wi-Fi » pour
  détecter l'appareil, puis renseignez le **mot de passe Wi-Fi imprimé sur
  l'étiquette** du Dyson (sous l'appareil ou derrière le filtre). Aucun compte
  n'est nécessaire.

## 5. Ajouter le widget sur l'écran d'accueil Samsung

1. Appui long sur une zone vide de l'écran d'accueil.
2. **Widgets**.
3. Cherchez **Dyson Glass**, puis choisissez **Dyson · Grand** (4x4) ou
   **Dyson · Compact** (4x2).
4. Faites glisser le widget à l'emplacement voulu.
5. L'écran de configuration s'ouvre : thème, transparence, capteurs et commandes
   rapides. Appuyez sur **AJOUTER LE WIDGET**.

Depuis l'onglet **Widget** de l'application, le bouton « Ajouter le widget »
place directement le widget sur l'écran d'accueil.

## 6. Si le Dyson ne répond pas

Ouvrez l'onglet **Appareil** et appuyez sur **Tester la connexion**. Le message
indique précisément où ça bloque.

| Message | Cause | Solution |
|---|---|---|
| Aucun Dyson configuré | Onboarding non terminé | Refaites la configuration |
| Identifiant local introuvable | Le Keystore n'a pas pu conserver la clé | Réglages → réinitialiser, puis reconfigurez |
| Appareil introuvable sur ce réseau | Le Wi-Fi bloque la découverte automatique | Saisissez l'adresse IP du Dyson dans le champ **Adresse IP** |
| L'appareil a refusé l'identifiant | Mauvais identifiant ou mauvais type d'appareil | Reconfigurez, ou utilisez le mot de passe Wi-Fi de l'étiquette |
| Connexion établie | Tout va bien | — |

**Trouver l'adresse IP du Dyson** : dans l'interface de votre box/routeur, liste
des appareils connectés — le Dyson y apparaît sous un nom contenant son numéro
de série. Saisissez cette adresse dans l'onglet Appareil : elle est mémorisée et
la découverte automatique n'est plus nécessaire.

Vérifiez aussi que le téléphone n'est pas sur un réseau **invité** et que le
Wi-Fi est en 2,4 GHz si le Dyson ne gère pas le 5 GHz — les deux réseaux doivent
être le même sous-réseau.
