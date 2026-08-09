import {
  AbsoluteFill,
  Easing,
  Interactive,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export const SceneStatuts: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  return (
    <AbsoluteFill name="Scène — Statuts" style={{ backgroundColor: "#0A1628" }}>
      <Interactive.Div
        name="Halo"
        style={{
          position: "absolute",
          inset: 0,
          background:
            "radial-gradient(circle at 50% 50%, #16365E 0%, #0A1628 64%)",
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
            color: "#5FA8FF",
            marginBottom: 30,
            opacity: interpolate(frame, [0, 0.7 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          CINQ STATUTS EXPLICITES
        </Interactive.Div>

        <Interactive.Div
          name="Titre"
          style={{
            fontFamily: "Inter",
            fontSize: 84,
            fontWeight: 800,
            letterSpacing: -1.5,
            color: "#F2F6FB",
            textAlign: "center",
            marginBottom: 76,
            opacity: interpolate(frame, [0.25 * fps, 1 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          Le moteur ne devine jamais
        </Interactive.Div>

        <Interactive.Div
          name="Rangée de statuts"
          style={{
            display: "flex",
            flexWrap: "wrap",
            justifyContent: "center",
            gap: 26,
            width: 1280,
          }}
        >
          <Interactive.Div
            name="Statut — Obligatoire"
            style={{
              fontFamily: "Inter",
              fontSize: 46,
              fontWeight: 700,
              color: "#FDBA74",
              backgroundColor: "#2A1708",
              border: "2px solid #7C4A16",
              borderRadius: 999,
              padding: "28px 52px",
              opacity: interpolate(frame, [0.9 * fps, 1.4 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              scale: interpolate(frame, [0.9 * fps, 1.6 * fps], [0.85, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.spring({ damping: 200 }),
                output: "perceptual-scale",
              }),
            }}
          >
            Obligatoire
          </Interactive.Div>

          <Interactive.Div
            name="Statut — Partiel"
            style={{
              fontFamily: "Inter",
              fontSize: 46,
              fontWeight: 700,
              color: "#FDE68A",
              backgroundColor: "#241F06",
              border: "2px solid #6F5F14",
              borderRadius: 999,
              padding: "28px 52px",
              opacity: interpolate(frame, [1.1 * fps, 1.6 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              scale: interpolate(frame, [1.1 * fps, 1.8 * fps], [0.85, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.spring({ damping: 200 }),
                output: "perceptual-scale",
              }),
            }}
          >
            Partiel
          </Interactive.Div>

          <Interactive.Div
            name="Statut — Non applicable"
            style={{
              fontFamily: "Inter",
              fontSize: 46,
              fontWeight: 700,
              color: "#CBD5E1",
              backgroundColor: "#1A2434",
              border: "2px solid #3D4C63",
              borderRadius: 999,
              padding: "28px 52px",
              opacity: interpolate(frame, [1.3 * fps, 1.8 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              scale: interpolate(frame, [1.3 * fps, 2 * fps], [0.85, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.spring({ damping: 200 }),
                output: "perceptual-scale",
              }),
            }}
          >
            Non applicable
          </Interactive.Div>

          <Interactive.Div
            name="Statut — À vérifier"
            style={{
              fontFamily: "Inter",
              fontSize: 46,
              fontWeight: 700,
              color: "#7DD3FC",
              backgroundColor: "#08243A",
              border: "2px solid #175B84",
              borderRadius: 999,
              padding: "28px 52px",
              opacity: interpolate(frame, [1.5 * fps, 2 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              scale: interpolate(frame, [1.5 * fps, 2.2 * fps], [0.85, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.spring({ damping: 200 }),
                output: "perceptual-scale",
              }),
            }}
          >
            À vérifier
          </Interactive.Div>

          <Interactive.Div
            name="Statut — Cas particulier"
            style={{
              fontFamily: "Inter",
              fontSize: 46,
              fontWeight: 700,
              color: "#C4B5FD",
              backgroundColor: "#1B1533",
              border: "2px solid #4C3A85",
              borderRadius: 999,
              padding: "28px 52px",
              opacity: interpolate(frame, [1.7 * fps, 2.2 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              scale: interpolate(frame, [1.7 * fps, 2.4 * fps], [0.85, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.spring({ damping: 200 }),
                output: "perceptual-scale",
              }),
            }}
          >
            Cas particulier
          </Interactive.Div>
        </Interactive.Div>

        <Interactive.Div
          name="Note"
          style={{
            fontFamily: "Inter",
            fontSize: 46,
            fontWeight: 400,
            lineHeight: 1.45,
            color: "#93A9C4",
            textAlign: "center",
            maxWidth: 1400,
            marginTop: 70,
            opacity: interpolate(frame, [2.6 * fps, 3.4 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          « À vérifier » et « Cas particulier » existent pour éviter une fausse
          précision.
        </Interactive.Div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
