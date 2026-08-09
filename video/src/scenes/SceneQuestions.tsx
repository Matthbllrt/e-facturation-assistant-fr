import {
  AbsoluteFill,
  Easing,
  Interactive,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export const SceneQuestions: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  return (
    <AbsoluteFill name="Scène — Questions" style={{ backgroundColor: "#0A1628" }}>
      <Interactive.Div
        name="Halo"
        style={{
          position: "absolute",
          inset: 0,
          background:
            "radial-gradient(circle at 22% 50%, #16365E 0%, #0A1628 60%)",
        }}
      />
      <AbsoluteFill
        name="Contenu"
        style={{
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "flex-start",
          padding: 170,
        }}
      >
        <Interactive.Div
          name="Sur-titre"
          style={{
            fontFamily: "Inter",
            fontSize: 34,
            fontWeight: 700,
            letterSpacing: 8,
            color: "#5FA8FF",
            marginBottom: 56,
            opacity: interpolate(frame, [0, 0.7 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          CE QUE TOUT LE MONDE SE DEMANDE
        </Interactive.Div>

        <Interactive.Div
          name="Question 1"
          style={{
            fontFamily: "Inter",
            fontSize: 92,
            fontWeight: 700,
            lineHeight: 1.25,
            letterSpacing: -1,
            color: "#F2F6FB",
            opacity: interpolate(frame, [0.5 * fps, 1.2 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
            translate: interpolate(
              frame,
              [0.5 * fps, 1.2 * fps],
              ["-40px 0px", "0px 0px"],
              {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              },
            ),
          }}
        >
          Suis-je concerné ?
        </Interactive.Div>

        <Interactive.Div
          name="Question 2"
          style={{
            fontFamily: "Inter",
            fontSize: 92,
            fontWeight: 700,
            lineHeight: 1.25,
            letterSpacing: -1,
            color: "#F2F6FB",
            opacity: interpolate(frame, [1.15 * fps, 1.85 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
            translate: interpolate(
              frame,
              [1.15 * fps, 1.85 * fps],
              ["-40px 0px", "0px 0px"],
              {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              },
            ),
          }}
        >
          À partir de quand ?
        </Interactive.Div>

        <Interactive.Div
          name="Question 3"
          style={{
            fontFamily: "Inter",
            fontSize: 92,
            fontWeight: 700,
            lineHeight: 1.25,
            letterSpacing: -1,
            color: "#F2F6FB",
            opacity: interpolate(frame, [1.8 * fps, 2.5 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
            translate: interpolate(
              frame,
              [1.8 * fps, 2.5 * fps],
              ["-40px 0px", "0px 0px"],
              {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              },
            ),
          }}
        >
          Que dois-je préparer ?
        </Interactive.Div>

        <Interactive.Div
          name="Réponse"
          style={{
            fontFamily: "Inter",
            fontSize: 54,
            fontWeight: 400,
            lineHeight: 1.4,
            color: "#93A9C4",
            maxWidth: 1250,
            marginTop: 56,
            opacity: interpolate(frame, [2.7 * fps, 3.5 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          Les réponses existent, mais elles sont dispersées dans les textes
          officiels.
        </Interactive.Div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
