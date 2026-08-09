import { loadFont } from "@remotion/fonts";
import { Composition, Folder, staticFile } from "remotion";
import "./index.css";
import { PresentationVideo } from "./PresentationVideo";
import { SceneConfidentialite } from "./scenes/SceneConfidentialite";
import { SceneContexte } from "./scenes/SceneContexte";
import { SceneOutro } from "./scenes/SceneOutro";
import { ScenePlan } from "./scenes/ScenePlan";
import { SceneProduit } from "./scenes/SceneProduit";
import { SceneQuestionnaire } from "./scenes/SceneQuestionnaire";
import { SceneQuestions } from "./scenes/SceneQuestions";
import { SceneSources } from "./scenes/SceneSources";
import { SceneStatuts } from "./scenes/SceneStatuts";
import { SceneVolets } from "./scenes/SceneVolets";

// Inter is self-hosted from public/fonts so rendering needs no network access.
// One variable file covers the whole 100-900 range. Called here (rather than in
// a separate module) so the bundler always keeps it: the scenes hardcode
// `fontFamily: "Inter"` inline to stay editable in the Studio.
loadFont({
  family: "Inter",
  url: staticFile("fonts/Inter.woff2"),
  weight: "100 900",
  display: "block",
});

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition
        id="PresentationEFacturation"
        component={PresentationVideo}
        durationInFrames={1587}
        fps={30}
        width={1920}
        height={1080}
      />
      <Folder name="Scenes">
        <Composition
          id="Contexte"
          component={SceneContexte}
          durationInFrames={150}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Questions"
          component={SceneQuestions}
          durationInFrames={165}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Produit"
          component={SceneProduit}
          durationInFrames={150}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Questionnaire"
          component={SceneQuestionnaire}
          durationInFrames={180}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Volets"
          component={SceneVolets}
          durationInFrames={165}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Statuts"
          component={SceneStatuts}
          durationInFrames={180}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Plan"
          component={ScenePlan}
          durationInFrames={180}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Sources"
          component={SceneSources}
          durationInFrames={165}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Confidentialite"
          component={SceneConfidentialite}
          durationInFrames={180}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Outro"
          component={SceneOutro}
          durationInFrames={180}
          fps={30}
          width={1920}
          height={1080}
        />
      </Folder>
    </>
  );
};
