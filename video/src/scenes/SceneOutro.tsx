import {
  AbsoluteFill,
  Easing,
  Interactive,
  interpolate,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export const SceneOutro: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  return (
    <AbsoluteFill name="Scène — Outro" style={{ backgroundColor: "#0A1628" }}>
      <Interactive.Div
        name="Halo"
        style={{
          position: "absolute",
          inset: 0,
          background:
            "radial-gradient(circle at 50% 40%, #1B4479 0%, #0A1628 66%)",
        }}
      />
      <AbsoluteFill
        name="Contenu"
        style={{
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          alignItems: "center",
          padding: 140,
        }}
      >
        <Interactive.Div
          name="Nom du produit"
          style={{
            fontFamily: "Inter",
            fontSize: 108,
            fontWeight: 800,
            letterSpacing: -2.5,
            color: "#FFFFFF",
            textAlign: "center",
            marginBottom: 26,
            opacity: interpolate(frame, [0, 0.9 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
            translate: interpolate(
              frame,
              [0, 0.9 * fps],
              ["0px 30px", "0px 0px"],
              {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              },
            ),
          }}
        >
          E-Facturation Assistant FR
        </Interactive.Div>

        <Interactive.Div
          name="Version"
          style={{
            fontFamily: "Inter",
            fontSize: 40,
            fontWeight: 600,
            letterSpacing: 5,
            color: "#5FA8FF",
            marginBottom: 68,
            opacity: interpolate(frame, [0.5 * fps, 1.2 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          VERSION 1
        </Interactive.Div>

        <Interactive.Div
          name="Livrables"
          style={{
            display: "flex",
            justifyContent: "center",
            gap: 26,
            width: 1560,
          }}
        >
          <Interactive.Div
            name="Livrable — Application"
            style={{
              flex: 1,
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 24,
              padding: "40px 34px",
              fontFamily: "Inter",
              fontSize: 44,
              fontWeight: 600,
              lineHeight: 1.3,
              color: "#F2F6FB",
              textAlign: "center",
              opacity: interpolate(frame, [1 * fps, 1.6 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              translate: interpolate(
                frame,
                [1 * fps, 1.6 * fps],
                ["0px 30px", "0px 0px"],
                {
                  extrapolateLeft: "clamp",
                  extrapolateRight: "clamp",
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                },
              ),
            }}
          >
            L&apos;application hors ligne
          </Interactive.Div>

          <Interactive.Div
            name="Livrable — Tableur"
            style={{
              flex: 1,
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 24,
              padding: "40px 34px",
              fontFamily: "Inter",
              fontSize: 44,
              fontWeight: 600,
              lineHeight: 1.3,
              color: "#F2F6FB",
              textAlign: "center",
              opacity: interpolate(frame, [1.2 * fps, 1.8 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              translate: interpolate(
                frame,
                [1.2 * fps, 1.8 * fps],
                ["0px 30px", "0px 0px"],
                {
                  extrapolateLeft: "clamp",
                  extrapolateRight: "clamp",
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                },
              ),
            }}
          >
            Le tableur compagnon
          </Interactive.Div>

          <Interactive.Div
            name="Livrable — Documentation"
            style={{
              flex: 1,
              backgroundColor: "#101F35",
              border: "2px solid #21406A",
              borderRadius: 24,
              padding: "40px 34px",
              fontFamily: "Inter",
              fontSize: 44,
              fontWeight: 600,
              lineHeight: 1.3,
              color: "#F2F6FB",
              textAlign: "center",
              opacity: interpolate(frame, [1.4 * fps, 2 * fps], [0, 1], {
                extrapolateLeft: "clamp",
                extrapolateRight: "clamp",
                easing: Easing.bezier(0.16, 1, 0.3, 1),
              }),
              translate: interpolate(
                frame,
                [1.4 * fps, 2 * fps],
                ["0px 30px", "0px 0px"],
                {
                  extrapolateLeft: "clamp",
                  extrapolateRight: "clamp",
                  easing: Easing.bezier(0.16, 1, 0.3, 1),
                },
              ),
            }}
          >
            La documentation client
          </Interactive.Div>
        </Interactive.Div>

        <Interactive.Div
          name="Avertissement"
          style={{
            fontFamily: "Inter",
            fontSize: 32,
            fontWeight: 400,
            lineHeight: 1.5,
            color: "#7E96B4",
            textAlign: "center",
            maxWidth: 1480,
            marginTop: 76,
            opacity: interpolate(frame, [2.4 * fps, 3.3 * fps], [0, 1], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
              easing: Easing.bezier(0.16, 1, 0.3, 1),
            }),
          }}
        >
          Outil pédagogique et organisationnel. Il ne remplace pas un conseil
          fiscal, comptable ou juridique, n&apos;est pas une plateforme agréée et
          ne garantit pas la conformité.
        </Interactive.Div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
