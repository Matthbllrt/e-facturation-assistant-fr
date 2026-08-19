"""Point d'entree de Instagram Evidence Recovery."""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Instagram Evidence Recovery — outil local de recuperation de preuves."
    )
    parser.add_argument("--host", default="127.0.0.1", help="Adresse d'ecoute (locale par defaut).")
    parser.add_argument("--port", type=int, default=8734, help="Port d'ecoute.")
    parser.add_argument("--no-browser", action="store_true", help="Ne pas ouvrir le navigateur.")
    parser.add_argument("--data-dir", default=None, help="Dossier des enquetes (cases/ par defaut).")
    args = parser.parse_args()

    if args.data_dir:
        os.environ["IER_DATA_DIR"] = args.data_dir

    from app.main import serve

    serve(host=args.host, port=args.port, open_browser=not args.no_browser)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
