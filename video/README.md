# Vidéo de présentation — E-Facturation Assistant FR

Vidéo de présentation produit (1920×1080, 30 fps, ~53 s), construite avec
[Remotion](https://www.remotion.dev/).

Le contenu est repris de la documentation client du projet stockée dans Google
Drive (démarrage rapide, guide utilisateur, FAQ, notice de limites) : les quatre
volets réglementaires, les cinq statuts, les priorités, les sources officielles
DGFiP et l'avertissement produit sont cités tels qu'ils y figurent.

## Commandes

```bash
npm i                  # installer les dépendances
npx remotion studio    # ouvrir l'éditeur (aperçu + édition visuelle)
npx remotion render PresentationEFacturation out/video.mp4
```

## Structure

| Fichier | Rôle |
| --- | --- |
| `src/Root.tsx` | Enregistre la composition principale et chaque scène individuellement, charge la police |
| `src/PresentationVideo.tsx` | Assemble les 10 scènes dans une `TransitionSeries` avec des fondus de 12 images |
| `src/scenes/*.tsx` | Une scène par fichier |

### Séquence

| # | Scène | Images | Propos |
| --- | --- | --- | --- |
| 1 | `SceneContexte` | 150 | La réforme 2026–2027 et qui elle concerne |
| 2 | `SceneQuestions` | 165 | Les trois questions que se posent les TPE |
| 3 | `SceneProduit` | 150 | Révélation du produit |
| 4 | `SceneQuestionnaire` | 180 | Le questionnaire conditionnel |
| 5 | `SceneVolets` | 165 | Les quatre volets réglementaires |
| 6 | `SceneStatuts` | 180 | Les cinq statuts, dont « À vérifier » |
| 7 | `ScenePlan` | 180 | Priorités P0/P1/P2 puis checklist |
| 8 | `SceneSources` | 165 | Traçabilité DGFiP et date de vérification |
| 9 | `SceneConfidentialite` | 180 | Fonctionnement hors ligne |
| 10 | `SceneOutro` | 180 | Livrables et avertissement |

Durée totale : 1695 images − 9 transitions × 12 = **1587 images**. Si vous
modifiez la durée d'une scène, reportez le nouveau total dans
`durationInFrames` sur la composition `PresentationEFacturation`.

## Conventions de code

Les scènes suivent les règles d'interactivité de Remotion pour rester
modifiables directement dans le Studio :

- styles entièrement en ligne, sans constantes ni spread ;
- appels `interpolate()` en ligne dans la prop `style` ;
- propriétés `scale` / `translate` / `rotate` plutôt que `transform` ;
- textes fixes écrits en dur dans le JSX ;
- `durationInFrames` en valeurs littérales.

## Police

Inter est auto-hébergée dans `public/fonts/Inter.woff2` (fichier variable
couvrant 100–900) et chargée via `@remotion/fonts` dans `src/Root.tsx`. Le rendu
ne fait donc aucun appel réseau — `@remotion/google-fonts` échouait ici, le
navigateur de rendu n'ayant pas accès à `fonts.gstatic.com`.

Les scènes déclarent `fontFamily: "Inter"` en dur plutôt que d'importer une
constante, pour rester éditables dans le Studio.
