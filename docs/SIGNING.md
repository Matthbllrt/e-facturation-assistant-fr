# Signature

## Ce qui est utilisé aujourd'hui

Les binaires de `release/` sont signés avec une **clé de développement
jetable**, versionnée dans le dépôt :

- keystore : `keystore/mosaic-dev.jks` (PKCS#12, RSA 4096, valide 30 ans)
- mot de passe du magasin et de la clé : `mosaic-dev-only`
- alias : `mosaic-dev`
- CN : `Mosaic Development Key (DO NOT PUBLISH)`
- empreinte SHA-256 :
  `05:8B:2B:89:28:36:AA:8A:EA:ED:C7:B0:31:59:FA:FA:03:A3:91:E5:DA:1E:6F:DF:E5:18:A5:76:8B:85:C7:C9`

Elle est versionnée délibérément. Android refuse d'installer une mise à jour
signée par une autre clé ; sans clé stable et disponible, chaque nouvelle
version imposerait une désinstallation, donc la perte du coffre. Pour un usage
privé en installation manuelle, c'est le bon compromis.

Elle est **inutilisable pour une publication** : son mot de passe est public
puisqu'il est dans ce fichier.

L'APK est signé en **schéma v3** uniquement. Le schéma v1 (JAR) est inutile
au-dessus d'Android 9 et le v3 est celui qui autorise une rotation de clé
ultérieure. `minSdk` étant 31, tous les appareils visés le comprennent.

## Passer à une vraie clé avant publication

1. Générer la clé — le mot de passe ne doit apparaître nulle part dans le dépôt :

   ```bash
   keytool -genkeypair -v \
     -keystore ~/mosaic-upload.jks -storetype PKCS12 \
     -alias mosaic -keyalg RSA -keysize 4096 -validity 10950 \
     -dname "CN=..., O=..., C=FR"
   ```

2. Créer `signing-release.properties` à la racine du dépôt (déjà dans
   `.gitignore`, et prioritaire sur la clé de développement) :

   ```properties
   storeFile=/chemin/absolu/vers/mosaic-upload.jks
   storePassword=...
   keyAlias=mosaic
   keyPassword=...
   ```

3. Reconstruire :

   ```bash
   ./gradlew clean :app:assembleRelease :app:bundleRelease
   ```

4. Vérifier que le certificat est bien le nouveau :

   ```bash
   $ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
     app/build/outputs/apk/release/app-release.apk
   ```

5. Supprimer `keystore/` du dépôt et faire tourner l'historique si le dépôt
   devient public.

Attention : changer de clé change l'identité de l'application pour Android. Un
appareil qui a la version signée en développement devra désinstaller — donc
perdre son coffre — avant d'installer la version signée avec la nouvelle clé.
Exporter le coffre avant (Paramètres → Export local chiffré) si le contenu
compte.

## Fichier de correspondance R8

`release/mapping.txt.gz` permet de désobfusquer une trace d'exécution. Il est
propre à ce build : le conserver avec les binaires, il est inutilisable pour un
autre build.
