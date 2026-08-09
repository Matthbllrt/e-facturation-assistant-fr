import {
  AbsoluteFill,
  Easing,
  Interactive,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export const SceneSources: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  return (
    <AbsoluteFill name="Scène — Sources" style={{ backgroundColor: "#0A1628" }}>
      <Interactive.Div
        name="Halo"
        style={{
          position: "absolute",
          inset: 0,
          background:
            "radial-gradient(circle at 72% 50%, #16365E 0%, #0A1628 62%)",
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
          TRAÇABILITÉ
        </Interactive.Div>

        <Interactive.Div
          name="Titre"
          style={{
            fontFamily: "Inter",
            fontSize: 68,
            fontWeight: 800,
            letterSpacing: -1.5,
            color: "#F2F6FB",
            textAlign: "center",
            marginBottom: 46,
            opacity: interpolate(frame, [0.25 * fps, 1 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          Chaque conclusion cite sa source officielle
        </Interactive.Div>

        <Interactive.Div
          name="Liste des sources"
          style={{
            display: "flex",
            flexDirection: "column",
            gap: 20,
            width: 1520,
          }}
        >
          <Interactive.Div
            name="Source — Calendrier"
            style={{
              display: "flex",
              flexDirection: "column",
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 22,
              padding: "28px 40px",
              opacity: interpolate(frame, [0.9 * fps, 1.4 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            <Interactive.Div
              name="Organisme — Calendrier"
              style={{
                fontFamily: "Inter",
                fontSize: 26,
                fontWeight: 700,
                letterSpacing: 4,
                color: "#5FA8FF",
                marginBottom: 10,
              }}
            >
              DGFIP · IMPOTS.GOUV.FR
            </Interactive.Div>
            <Interactive.Div
              name="Titre — Calendrier"
              style={{
                fontFamily: "Inter",
                fontSize: 42,
                fontWeight: 600,
                color: "#F2F6FB",
              }}
            >
              À partir de quand suis-je concerné par la réforme ?
            </Interactive.Div>
          </Interactive.Div>

          <Interactive.Div
            name="Source — Plateformes"
            style={{
              display: "flex",
              flexDirection: "column",
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 22,
              padding: "28px 40px",
              opacity: interpolate(frame, [1.2 * fps, 1.7 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            <Interactive.Div
              name="Organisme — Plateformes"
              style={{
                fontFamily: "Inter",
                fontSize: 26,
                fontWeight: 700,
                letterSpacing: 4,
                color: "#5FA8FF",
                marginBottom: 10,
              }}
            >
              DGFIP · IMPOTS.GOUV.FR
            </Interactive.Div>
            <Interactive.Div
              name="Titre — Plateformes"
              style={{
                fontFamily: "Inter",
                fontSize: 42,
                fontWeight: 600,
                color: "#F2F6FB",
              }}
            >
              La liste officielle des plateformes agréées
            </Interactive.Div>
          </Interactive.Div>

          <Interactive.Div
            name="Source — Franchise"
            style={{
              display: "flex",
              flexDirection: "column",
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 22,
              padding: "28px 40px",
              opacity: interpolate(frame, [1.5 * fps, 2 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            <Interactive.Div
              name="Organisme — Franchise"
              style={{
                fontFamily: "Inter",
                fontSize: 26,
                fontWeight: 700,
                letterSpacing: 4,
                color: "#5FA8FF",
                marginBottom: 10,
              }}
            >
              DGFIP · IMPOTS.GOUV.FR
            </Interactive.Div>
            <Interactive.Div
              name="Titre — Franchise"
              style={{
                fontFamily: "Inter",
                fontSize: 42,
                fontWeight: 600,
                color: "#F2F6FB",
              }}
            >
              Micro-entrepreneur ou franchise : suis-je concerné ?
            </Interactive.Div>
          </Interactive.Div>
        </Interactive.Div>

        <Interactive.Div
          name="Date de vérification"
          style={{
            fontFamily: "Inter",
            fontSize: 40,
            fontWeight: 600,
            color: "#93A9C4",
            textAlign: "center",
            marginTop: 44,
            opacity: interpolate(frame, [2.3 * fps, 3 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          Informations réglementaires vérifiées le 08/08/2026.
        </Interactive.Div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
