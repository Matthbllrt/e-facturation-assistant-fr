# Dyson Glass — installation

## 1. Installer l'APK

Compiler :

```bash
./gradlew assembleDebug
```

L'APK est ici :

```
app/build/outputs/apk/debug/app-debug.apk
```

L'installer sur le Galaxy S24 :

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sans câble : copiez l'APK sur le téléphone, ouvrez-le avec « Mes fichiers » et
autorisez l'installation depuis cette source.

## 2. Lancer Dyson Glass

Ouvrez l'application depuis le tiroir d'applications.

## 3. Connecter le Dyson

Le téléphone doit être sur le **même Wi-Fi** que le Dyson.

**CONNECTER MON DYSON**, puis au choix :

- **Compte MyDyson** — e-mail, pays, code reçu par e-mail, mot de passe. Le mot
  de passe n'est pas conservé.
- **Configuration manuelle** — « Rechercher sur le Wi-Fi », puis le **mot de
  passe Wi-Fi imprimé sur l'étiquette** de l'appareil. Aucun compte requis.

Si l'appareil n'est pas trouvé : onglet **Appareil** → **Tester la connexion**,
et saisissez son adresse IP au besoin.

## 4. Revenir à l'écran d'accueil

Bouton Accueil.

## 5. Appui long

Sur une zone vide de l'écran d'accueil.

## 6. Widgets

Touchez **Widgets**.

## 7. Dyson Glass

Cherchez **Dyson Glass**. Deux entrées :

- **Dyson Glass · Large** — se pose en 4×3
- **Dyson Glass · Compact** — se pose en 4×2

## 8. Ajouter le widget

Faites-le glisser sur l'écran d'accueil. L'écran de configuration s'ouvre
(thème, nom affiché), puis **AJOUTER LE WIDGET**.

## 9. Redimensionner pour passer Compact ↔ Large

Appui long sur le widget, puis tirez les poignées : la mise en page bascule
automatiquement entre compact et large selon la hauteur. Les deux entrées sont
le même widget, seule la taille de départ diffère.

---

## Build release

`assembleRelease` produit un APK **non signé** tant qu'aucune clé n'est fournie :

```
app/build/outputs/apk/release/app-release-unsigned.apk
```

Pour un `app-release.apk` installable :

```bash
keytool -genkey -v -keystore ~/dyson-glass.jks -keyalg RSA \
        -keysize 2048 -validity 10000 -alias dyson

export DYSON_KEYSTORE=~/dyson-glass.jks
export DYSON_KEYSTORE_PASSWORD='mot-de-passe'
export DYSON_KEY_ALIAS=dyson
export DYSON_KEY_PASSWORD='mot-de-passe'

./gradlew assembleRelease
```

Le `.jks` est ignoré par git et ne doit jamais être commité.

## Si le Dyson ne répond pas

Onglet **Appareil** → **Tester la connexion**.

| Message | Solution |
|---|---|
| Aucun Dyson configuré | Refaire la configuration |
| Identifiant local introuvable | Réglages → réinitialiser, puis reconfigurer |
| Appareil introuvable sur ce réseau | Saisir l'adresse IP du Dyson |
| L'appareil a refusé l'identifiant | Reconfigurer avec le mot de passe Wi-Fi de l'étiquette |
| Connexion établie | Tout va bien |

Le téléphone ne doit pas être sur un réseau **invité** : il doit partager le
sous-réseau du Dyson.
