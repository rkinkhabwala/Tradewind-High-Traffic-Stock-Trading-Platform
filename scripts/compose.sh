#!/usr/bin/env bash
# Runs `docker compose` from an ASCII-only path.
#
# Docker's BuildKit derives a session header from the build directory's name and rejects non-ASCII characters,
# so `docker compose build` fails when the checkout lives in a folder like "Tradewind — High-Traffic ...".
# This script points an ASCII-named symlink at the repository and runs compose through it. Commands that do not
# build (up without --build, ps, logs, down) work from the repository directly as well.
#
# Usage: scripts/compose.sh up -d --build
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
link="${TMPDIR:-/tmp}/tradewind-compose"
ln -sfn "$repo" "$link"
cd "$link"
exec docker compose "$@"
