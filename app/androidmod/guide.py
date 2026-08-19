"""Guide de récupération des données encore présentes sur TON appareil."""
from __future__ import annotations

from typing import Any

GUIDE: list[dict[str, Any]] = [
    {
        "titre": "1. Sans aucun outil technique (recommande en premier)",
        "etapes": [
            "Branche le téléphone en USB et choisis « Transfert de fichiers (MTP) ».",
            "Ouvre l'explorateur Windows : le téléphone apparait comme un lecteur.",
            "Copie ces dossiers vers ton ordinateur : DCIM/Camera, Pictures/Instagram, "
            "Pictures/Screenshots, Movies/Instagram, Download, Documents.",
            "Dans cet outil, onglet Sources, ajoute les dossiers copies : chaque fichier "
            "sera hache en SHA-256 et horodate a l'import.",
        ],
        "note": "Cette methode ne nécessite ni adb, ni root, ni reglage particulier.",
    },
    {
        "titre": "2. Avec adb (facultatif, plus complet)",
        "etapes": [
            "Installe « Android SDK Platform Tools » depuis le site officiel Android.",
            "Sur le téléphone : Paramètrès > A propos > appuie 7 fois sur « Numero de build » "
            "pour activer les options développeur.",
            "Options développeur > active « Debogage USB ».",
            "Branche le téléphone et accepte la demande d'autorisation affichée a l'écran.",
            "Dans cet outil, onglet Android : détecté l'appareil, explore les dossiers publics, "
            "puis importé ceux qui t'interessent.",
        ],
        "note": "adb ne lit ici que le stockage partage, exactement comme l'explorateur Windows.",
    },
    {
        "titre": "3. Ou chercher des traces d'Instagram sur l'appareil",
        "etapes": [
            "Download/ : une archive ZIP d'export Instagram déjà téléchargée, des PDF, des e-mails.",
            "Pictures/Instagram/ et Movies/Instagram/ : contenus enregistrés depuis l'application.",
            "Pictures/Screenshots/ : captures de conversations, de profils, de notifications.",
            "DCIM/Camera/ : photos publiées a l'epoque (les metadonnees donnent la date de prise de vue).",
            "Documents/ et dossiers de sauvegarde d'applications de notes.",
            "Sauvegardes Google Drive / Google Photos associees au compte Google du téléphone.",
        ],
        "note": "Ces emplacements sont accessibles sans aucune manipulation technique.",
    },
    {
        "titre": "4. Autres pistes hors téléphone",
        "etapes": [
            "Boite e-mail (même ancienne, si encore accessible) : rechercher « Instagram » — "
            "notifications de connexion, changements d'e-mail, confirmations d'export.",
            "Anciens ordinateurs : dossier Telechargements, corbeille, historique de navigateur.",
            "Sauvegardes cloud : Google Drive, iCloud, OneDrive, Dropbox.",
            "Cartes SD et disques externes d'anciens téléphones.",
        ],
        "note": "Chaque source ajoutee ameliore la reconstitution et permet des comparaisons.",
    },
]

LIMITES: list[str] = [
    "Les messages privés d'Instagram ne sont PAS stockes en clair dans un dossier "
    "accessible du téléphone : ils vivent dans l'espace privé de l'application, "
    "auquel Android interdit l'accès sans rooter l'appareil. Cet outil ne rootera "
    "jamais ton téléphone et n'ira jamais dans cet espace.",
    "Le cache de l'application (miniatures, images temporaires) n'est pas non plus "
    "accessible sans contourner Android.",
    "Seule une archive officielle Instagram restitue les conversations complètes. "
    "Elle se demande depuis le compte, donc après récupération de l'accès.",
    "Les données d'autres applications ne sont jamais lues par cet outil.",
]

ACCOUNT_RECOVERY: list[dict[str, str]] = [
    {
        "titre": "Récupération officielle du compte",
        "detail": "Sur l'écran de connexion Instagram : « Vous avez oublie votre mot de passe ? » "
        "puis « Besoin d'aide ? ». Instagram propose une vérification par selfie video "
        "lorsque l'e-mail et le téléphone ne sont plus accessibles.",
    },
    {
        "titre": "Formulaire de compte piratee",
        "detail": "instagram.com/hacked : parcours dedie lorsque l'adresse e-mail associee "
        "a été changee sans ton accord.",
    },
    {
        "titre": "Demande d'archive",
        "detail": "Une fois l'accès retrouve : Paramètrès > Espace comptes > Vos informations "
        "et autorisations > Télécharger vos informations. Choisis le format JSON et "
        "« toutes les données » : c'est le fichier le plus utile pour cet outil.",
    },
    {
        "titre": "Droit d'accès (RGPD)",
        "detail": "En Europe, tu peux exercer ton droit d'accès a tes données personnelles "
        "auprès de Meta. En cas de refus ou d'absence de réponse, une réclamation "
        "est possible auprès de la CNIL.",
    },
]


def full_guide() -> dict[str, Any]:
    return {
        "guide": GUIDE,
        "limites": LIMITES,
        "recuperation_du_compte": ACCOUNT_RECOVERY,
        "avertissement": (
            "Ce module ne concerne que TES appareils et TES données. Il ne permet "
            "pas d'acceder aux données d'autrui, ne roote rien et ne contourne "
            "aucune protection."
        ),
    }
