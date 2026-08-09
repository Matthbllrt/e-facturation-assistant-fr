import {
  AbsoluteFill,
  Easing,
  Interactive,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export const SceneConfidentialite: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  return (
    <AbsoluteFill
      name="Scène — Confidentialité"
      style={{ backgroundColor: "#0A1628" }}
    >
      <Interactive.Div
        name="Halo"
        style={{
          position: "absolute",
          inset: 0,
          background:
            "radial-gradient(circle at 50% 42%, #0E3A34 0%, #0A1628 62%)",
        }}
      />
      <AbsoluteFill
        name="Contenu"
        style={{
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "center",
          padding: 150,
        }}
      >
        <Interactive.Div
          name="Sur-titre"
          style={{
            fontFamily: "Inter",
            fontSize: 32,
            fontWeight: 700,
            letterSpacing: 8,
            color: "#4ADE80",
            marginBottom: 34,
            opacity: interpolate(frame, [0, 0.7 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          CONFIDENTIALITÉ
        </Interactive.Div>

        <Interactive.Div
          name="Titre"
          style={{
            fontFamily: "Inter",
            fontSize: 132,
            fontWeight: 800,
            letterSpacing: -3,
            color: "#F2F6FB",
            textAlign: "center",
            marginBottom: 64,
            scale: interpolate(frame, [0.2 * fps, 1.4 * fps], [0.88, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.spring({ damping: 200 }),
              output: "perceptual-scale",
            }),
            opacity: interpolate(frame, [0.2 * fps, 1 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          100 % hors ligne
        </Interactive.Div>

        <Interactive.Div
          name="Rangée de garanties"
          style={{
            display: "flex",
            flexWrap: "wrap",
            justifyContent: "center",
            gap: 24,
            width: 1560,
          }}
        >
          <Interactive.Div
            name="Garantie — Compte"
            style={{
              fontFamily: "Inter",
              fontSize: 44,
              fontWeight: 600,
              color: "#BBF7D0",
              backgroundColor: "#0C2A20",
              border: "2px solid #1F5F45",
              borderRadius: 999,
              padding: "26px 48px",
              opacity: interpolate(frame, [1.2 * fps, 1.7 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            Aucun compte
          </Interactive.Div>

          <Interactive.Div
            name="Garantie — Serveur"
            style={{
              fontFamily: "Inter",
              fontSize: 44,
              fontWeight: 600,
              color: "#BBF7D0",
              backgroundColor: "#0C2A20",
              border: "2px solid #1F5F45",
              borderRadius: 999,
              padding: "26px 48px",
              opacity: interpolate(frame, [1.4 * fps, 1.9 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            Aucun serveur
          </Interactive.Div>

          <Interactive.Div
            name="Garantie — Analytics"
            style={{
              fontFamily: "Inter",
              fontSize: 44,
              fontWeight: 600,
              color: "#BBF7D0",
              backgroundColor: "#0C2A20",
              border: "2px solid #1F5F45",
              borderRadius: 999,
              padding: "26px 48px",
              opacity: interpolate(frame, [1.6 * fps, 2.1 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            Aucun analytics
          </Interactive.Div>

          <Interactive.Div
            name="Garantie — Installation"
            style={{
              fontFamily: "Inter",
              fontSize: 44,
              fontWeight: 600,
              color: "#BBF7D0",
              backgroundColor: "#0C2A20",
              border: "2px solid #1F5F45",
              borderRadius: 999,
              padding: "26px 48px",
              opacity: interpolate(frame, [1.8 * fps, 2.3 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            Aucune installation
          </Interactive.Div>
        </Interactive.Div>

        <Interactive.Div
          name="Explication"
          style={{
            fontFamily: "Inter",
            fontSize: 50,
            fontWeight: 400,
            lineHeight: 1.45,
            color: "#93A9C4",
            textAlign: "center",
            maxWidth: 1420,
            marginTop: 66,
            opacity: interpolate(frame, [2.6 * fps, 3.4 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          Vos réponses restent dans votre navigateur. Vous exportez votre
          progression dans un fichier JSON qui vous appartient.
        </Interactive.Div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
