#!/usr/bin/env bash
# Captures des vrais écrans pour le README et le site, puis leurs maquettes, en anglais et en français :
#   - Android : Robolectric + Roborazzi (ScreenshotTest), sans téléphone ;
#   - bureau : l'interface Svelte dans Chromium, avec un faux cœur (desktop/scripts/screenshots.mjs) ;
#   - maquettes : téléphones, fenêtres et en-tête (desktop/scripts/mockups.mjs).
# Sortie : docs/assets/shots/<langue>/ et docs/assets/mockups/<langue>/.
#
#   scripts/screenshots.sh [en] [fr]      (par défaut : les deux)
#
# Chromium : celui de Playwright (npx playwright install chromium) ou CHROMIUM=/chemin/vers/chrome.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
LANGS=("$@")
[ ${#LANGS[@]} -eq 0 ] && LANGS=(en fr)

echo "==> Cœur pour les tests JVM (captures Android)"
(cd "$ROOT/core" && cargo build -q -p dicto-ffi --features whisper,sherpa-shared)

echo "==> Interface de bureau"
(cd "$ROOT/desktop" && npm run build > /dev/null)

for lang in "${LANGS[@]}"; do
  SHOTS="$ROOT/docs/assets/shots/$lang"
  mkdir -p "$SHOTS"

  echo "==> Android ($lang)"
  (cd "$ROOT/android" && ./gradlew --no-daemon -q :app:testDebugUnitTest --tests '*ScreenshotTest*' "-Pdicto.screenshotLang=$lang")
  from="$ROOT/android/app/build/screenshots/$lang"
  cp "$from/01-dictee.png" "$SHOTS/android-dictee.png"
  cp "$from/02-modeles.png" "$SHOTS/android-modeles.png"
  cp "$from/02c-modeles-en-ligne.png" "$SHOTS/android-en-ligne.png"
  cp "$from/03-declencheurs.png" "$SHOTS/android-declencheurs.png"
  cp "$from/04-historique.png" "$SHOTS/android-historique.png"
  cp "$from/06-panneau-et-clavier.png" "$SHOTS/android-clavier.png"
  cp "$from/08-fournisseurs.png" "$SHOTS/android-retouche.png"

  echo "==> Bureau ($lang)"
  (cd "$ROOT/desktop" && node scripts/screenshots.mjs "$SHOTS" --lang "$lang")

  echo "==> Maquettes ($lang)"
  (cd "$ROOT/desktop" && node scripts/mockups.mjs --lang "$lang")
done
