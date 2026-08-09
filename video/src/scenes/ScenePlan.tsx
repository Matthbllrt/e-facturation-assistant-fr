import {
  AbsoluteFill,
  Easing,
  Interactive,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export const ScenePlan: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  return (
    <AbsoluteFill name="Scène — Plan" style={{ backgroundColor: "#0A1628" }}>
      <Interactive.Div
        name="Halo"
        style={{
          position: "absolute",
          inset: 0,
          background:
            "radial-gradient(circle at 30% 45%, #16365E 0%, #0A1628 62%)",
        }}
      />
      <AbsoluteFill
        name="Contenu"
        style={{
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "center",
          padding: 110,
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
          ÉTAPE 3 — VOTRE PLAN
        </Interactive.Div>

        <Interactive.Div
          name="Titre"
          style={{
            fontFamily: "Inter",
            fontSize: 72,
            fontWeight: 800,
            letterSpacing: -1.5,
            color: "#F2F6FB",
            textAlign: "center",
            marginBottom: 48,
            opacity: interpolate(frame, [0.25 * fps, 1 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          Trois priorités, puis la checklist
        </Interactive.Div>

        <Interactive.Div
          name="Liste des priorités"
          style={{
            display: "flex",
            flexDirection: "column",
            gap: 22,
            width: 1400,
          }}
        >
          <Interactive.Div
            name="Priorité P0"
            style={{
              display: "flex",
              alignItems: "center",
              gap: 36,
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 24,
              padding: "30px 40px",
              opacity: interpolate(frame, [0.9 * fps, 1.5 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              translate: interpolate(
                frame,
                [0.9 * fps, 1.5 * fps],
                ["-34px 0px", "0px 0px"],
                {
                  extrapolateLeft: "clamp",
                  extrapolateRight: "clamp",
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                },
              ),
            }}
          >
            <Interactive.Div
              name="Étiquette P0"
              style={{
                fontFamily: "Inter",
                fontSize: 34,
                fontWeight: 800,
                color: "#FDBA74",
                backgroundColor: "#2A1708",
                border: "2px solid #7C4A16",
                borderRadius: 14,
                padding: "14px 26px",
              }}
            >
              P0
            </Interactive.Div>
            <Interactive.Div
              name="Action P0"
              style={{
                fontFamily: "Inter",
                fontSize: 44,
                fontWeight: 600,
                color: "#F2F6FB",
              }}
            >
              Vérifier votre canal de réception
            </Interactive.Div>
          </Interactive.Div>

          <Interactive.Div
            name="Priorité P1"
            style={{
              display: "flex",
              alignItems: "center",
              gap: 36,
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 24,
              padding: "30px 40px",
              opacity: interpolate(frame, [1.2 * fps, 1.8 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              translate: interpolate(
                frame,
                [1.2 * fps, 1.8 * fps],
                ["-34px 0px", "0px 0px"],
                {
                  extrapolateLeft: "clamp",
                  extrapolateRight: "clamp",
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                },
              ),
            }}
          >
            <Interactive.Div
              name="Étiquette P1"
              style={{
                fontFamily: "Inter",
                fontSize: 34,
                fontWeight: 800,
                color: "#FDE68A",
                backgroundColor: "#241F06",
                border: "2px solid #6F5F14",
                borderRadius: 14,
                padding: "14px 26px",
              }}
            >
              P1
            </Interactive.Div>
            <Interactive.Div
              name="Action P1"
              style={{
                fontFamily: "Inter",
                fontSize: 44,
                fontWeight: 600,
                color: "#F2F6FB",
              }}
            >
              Choisir une plateforme agréée
            </Interactive.Div>
          </Interactive.Div>

          <Interactive.Div
            name="Priorité P2"
            style={{
              display: "flex",
              alignItems: "center",
              gap: 36,
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 24,
              padding: "30px 40px",
              opacity: interpolate(frame, [1.5 * fps, 2.1 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              translate: interpolate(
                frame,
                [1.5 * fps, 2.1 * fps],
                ["-34px 0px", "0px 0px"],
                {
                  extrapolateLeft: "clamp",
                  extrapolateRight: "clamp",
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                },
              ),
            }}
          >
            <Interactive.Div
              name="Étiquette P2"
              style={{
                fontFamily: "Inter",
                fontSize: 34,
                fontWeight: 800,
                color: "#7DD3FC",
                backgroundColor: "#08243A",
                border: "2px solid #175B84",
                borderRadius: 14,
                padding: "14px 26px",
              }}
            >
              P2
            </Interactive.Div>
            <Interactive.Div
              name="Action P2"
              style={{
                fontFamily: "Inter",
                fontSize: 44,
                fontWeight: 600,
                color: "#F2F6FB",
              }}
            >
              Compléter les données de vos clients
            </Interactive.Div>
          </Interactive.Div>
        </Interactive.Div>

        <Interactive.Div
          name="Note"
          style={{
            fontFamily: "Inter",
            fontSize: 38,
            fontWeight: 400,
            color: "#93A9C4",
            textAlign: "center",
            marginTop: 44,
            opacity: interpolate(frame, [2.4 * fps, 3.2 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          Puis une checklist détaillée, à suivre dans le tableur compagnon.
        </Interactive.Div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
