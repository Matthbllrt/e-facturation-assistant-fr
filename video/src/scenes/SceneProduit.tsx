import {
  AbsoluteFill,
  Easing,
  Interactive,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export const SceneProduit: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  return (
    <AbsoluteFill name="Scène — Produit" style={{ backgroundColor: "#0A1628" }}>
      <Interactive.Div
        name="Halo"
        style={{
          position: "absolute",
          inset: 0,
          background:
            "radial-gradient(circle at 50% 45%, #1B4479 0%, #0A1628 65%)",
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
          name="Badge"
          style={{
            fontFamily: "Inter",
            fontSize: 32,
            fontWeight: 700,
            letterSpacing: 5,
            color: "#7DD3FC",
            backgroundColor: "#0F2A47",
            border: "2px solid #1E4E7A",
            borderRadius: 999,
            padding: "18px 44px",
            marginBottom: 56,
            opacity: interpolate(frame, [0.2 * fps, 1 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          V1 · APPLICATION HORS LIGNE
        </Interactive.Div>

        <Interactive.Div
          name="Nom du produit"
          style={{
            fontFamily: "Inter",
            fontSize: 132,
            fontWeight: 800,
            lineHeight: 1.05,
            letterSpacing: -3,
            color: "#FFFFFF",
            textAlign: "center",
            maxWidth: 1600,
            scale: interpolate(frame, [0, 1.4 * fps], [0.86, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.spring({ damping: 200 }),
              output: "perceptual-scale",
            }),
            opacity: interpolate(frame, [0, 0.9 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          E-Facturation Assistant FR
        </Interactive.Div>

        <Interactive.Div
          name="Promesse"
          style={{
            fontFamily: "Inter",
            fontSize: 56,
            fontWeight: 400,
            lineHeight: 1.4,
            color: "#A8BFD8",
            textAlign: "center",
            maxWidth: 1350,
            marginTop: 54,
            opacity: interpolate(frame, [1.2 * fps, 2.1 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
            translate: interpolate(
              frame,
              [1.2 * fps, 2.1 * fps],
              ["0px 26px", "0px 0px"],
              {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              },
            ),
          }}
        >
          Vous répondez à quelques questions. Vous obtenez ce que vous devez
          préparer.
        </Interactive.Div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
