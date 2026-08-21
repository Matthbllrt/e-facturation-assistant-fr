# Tests

## Tests unitaires JVM — exécutés

```bash
./gradlew :app:testDebugUnitTest
```

**118 tests, 0 échec** au dernier build.

| Suite | Ce qu'elle couvre |
|---|---|
| `MessageDeduplicatorTest` | Notification re-postée, doublon webhook/polling, fusion notification → API, même texte plus tard, sens entrant/sortant, stabilité des clés de déduplication |
| `NotificationMatcherTest` | Paquet Instagram officiel uniquement, notification groupée, notification de progression, likes et follows, autre conversation, **homonyme avec clé différente**, correspondance par nom signalée, compteur de non-lus, accents, absence de cible |
| `ConversationRepositoryTest` | Écriture, déduplication de bout en bout, mise à niveau par l'API, échec d'écriture remonté, **messages déjà stockés jamais perdus**, horodatage de dernière activité, effacement |
| `SecureStoreTest` | Aller-retour chiffré, **absence de texte clair sur le disque**, non-déterminisme du chiffrement, génération unique de la passphrase, effacement, coffre scellé par une autre clé, fichier tronqué |
| `PinManagerTest` | PIN faibles refusés, essais restants, verrouillage exponentiel, verrouillage opposé même au bon PIN, persistance des échecs après redémarrage, sel PBKDF2, vérificateur illisible |
| `AppLockTest` | Verrouillé au démarrage, bouton d'urgence, verrouillage immédiat, temps passé en arrière-plan, « Jamais », inactivité au premier plan, réinitialisation par interaction |
| `CooldownPolicyTest` | Désactivé, sans activité, avant/à/après l'échéance, valeur personnalisée, délai jamais négatif, report par un nouveau message |
| `AliasTest` | Alias par défaut « Louis », repli, troncature, caractères invisibles, **détection de fuite de l'identité réelle**, graine d'avatar |
| `VaultExporterTest` | Contenu exporté, **identifiant Instagram absent de l'export**, fichier illisible sans la phrase, déchiffrement conforme au format documenté, phrases trop courtes refusées |
| `InstagramApiClientTest` | Aucun appel sans jeton, stockage et effacement du jeton, fenêtre de renouvellement, URL Graph documentée |
| `LoggingHygieneTest` | **Aucun `Log.*` hors de `SafeLog`**, aucune interpolation de valeur sensible, `redact` irréversible et stable |
| `MosaicDatabaseSchemaTest` | Migration présente pour chaque version, versions contiguës, schéma exporté, **migration destructive absente** |
| `StartupSmokeTest` | Démarrage à froid réel de l'`Application`, coffre verrouillé, **rien n'est déchiffré avant authentification**, bibliothèque native absente non fatale, valeurs par défaut de confidentialité |

## Tests instrumentés — écrits, non exécutés ici

```bash
./gradlew :app:connectedDebugAndroidTest
```

`EncryptedVaultTest` vérifie ce qui ne peut pas l'être sur la JVM : que le
fichier de base écrit sur le téléphone est réellement chiffré par SQLCipher (il
ne commence pas par `SQLite format 3` et ne contient pas le texte des messages),
que la passphrase est bien scellée par le Keystore matériel, qu'une passphrase
différente ne l'ouvre pas — sans rien effacer — et que la version de schéma
déclarée correspond.

**Ils n'ont pas été exécutés** : l'environnement de build utilisé n'expose pas
`/dev/kvm`, donc aucun émulateur Android ne peut démarrer, et aucun appareil
physique n'y est connecté. Ils compilent (`:app:compileDebugAndroidTestKotlin`
passe) et se lancent tels quels sur un téléphone branché en USB.

## Vérification des binaires release

Faite statiquement, faute d'appareil :

- **Signature** : `apksigner verify` — valide, schéma v3, un seul signataire,
  RSA 4096.
- **Manifeste** : `aapt2 dump badging` — `app.mosaic.privatevault`, versionCode
  1, `targetSdkVersion 36`, activité de lancement présente, aucune permission
  inattendue.
- **APK universel** : les quatre ABI sont présents (`arm64-v8a`, `armeabi-v7a`,
  `x86`, `x86_64`), y compris `libsqlcipher.so` pour chacun.
- **R8** : `missing_rules.txt` vide ; présence vérifiée dans le DEX final de
  `MosaicApp`, `MainActivity`, `MosaicNotificationListener`,
  `MosaicDatabase_Impl`, des deux workers, de `SupportOpenHelperFactory`, des
  20 sérialiseurs kotlinx et des annotations `retrofit2.http.*`.

Ce qu'il reste à vérifier sur un vrai téléphone, et qui ne peut pas l'être
ici : l'installation elle-même, le premier lancement, le dialogue biométrique,
l'accès aux notifications, et la capture d'un vrai message Instagram. La
procédure est dans [`../release/README.md`](../release/README.md).
