import {
  AbsoluteFill,
  Easing,
  Interactive,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export const SceneQuestionnaire: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  return (
    <AbsoluteFill
      name="Scène — Questionnaire"
      style={{ backgroundColor: "#0A1628" }}
    >
      <Interactive.Div
        name="Halo"
        style={{
          position: "absolute",
          inset: 0,
          background:
            "radial-gradient(circle at 70% 40%, #16365E 0%, #0A1628 62%)",
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
          ÉTAPE 1 — LE QUESTIONNAIRE
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
            marginBottom: 46,
            opacity: interpolate(frame, [0.25 * fps, 1 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          Court, et adapté à votre situation
        </Interactive.Div>

        <Interactive.Div
          name="Carte question"
          style={{
            width: 1320,
            backgroundColor: "#101F35",
            border: "2px solid #21406A",
            borderRadius: 32,
            padding: 46,
            display: "flex",
            flexDirection: "column",
            alignItems: "flex-start",
            opacity: interpolate(frame, [0.8 * fps, 1.5 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
            translate: interpolate(
              frame,
              [0.8 * fps, 1.5 * fps],
              ["0px 40px", "0px 0px"],
              {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              },
            ),
          }}
        >
          <Interactive.Div
            name="Numéro de question"
            style={{
              fontFamily: "Inter",
              fontSize: 28,
              fontWeight: 700,
              letterSpacing: 4,
              color: "#5FA8FF",
              marginBottom: 18,
            }}
          >
            Q03
          </Interactive.Div>

          <Interactive.Div
            name="Intitulé"
            style={{
              fontFamily: "Inter",
              fontSize: 54,
              fontWeight: 700,
              color: "#F2F6FB",
              marginBottom: 34,
            }}
          >
            Qui sont vos clients ?
          </Interactive.Div>

          <Interactive.Div
            name="Réponse — Pro France"
            style={{
              fontFamily: "Inter",
              fontSize: 40,
              fontWeight: 600,
              color: "#0A1628",
              backgroundColor: "#5FA8FF",
              borderRadius: 18,
              padding: "22px 36px",
              width: "100%",
              marginBottom: 16,
              opacity: interpolate(frame, [1.7 * fps, 2.2 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            Des entreprises établies en France
          </Interactive.Div>

          <Interactive.Div
            name="Réponse — Particuliers"
            style={{
              fontFamily: "Inter",
              fontSize: 40,
              fontWeight: 400,
              color: "#C4D6EA",
              backgroundColor: "#16283F",
              border: "2px solid #26456E",
              borderRadius: 18,
              padding: "22px 36px",
              width: "100%",
              marginBottom: 16,
              opacity: interpolate(frame, [2 * fps, 2.5 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            Des particuliers
          </Interactive.Div>

          <Interactive.Div
            name="Réponse — Incertitude"
            style={{
              fontFamily: "Inter",
              fontSize: 40,
              fontWeight: 400,
              color: "#C4D6EA",
              backgroundColor: "#16283F",
              border: "2px solid #26456E",
              borderRadius: 18,
              padding: "22px 36px",
              width: "100%",
              opacity: interpolate(frame, [2.3 * fps, 2.8 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
            }}
          >
            Je ne sais pas
          </Interactive.Div>
        </Interactive.Div>

        <Interactive.Div
          name="Note"
          style={{
            fontFamily: "Inter",
            fontSize: 38,
            fontWeight: 400,
            color: "#7E96B4",
            textAlign: "center",
            marginTop: 34,
            opacity: interpolate(frame, [3 * fps, 3.7 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          Seules les questions utiles à votre profil s&apos;affichent.
        </Interactive.Div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
