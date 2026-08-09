import {
  AbsoluteFill,
  Easing,
  Interactive,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export const SceneVolets: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  return (
    <AbsoluteFill name="Scène — Volets" style={{ backgroundColor: "#0A1628" }}>
      <Interactive.Div
        name="Halo"
        style={{
          position: "absolute",
          inset: 0,
          background:
            "radial-gradient(circle at 50% 30%, #16365E 0%, #0A1628 62%)",
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
          ÉTAPE 2 — VOTRE RÉSULTAT
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
            marginBottom: 72,
            opacity: interpolate(frame, [0.25 * fps, 1 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          Quatre volets analysés séparément
        </Interactive.Div>

        <Interactive.Div
          name="Grille des volets"
          style={{
            display: "flex",
            flexWrap: "wrap",
            justifyContent: "center",
            gap: 32,
            width: 1560,
          }}
        >
          <Interactive.Div
            name="Volet 01 — Réception"
            style={{
              width: 748,
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 26,
              padding: 44,
              opacity: interpolate(frame, [0.9 * fps, 1.5 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              translate: interpolate(
                frame,
                [0.9 * fps, 1.5 * fps],
                ["0px 34px", "0px 0px"],
                {
                  extrapolateLeft: "clamp",
                  extrapolateRight: "clamp",
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                },
              ),
            }}
          >
            <Interactive.Div
              name="Numéro 01"
              style={{
                fontFamily: "Inter",
                fontSize: 30,
                fontWeight: 800,
                letterSpacing: 3,
                color: "#5FA8FF",
                marginBottom: 16,
              }}
            >
              01
            </Interactive.Div>
            <Interactive.Div
              name="Libellé 01"
              style={{
                fontFamily: "Inter",
                fontSize: 50,
                fontWeight: 700,
                lineHeight: 1.25,
                color: "#F2F6FB",
              }}
            >
              Réception de vos factures
            </Interactive.Div>
          </Interactive.Div>

          <Interactive.Div
            name="Volet 02 — Ventes"
            style={{
              width: 748,
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 26,
              padding: 44,
              opacity: interpolate(frame, [1.2 * fps, 1.8 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              translate: interpolate(
                frame,
                [1.2 * fps, 1.8 * fps],
                ["0px 34px", "0px 0px"],
                {
                  extrapolateLeft: "clamp",
                  extrapolateRight: "clamp",
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                },
              ),
            }}
          >
            <Interactive.Div
              name="Numéro 02"
              style={{
                fontFamily: "Inter",
                fontSize: 30,
                fontWeight: 800,
                letterSpacing: 3,
                color: "#5FA8FF",
                marginBottom: 16,
              }}
            >
              02
            </Interactive.Div>
            <Interactive.Div
              name="Libellé 02"
              style={{
                fontFamily: "Inter",
                fontSize: 50,
                fontWeight: 700,
                lineHeight: 1.25,
                color: "#F2F6FB",
              }}
            >
              Facturation électronique de vos ventes
            </Interactive.Div>
          </Interactive.Div>

          <Interactive.Div
            name="Volet 03 — Transaction"
            style={{
              width: 748,
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 26,
              padding: 44,
              opacity: interpolate(frame, [1.5 * fps, 2.1 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              translate: interpolate(
                frame,
                [1.5 * fps, 2.1 * fps],
                ["0px 34px", "0px 0px"],
                {
                  extrapolateLeft: "clamp",
                  extrapolateRight: "clamp",
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                },
              ),
            }}
          >
            <Interactive.Div
              name="Numéro 03"
              style={{
                fontFamily: "Inter",
                fontSize: 30,
                fontWeight: 800,
                letterSpacing: 3,
                color: "#5FA8FF",
                marginBottom: 16,
              }}
            >
              03
            </Interactive.Div>
            <Interactive.Div
              name="Libellé 03"
              style={{
                fontFamily: "Inter",
                fontSize: 50,
                fontWeight: 700,
                lineHeight: 1.25,
                color: "#F2F6FB",
              }}
            >
              Transmission des données de transaction
            </Interactive.Div>
          </Interactive.Div>

          <Interactive.Div
            name="Volet 04 — Paiement"
            style={{
              width: 748,
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 26,
              padding: 44,
              opacity: interpolate(frame, [1.8 * fps, 2.4 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              translate: interpolate(
                frame,
                [1.8 * fps, 2.4 * fps],
                ["0px 34px", "0px 0px"],
                {
                  extrapolateLeft: "clamp",
                  extrapolateRight: "clamp",
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                },
              ),
            }}
          >
            <Interactive.Div
              name="Numéro 04"
              style={{
                fontFamily: "Inter",
                fontSize: 30,
                fontWeight: 800,
                letterSpacing: 3,
                color: "#5FA8FF",
                marginBottom: 16,
              }}
            >
              04
            </Interactive.Div>
            <Interactive.Div
              name="Libellé 04"
              style={{
                fontFamily: "Inter",
                fontSize: 50,
                fontWeight: 700,
                lineHeight: 1.25,
                color: "#F2F6FB",
              }}
            >
              Transmission des données de paiement
            </Interactive.Div>
          </Interactive.Div>
        </Interactive.Div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
