# Guide d'utilisation pas à pas

Ce guide s'adresse à une personne sans connaissances techniques.

## 1. Installer

1. Installez Python depuis <https://www.python.org/downloads/windows/>.
   **Cochez « Add Python to PATH »** sur le premier écran de l'installateur.
2. Double-cliquez sur `setup.bat`. Attendez le message « Installation terminée ».
3. Double-cliquez sur `start.bat`. Une fenêtre noire s'ouvre, puis le navigateur.

Si le navigateur ne s'ouvre pas, tapez `http://127.0.0.1:8734/` dans la barre
d'adresse.

## 2. Créer le dossier d'enquête

Saisissez le nom d'utilisateur du compte concerné, sans le « @ », puis cliquez
sur **Créer le dossier**. Un dossier `cases/<nom>` est créé sur votre disque : il
contient désormais tout ce qui concerne cette enquête.

## 3. Archiver ce qui est encore public

Onglet **Profil** → **Rechercher les informations publiques**.

L'outil consulte l'adresse publique du profil, exactement comme un visiteur non
connecté, et enregistre ce qu'il y trouve avec la date et l'heure de la
consultation.

Si Instagram refuse la consultation automatique, l'outil vous le dit clairement
et ne cherche pas à contourner le refus. Dans ce cas :

1. ouvrez `https://www.instagram.com/<nom_utilisateur>/` dans votre navigateur ;
2. clic droit → « Afficher le code source de la page » ;
3. sélectionnez tout (Ctrl+A), copiez (Ctrl+C) ;
4. collez dans le champ **Saisie manuelle**, puis **Enregistrer cette collecte**.

Une capture d'écran de la page fait également une preuve utile : ajoutez-la
depuis l'onglet **Imports** en choisissant le type « Autres fichiers ».

## 4. Importer une archive Instagram

C'est la source la plus riche : elle contient les conversations, les abonnés,
les abonnements, les médias et l'historique du compte.

Onglet **Imports** :

- **Méthode recommandée** : collez le chemin complet du fichier ou du dossier,
  par exemple `C:\Users\moi\Downloads\instagram-mon_compte.zip`, puis cliquez
  sur **Importer ce chemin**. Cette méthode supporte les archives de plusieurs
  gigaoctets.
  Astuce : dans l'explorateur Windows, clic droit sur le fichier en maintenant
  Maj → « Copier en tant que chemin d'accès ».
- **Autre méthode** : bouton **Parcourir** puis **Envoyer et analyser**.

Renseignez la **date de l'export** si vous la connaissez : c'est elle qui permet
de borner les changements lors d'une comparaison.

L'analyse démarre automatiquement. Une barre d'avancement s'affiche en haut à
droite. Vous pouvez continuer à naviguer pendant ce temps.

### Où trouver une ancienne archive

- Dossier `Téléchargements` de vos ordinateurs actuels et anciens ;
- dossier `Download` du téléphone ;
- Google Drive, iCloud, OneDrive, Dropbox ;
- pièces jointes et liens dans vos e-mails (cherchez « Instagram ») ;
- cartes SD et disques externes.

## 5. Ajouter toutes les autres traces

Onglet **Imports**, type « Autres fichiers » : captures d'écran, vidéos d'écran,
e-mails Instagram sauvegardés, PDF, exports de notifications, dossiers de
téléphone, anciennes sauvegardes.

Pour chaque lot, remplissez **Provenance déclarée** et **Observations** : d'où
vient ce fichier, quand vous l'avez obtenu, ce qu'il montre. C'est ce contexte
qui donne sa valeur à une preuve.

Onglet **Sources** → **Ajouter une observation écrite** pour consigner un
souvenir. Il sera enregistré comme déclaration, avec un niveau de confiance
explicite, jamais comme un fait établi.

## 6. Comparer deux exports

Si vous disposez de deux archives de dates différentes, onglet **Comparaison
d'exports** : choisissez la plus ancienne et la plus récente, puis **Comparer**.

L'outil liste les abonnés apparus et disparus, les abonnements ajoutés et
supprimés, les conversations et messages ajoutés ou absents, et les médias.
Chaque changement est daté par un encadrement du type : « ajout intervenu entre
ces deux dates » — jamais par une date inventée.

## 7. Lire les conversations

Onglet **Conversations** : la liste des fils à gauche, la conversation à droite.

Vous pouvez filtrer par mot, expéditeur, période, type de contenu, messages
envoyés ou reçus, médias seulement, liens seulement.

Le bouton **Afficher la source originale** sous chaque message montre la donnée
brute exacte du fichier d'export, avec l'empreinte SHA-256 du fichier d'origine.

Les messages retirés par leur auteur apparaissent en rouge, signalés comme tels :
leur contenu n'est pas dans l'export et n'est pas reconstituable.

## 8. Comprendre les éléments manquants

Onglet **Éléments manquants**. Ce ne sont **pas** des contenus retrouvés, mais
des indices d'absence :

- **CONFIRMÉ** : la source elle-même atteste l'absence (message marqué retiré,
  média cité par un message mais absent de l'archive) ;
- **PROBABLE** : plusieurs indices concordent (fichier manquant dans une série
  numérotée, média orphelin, réaction sur un message vide) ;
- **POSSIBLE** : anomalie statistique, par exemple un long silence dans un fil
  habituellement très actif. Un silence n'est jamais une preuve de suppression,
  et l'outil le précise.

## 9. Figer les preuves

Onglet **Mode preuve** → **Activer le mode preuve**.

À partir de là, les fichiers sources sont en lecture seule, aucun import n'est
possible, et `manifest.json` fige l'empreinte SHA-256 de chaque fichier. Le
bouton **Vérifier l'intégrité maintenant** recalcule toutes les empreintes et
signale le moindre écart.

Faites-le une fois vos imports terminés. Vous pourrez le désactiver plus tard
pour ajouter une source : le manifeste précédent reste conservé.

## 10. Produire le rapport

Onglet **Rapport final** → **Générer le rapport complet**.

Vous obtenez, dans `cases/<nom>/reports/` :

- `rapport.html` — à ouvrir dans un navigateur, imprimable ;
- `rapport.pdf` — version imprimée ;
- `rapport.json` — données complètes ;
- `csv/` — un fichier par catégorie (abonnés, messages, chronologie, empreintes…) ;
- une archive `.zip` regroupant le tout, prête à être transmise.

Le rapport comporte 12 sections : compte concerné, période étudiée, sources
utilisées, intégrité des fichiers, conversations, chronologie, abonnés,
abonnements, changements détectés, médias, éléments potentiellement manquants,
limites de la récupération.

## 11. Récupérer davantage

Onglet **Récupérer davantage** : liste ce qui est acquis (✅) et ce qui manque
(❌), avec pour chaque manque la marche à suivre. Il rappelle aussi ce qui n'est
pas récupérable sans reprendre le contrôle du compte.

### Reprendre le contrôle du compte (voie officielle)

- Écran de connexion → « Vous avez oublié votre mot de passe ? » → « Besoin
  d'aide ? ». Instagram propose une vérification par selfie vidéo lorsque
  l'e-mail et le téléphone ne sont plus accessibles.
- `instagram.com/hacked` si l'adresse e-mail a été changée sans votre accord.
- En Europe, vous pouvez exercer votre droit d'accès à vos données auprès de
  Meta ; en cas de refus ou d'absence de réponse, une réclamation est possible
  auprès de la CNIL.

## Questions fréquentes

**Mes données partent-elles quelque part ?**
Non. Le service n'écoute que sur votre machine et refuse les connexions
distantes. La seule requête réseau possible est la consultation de la page
publique du profil, que vous déclenchez vous-même.

**Puis-je déplacer mon dossier d'enquête ?**
Oui. Le dossier `cases/<nom>` est autonome : copiez-le sur une clé USB, il
s'ouvrira ailleurs avec la même application.

**L'outil peut-il retrouver un message supprimé ?**
Seulement s'il figure encore dans une source que vous fournissez (un export plus
ancien, une capture d'écran). Sinon, il signale l'absence sans jamais
reconstituer le contenu.

**Le port 8734 est déjà utilisé.**
Ouvrez une invite de commandes dans le dossier et lancez :
`.venv\Scripts\python.exe run.py --port 8750`
