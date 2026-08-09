import {
  AbsoluteFill,
  Easing,
  Interactive,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export const SceneContexte: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  return (
    <AbsoluteFill name="Scène — Contexte" style={{ backgroundColor: "#0A1628" }}>
      <Interactive.Div
        name="Halo"
        style={{
          position: "absolute",
          inset: 0,
          background:
            "radial-gradient(circle at 50% 38%, #16365E 0%, #0A1628 62%)",
        }}
      />
      <AbsoluteFill
        name="Contenu"
        style={{
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "center",
          padding: 160,
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
            marginBottom: 44,
            opacity: interpolate(frame, [0, 0.8 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
            translate: interpolate(
              frame,
              [0, 0.8 * fps],
              ["0px 24px", "0px 0px"],
              {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              },
            ),
          }}
        >
          RÉFORME FRANÇAISE 2026–2027
        </Interactive.Div>

        <Interactive.Div
          name="Titre"
          style={{
            fontFamily: "Inter",
            fontSize: 116,
            fontWeight: 800,
            lineHeight: 1.08,
            letterSpacing: -2,
            color: "#F2F6FB",
            textAlign: "center",
            maxWidth: 1500,
            opacity: interpolate(frame, [0.35 * fps, 1.3 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
            translate: interpolate(
              frame,
              [0.35 * fps, 1.3 * fps],
              ["0px 40px", "0px 0px"],
              {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              },
            ),
          }}
        >
          La facturation électronique devient obligatoire
        </Interactive.Div>

        <Interactive.Div
          name="Sous-titre"
          style={{
            fontFamily: "Inter",
            fontSize: 58,
            fontWeight: 400,
            lineHeight: 1.4,
            color: "#93A9C4",
            textAlign: "center",
            maxWidth: 1300,
            marginTop: 52,
            opacity: interpolate(frame, [1.1 * fps, 2 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
            translate: interpolate(
              frame,
              [1.1 * fps, 2 * fps],
              ["0px 28px", "0px 0px"],
              {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              },
            ),
          }}
        >
          Micro-entrepreneurs, freelances et TPE françaises sont concernés.
        </Interactive.Div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
