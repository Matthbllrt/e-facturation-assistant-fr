import { TransitionSeries, linearTiming } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { AbsoluteFill } from "remotion";
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

export const PresentationVideo: React.FC = () => {
  return (
    <AbsoluteFill style={{ backgroundColor: "#0A1628" }}>
      <TransitionSeries>
        <TransitionSeries.Sequence durationInFrames={150} name="Contexte">
          <SceneContexte />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition
          presentation={fade()}
          timing={linearTiming({ durationInFrames: 12 })}
        />
        <TransitionSeries.Sequence durationInFrames={165} name="Questions">
          <SceneQuestions />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition
          presentation={fade()}
          timing={linearTiming({ durationInFrames: 12 })}
        />
        <TransitionSeries.Sequence durationInFrames={150} name="Produit">
          <SceneProduit />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition
          presentation={fade()}
          timing={linearTiming({ durationInFrames: 12 })}
        />
        <TransitionSeries.Sequence durationInFrames={180} name="Questionnaire">
          <SceneQuestionnaire />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition
          presentation={fade()}
          timing={linearTiming({ durationInFrames: 12 })}
        />
        <TransitionSeries.Sequence durationInFrames={165} name="Volets">
          <SceneVolets />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition
          presentation={fade()}
          timing={linearTiming({ durationInFrames: 12 })}
        />
        <TransitionSeries.Sequence durationInFrames={180} name="Statuts">
          <SceneStatuts />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition
          presentation={fade()}
          timing={linearTiming({ durationInFrames: 12 })}
        />
        <TransitionSeries.Sequence durationInFrames={180} name="Plan">
          <ScenePlan />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition
          presentation={fade()}
          timing={linearTiming({ durationInFrames: 12 })}
        />
        <TransitionSeries.Sequence durationInFrames={165} name="Sources">
          <SceneSources />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition
          presentation={fade()}
          timing={linearTiming({ durationInFrames: 12 })}
        />
        <TransitionSeries.Sequence
          durationInFrames={180}
          name="Confidentialité"
        >
          <SceneConfidentialite />
        </TransitionSeries.Sequence>
        <TransitionSeries.Transition
          presentation={fade()}
          timing={linearTiming({ durationInFrames: 12 })}
        />
        <TransitionSeries.Sequence durationInFrames={180} name="Outro">
          <SceneOutro />
        </TransitionSeries.Sequence>
      </TransitionSeries>
    </AbsoluteFill>
  );
};
