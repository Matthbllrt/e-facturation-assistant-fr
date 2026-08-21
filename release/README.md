# Mosaic — installation

## Ce qu'il y a dans ce dossier

| Fichier | À quoi il sert |
|---|---|
| `Mosaic-release.apk` | **C'est celui-ci qu'on installe sur un téléphone.** APK universel (arm64, arm32, x86, x86_64), signé, minifié. |
| `Mosaic-release.aab` | Bundle pour Google Play. **Ne s'installe pas** directement sur un téléphone. |
| `mapping.txt.gz` | Correspondance R8, pour lire une trace d'erreur de cette version précise. |
| `SHA256SUMS.txt` | Empreintes des deux binaires. |

## Installation

Android 12 minimum. Testé pour cibler Android 16 / Samsung Galaxy.

1. Copier `Mosaic-release.apk` sur le téléphone (câble USB, ou tout autre
   moyen — mais éviter de le faire passer par un service en ligne).
2. Ouvrir le fichier depuis le gestionnaire de fichiers.
3. Android demandera d'autoriser l'installation d'applications provenant de
   cette source : **Réglages → Applications → Accès spécial → Installer des
   applications inconnues**, puis autoriser le gestionnaire de fichiers.
4. Installer, puis ouvrir **Mosaic**.

Vérification facultative de l'intégrité du fichier avant installation :

```bash
sha256sum -c SHA256SUMS.txt
```

## Première configuration

L'assistant fait cinq écrans.

**1. Méthode.** Deux choix, et le bon dépend de votre compte Instagram :

- **Notifications Android** — pour un compte Instagram personnel. Mosaic lit les
  notifications Instagram de la personne choisie. Aucun mot de passe, aucun
  jeton, aucun serveur. **La capture ne commence qu'à partir de maintenant :
  l'historique antérieur n'est pas récupérable**, Android ne l'expose pas.
- **API officielle Instagram** — réservé aux comptes professionnels
  (Business/Creator). Demande un jeton d'accès longue durée que vous générez
  depuis votre propre application Meta. Là aussi, Meta ne rend lisibles que les
  20 derniers messages d'un fil.

**2. Autorisation.** En mode notifications, Android demande l'accès aux
notifications. C'est la seule façon officielle de lire les messages d'un compte
personnel. Mosaic ne conserve que la conversation choisie et ignore tout le
reste.

**3. Personne suivie.** En mode notifications, la liste se remplit dès qu'une
notification Instagram arrive — envoyez-vous un message pour la faire
apparaître, puis sélectionnez la conversation. Choisir dans la liste plutôt que
taper le nom permet à Mosaic de retenir la **clé de conversation**, ce qui évite
de confondre deux personnes portant le même nom.

**4. Alias.** Par défaut **Louis**. Ce nom remplace l'identité réelle partout
dans l'application, dans les notifications et dans la liste des applications
récentes. Il ne change rien sur Instagram.

**5. Terminé.**

Pensez ensuite à définir un **code de secours** dans Paramètres → Verrouillage :
sans lui, si la biométrie devient indisponible, il n'y a pas d'autre porte.

## Ce que l'application fait, et ne fait pas

- Elle conserve une copie locale **chiffrée** des messages, y compris si la
  conversation est ensuite supprimée côté Instagram.
- Elle répond depuis l'application quand c'est possible : via l'API officielle
  en mode A, via la réponse rapide de la notification en mode B. Quand ce n'est
  pas possible, elle affiche un bouton **« Ouvrir Instagram »** au lieu d'un
  bouton d'envoi qui ne ferait rien.
- **Elle ne supprime jamais rien sur Instagram.** L'API de Meta n'expose aucune
  opération de suppression de conversation ; les seules méthodes qui
  fonctionneraient feraient bannir le compte. À la fin du cooldown, Mosaic
  affiche « Nettoyage prêt » et propose d'ouvrir Instagram — la suppression est
  faite par vous. Détails dans [`../docs/INSTAGRAM.md`](../docs/INSTAGRAM.md).
- Elle n'envoie rien à aucun serveur, sauf à `graph.instagram.com` en mode A.
- Elle ne pose aucune notification par défaut.

## Effacer

Menu **⋮ → Effacer**, après confirmation et authentification :

- **Effacer la conversation locale** — les messages disparaissent, la
  configuration reste.
- **Réinitialiser entièrement Mosaic** — base, coffre chiffré, préférences et
  **la clé du Keystore**. Sans cette clé, aucune donnée résiduelle sur la
  mémoire flash n'est déchiffrable. Il n'y a pas de corbeille.

## À savoir sur la signature

Cet APK est signé avec une **clé de développement jetable** dont le mot de passe
est public (voir [`../docs/SIGNING.md`](../docs/SIGNING.md)). C'est sans
conséquence pour un usage privé en installation manuelle, et c'est ce qui permet
d'installer une future version par-dessus celle-ci sans perdre le coffre. En
revanche il ne faut pas publier ce binaire tel quel : remplacer la clé d'abord.

## Si quelque chose ne va pas

| Symptôme | Cause probable |
|---|---|
| « La capture est inactive » | L'accès aux notifications a été révoqué, ou le jeton d'API a expiré. Paramètres → Synchronisation. |
| « Identification par nom affiché uniquement » | Android n'a pas fourni de clé de conversation. Refaire le choix de la personne depuis la liste, à la réception d'une notification. |
| « Coffre illisible » | La clé du Keystore a disparu (données de l'application effacées, appareil restauré depuis une sauvegarde d'un autre téléphone). Mosaic ne supprime rien de lui-même ; les données existantes ne sont plus déchiffrables et une réinitialisation est nécessaire. |
| Aucun message ne s'enregistre | Les notifications Instagram sont-elles activées pour cette conversation, côté Instagram ? |
| « Fenêtre de réponse de 24 h dépassée » | Règle Meta en mode API. Passer par « Ouvrir Instagram ». |
