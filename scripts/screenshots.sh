#!/usr/bin/env bash
# Captures des vrais écrans pour le README et le site, puis leurs maquettes :
#   - Android : Robolectric + Roborazzi (ScreenshotTest), sans téléphone ;
#   - bureau : l'interface Svelte dans Chromium, avec un faux cœur (desktop/scripts/screenshots.mjs) ;
#   - maquettes : téléphones, fenêtres et en-tête (desktop/scripts/mockups.mjs).
# Sortie : docs/assets/shots/ et docs/assets/mockups/.
#
# Chromium : celui de Playwright (npx playwright install chromium) ou CHROMIUM=/chemin/vers/chrome.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
SHOTS="$ROOT/docs/assets/shots"
mkdir -p "$SHOTS"

echo "==> Android"
(cd "$ROOT/android" && ./gradlew --no-daemon -q :app:testDebugUnitTest --tests '*ScreenshotTest*' --rerun-tasks)
from="$ROOT/android/app/build/screenshots"
cp "$from/01-dictee.png" "$SHOTS/android-dictee.png"
cp "$from/02-modeles.png" "$SHOTS/android-modeles.png"
cp "$from/02c-modeles-en-ligne.png" "$SHOTS/android-en-ligne.png"
cp "$from/03-declencheurs.png" "$SHOTS/android-declencheurs.png"
cp "$from/04-historique.png" "$SHOTS/android-historique.png"
cp "$from/06-panneau-et-clavier.png" "$SHOTS/android-clavier.png"
cp "$from/08-fournisseurs.png" "$SHOTS/android-retouche.png"

echo "==> Bureau"
cd "$ROOT/desktop"
npm run build > /dev/null
node scripts/screenshots.mjs "$SHOTS"

echo "==> Maquettes"
node scripts/mockups.mjs
