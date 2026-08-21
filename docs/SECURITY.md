# Revue de sécurité — Mosaic 1.0.0

Revue effectuée avant la production des binaires release.

## Modèle de menace

Mosaic protège le contenu d'une conversation contre :

| Menace | Défense |
|---|---|
| Quelqu'un prend le téléphone déverrouillé | Verrouillage biométrique/PIN indépendant du verrouillage système, verrouillage automatique configurable, bouton « Verrouiller » immédiat |
| Regard par-dessus l'épaule, aperçu Récents | `FLAG_SECURE`, libellé et couleur neutres dans le sélecteur d'applications, aucune notification par défaut |
| Extraction hors ligne du stockage (adb backup, image du flash) | SQLCipher pour la base, AES-256-GCM scellé par le Keystore pour le reste, `allowBackup=false`, sauvegarde cloud et transfert d'appareil exclus |
| Lecture des logs (`adb logcat`, rapport de bug) | Aucun contenu, identifiant ou jeton en log ; test automatisé qui échoue le build si une régression apparaît |
| Ajout d'une empreinte par un tiers connaissant le code de l'écran | Marqueur Keystore invalidé par tout changement d'enrôlement biométrique → bascule forcée sur le PIN |
| Force brute du PIN | PBKDF2-SHA256 210 000 itérations, sel aléatoire, verrouillage exponentiel persisté (survit au redémarrage de l'application) |
| Quelqu'un voit qui est suivi | Alias local partout ; identité réelle uniquement dans le coffre chiffré, révélée après une authentification distincte, masquée à la sortie de l'écran |

Ce que Mosaic **ne protège pas** contre, et ne peut pas :

- un appareil rooté ou compromis pendant l'utilisation ;
- un attaquant qui connaît à la fois le code de déverrouillage de l'appareil **et**
  le PIN de Mosaic ;
- l'autre personne, qui a évidemment sa propre copie des messages ;
- Meta, qui a la sienne.

## Décisions de conception notables

**La clé maître n'est pas liée à l'authentification utilisateur.** Le
`NotificationListenerService` capture des messages pendant que le téléphone est
verrouillé — c'est le moment où ils arrivent. Une clé liée à l'authentification
échouerait précisément à ce moment, perdant silencieusement des messages.
L'authentification est donc appliquée à la frontière de l'interface, la clé
protégeant les données au repos contre une extraction hors ligne.
`setUnlockedDeviceRequired` est désactivé pour la même raison. StrongBox est
utilisé quand le matériel le fournit.

**La passphrase SQLCipher ne dérive pas du PIN.** 32 octets aléatoires générés
une fois, scellés par le Keystore. Changer le PIN ne met donc jamais la base en
danger, et la passphrase n'est pas devinable à partir de ce que l'utilisateur
connaît.

**Aucun secret Meta dans l'APK.** Voir [INSTAGRAM.md](INSTAGRAM.md).

**Rien n'est supprimé automatiquement.** Une base illisible est signalée, jamais
effacée ; la migration destructive de Room est désactivée et un test le
vérifie ; un échec d'écriture est remonté au lieu d'être avalé.

## Permissions

Déclarées explicitement :

| Permission | Raison |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Mode A uniquement (API Graph). Inutilisées en mode notifications. |
| `POST_NOTIFICATIONS` | Uniquement si l'utilisateur active la notification neutre. La politique par défaut ne poste rien. |
| `USE_BIOMETRIC` | Déverrouillage. |

`BIND_NOTIFICATION_LISTENER_SERVICE` est déclarée sur le service : c'est une
permission que **seul le système** accorde, via les réglages Android, et que
l'application ne peut pas s'octroyer.

Ajoutées transitivement par les bibliothèques AndroidX, visibles dans l'APK :

| Permission | Origine | Commentaire |
|---|---|---|
| `WAKE_LOCK`, `FOREGROUND_SERVICE` | WorkManager | Exécution du worker de cooldown. |
| `RECEIVE_BOOT_COMPLETED` | WorkManager | Replanifie le cooldown après un redémarrage — c'est le comportement voulu. |
| `USE_FINGERPRINT` | androidx.biometric | Compatibilité héritée de la bibliothèque. |
| `*.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core | Permission interne de signature, non exposée. |

**Jamais demandées** : contacts, stockage externe, localisation, micro, caméra,
SMS, journal d'appels, comptes.

## Absence de télémétrie

Aucune dépendance d'analytics, de crash reporting ou de publicité n'est
déclarée. Le seul trafic réseau que l'application peut produire est vers
`graph.instagram.com`, et uniquement en mode A avec un jeton fourni par
l'utilisateur. L'intercepteur de log d'OkHttp n'est délibérément pas installé.

## Hygiène des logs

`SafeLog` est le seul point de sortie. En release, seuls les événements de
niveau erreur passent, réduits au nom de l'événement et au **type** de
l'exception — le message de l'exception est écarté, parce que SQLCipher et
Retrofit ont l'habitude de recracher la valeur fautive. `LoggingHygieneTest`
parcourt l'arborescence des sources et fait échouer le build si un
`Log.d(...)` direct apparaît ou si un appel à `SafeLog` interpole une variable
au nom sensible.

## Points d'attention connus

1. **La clé de développement est versionnée** (`keystore/mosaic-dev.jks`, mot de
   passe dans `keystore/mosaic-dev.properties`). C'est délibéré pour un dépôt
   privé : sans elle, aucune mise à jour côté téléphone ne pourrait s'installer
   par-dessus la précédente. Elle est inutilisable pour une publication — voir
   [SIGNING.md](SIGNING.md).
2. **Le jeton d'API est collé manuellement.** L'alternative — embarquer le
   secret Meta — serait pire. Le jeton est scellé par le Keystore.
3. **Le mode notifications voit passer les notifications des autres
   applications.** C'est inhérent à `NotificationListenerService` : Android ne
   permet pas de s'abonner à un seul paquet. Mosaic filtre dès l'entrée et
   n'écrit jamais rien qui ne provienne de la conversation ciblée. Le
   `default_filter_types="conversations"` du manifeste réduit encore ce que le
   système transmet.
4. **L'export chiffré n'est protégé que par sa phrase secrète.** Il quitte le
   périmètre du Keystore. L'interface le dit, et refuse les phrases de moins de
   10 caractères.
