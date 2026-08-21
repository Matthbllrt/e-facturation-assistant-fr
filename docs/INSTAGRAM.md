# Intégration Instagram — ce qui est possible, ce qui ne l'est pas

Ce document consigne les vérifications faites sur la documentation Meta avant
d'écrire le code, et les conséquences concrètes sur Mosaic. Il existe pour une
raison précise : plusieurs fonctionnalités demandées ne sont **pas réalisables
proprement**, et il vaut mieux l'écrire noir sur blanc que de livrer une
illusion.

Vérifié le 21 août 2026 contre la documentation officielle Meta.

## Ce que Mosaic ne fait jamais

Aucune des techniques suivantes n'est utilisée, nulle part dans le code :

- demander ou stocker un mot de passe Instagram ;
- réutiliser des cookies ou une session Instagram ;
- scraper des pages Instagram ;
- appeler une API privée ou non documentée ;
- rétro-ingénierie du protocole Instagram ;
- contournement d'une protection Meta ;
- automatisation de clics via un service d'accessibilité.

Ce ne sont pas seulement des interdits contractuels : ce sont exactement les
techniques qui font bannir un compte. Un coffre de messages dont le compte
source est banni ne sert à rien.

## Mode A — API officielle (comptes professionnels uniquement)

**Ce qui existe.** L'« Instagram API with Instagram Login » expose la
Conversations API et la Send API :

| Opération | Endpoint | Utilisé par Mosaic |
|---|---|---|
| Lister les conversations | `GET /me/conversations` | oui |
| Lister les messages d'un fil | `GET /{conversation-id}?fields=messages` | oui |
| Lire un message | `GET /{message-id}` | oui |
| Envoyer un message | `POST /me/messages` | oui |
| Renouveler le jeton | `GET /refresh_access_token` | oui |

Permissions minimales : `instagram_business_basic` et
`instagram_business_manage_messages`.

**Limites réelles, appliquées dans le code.**

1. **Comptes professionnels seulement.** Meta ne délivre pas de jeton de
   messagerie exploitable pour un compte Instagram personnel. Si votre compte
   n'est pas Business ou Creator, le mode A ne fonctionnera pas — d'où le
   mode B.
2. **20 derniers messages.** La documentation précise qu'au-delà des 20
   messages les plus récents d'un fil, la lecture d'un message renvoie une
   erreur « message supprimé ». Mosaic **n'importe donc pas l'historique** : il
   accumule à partir de son installation. C'est dit explicitement dans
   l'assistant de configuration.
3. **Fenêtre de 24 heures.** Un compte professionnel ne peut répondre que dans
   les 24 h suivant le dernier message reçu. Au-delà, l'envoi est refusé par
   Meta ; Mosaic affiche le message d'erreur correspondant et propose le
   repli « Ouvrir Instagram ».
4. **Conversations en attente.** Les fils du dossier « Demandes » inactifs
   depuis plus de 30 jours n'apparaissent pas dans l'API.

**Pas de secret Meta dans l'APK.** L'échange d'un code OAuth contre un jeton
exige le secret de l'application Meta. Un secret embarqué dans un APK n'est pas
un secret : il est extractible en quelques minutes. Mosaic ne fait donc jamais
cet échange. Vous générez vous-même un jeton longue durée depuis votre propre
application Meta et vous le collez dans Mosaic, qui le scelle avec le Keystore
Android. Le renouvellement passe par `refresh_access_token`, seul endpoint de
jeton qui n'exige aucun secret.

## Mode B — notifications Android (tout compte)

Comme l'API officielle est fermée aux comptes personnels, Mosaic propose un
mode purement local, sans serveur et sans identifiants :
`NotificationListenerService` lit les notifications Instagram et ne conserve que
celles de la conversation choisie.

**Ce que ce mode peut faire.**

- Capturer les messages **à partir du moment où il est activé**.
- Distinguer entrant et sortant via `MessagingStyle`.
- Répondre depuis Mosaic **lorsque** la notification Instagram porte une action
  `RemoteInput` — c'est exactement ce que fait la réponse rapide du système.

**Ce que ce mode ne peut pas faire, et ne prétend pas faire.**

- Récupérer l'historique antérieur. Android n'expose rien de tel.
- Voir un message arrivé alors que les notifications Instagram étaient coupées,
  ou pendant que l'accès aux notifications de Mosaic était révoqué.
- Récupérer une image ou une vidéo. Seule l'existence de la pièce jointe est
  enregistrée, affichée comme `[contenu non récupérable]`.
- Répondre quand Instagram a retiré la notification : le `PendingIntent` meurt
  avec elle. Mosaic bascule alors sur « Ouvrir Instagram ».

**Homonymes.** Deux personnes peuvent porter le même nom affiché. Mosaic
mémorise la clé de conversation (`shortcutId`) au moment du choix et s'en sert
en priorité. Quand Android ne fournit pas cette clé, la correspondance se fait
sur le nom seul et un avertissement permanent s'affiche en tête de conversation.

## Suppression automatique de la conversation Instagram

**Demandée. Non réalisable proprement. Non implémentée.**

Vérification faite : la Conversations API de Meta documente `GET` sur
`/me/conversations`, `/{conversation-id}` et `/{message-id}`, et `POST` sur
`/me/messages`. **Aucune opération de suppression de fil ou d'annulation de
message n'est documentée.** L'API « Moderate Conversations » permet de bloquer
un utilisateur, de le débloquer ou de déplacer un fil en spam — jamais de le
supprimer.

Les seules façons de supprimer un fil par programme seraient une API privée ou
l'automatisation de clics dans l'application Instagram. Les deux sont exclues
(voir la première section).

**Ce qui est implémenté à la place.** Le cooldown existe, mais son aboutissement
est honnête :

1. le message est reçu, chiffré et stocké localement ;
2. le délai choisi s'écoule (désactivé, 30 s, 2 min, 5 min, 15 min, ou
   personnalisé) ;
3. Mosaic affiche discrètement « Nettoyage prêt » ;
4. un bouton ouvre Instagram, au plus près de la conversation quand un lien
   officiel `ig.me/m/<username>` est disponible, sinon sur la boîte de
   réception ;
5. **vous** effectuez la suppression ;
6. Mosaic conserve sa copie locale.

Mosaic n'affiche jamais « conversation supprimée sur Instagram », puisqu'il n'a
aucun moyen de le vérifier.

## Ce que Mosaic ne supprime jamais tout seul

Un message enregistré localement reste enregistré, même si l'autre personne le
supprime côté Instagram, même si une resynchronisation renvoie moins
d'historique, même si l'envoi échoue. Seul le bouton « Effacer » supprime des
données, après confirmation et authentification.
