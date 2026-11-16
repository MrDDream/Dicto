// Icônes du bureau, dessinées d'après le canvas « Icones-Bureau » :
// - source.png (1024 px) : l'icône de l'app, d'où `tauri icon` tire .ico, .icns et PNG ;
// - tray.png / tray-listening.png : la zone de notification, au repos et en écoute.
// Les petites tailles suivent le dessin simplifié du canvas (sans les lignes de texte, trait épais).
import { Resvg } from "@resvg/resvg-js";
import { writeFileSync } from "node:fs";
import { resolve } from "node:path";

const out = resolve(import.meta.dirname, "../src-tauri/icons");

const full = (ink, fill) => `
  <rect x="36" y="14" width="28" height="48" rx="14" stroke="${ink}" stroke-width="7" fill="${fill}"/>
  <path d="M44 32H56" stroke="${ink}" stroke-width="6" stroke-linecap="round"/>
  <path d="M44 44H52" stroke="${ink}" stroke-width="6" stroke-linecap="round"/>
  <path d="M26 50A24 24 0 0 0 74 50" stroke="${ink}" stroke-width="7" stroke-linecap="round" fill="none"/>
  <path d="M50 74V86" stroke="${ink}" stroke-width="7" stroke-linecap="round"/>`;

const simple = (ink, fill) => `
  <rect x="36" y="14" width="28" height="48" rx="14" stroke="${ink}" stroke-width="9" fill="${fill}"/>
  <path d="M26 50A24 24 0 0 0 74 50" stroke="${ink}" stroke-width="9" stroke-linecap="round" fill="none"/>
  <path d="M50 74V86" stroke="${ink}" stroke-width="9" stroke-linecap="round"/>`;

// Tuile arrondie (rayon 22 % comme sur le canvas), glyphe à 70 % de la tuile.
const tile = (size, bg, glyph) => `
<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 100 100">
  <rect width="100" height="100" rx="22" fill="${bg}"/>
  <g transform="translate(15 15) scale(0.7)">${glyph}</g>
</svg>`;

const render = (svg, size, file) => {
  const png = new Resvg(svg, { fitTo: { mode: "width", value: size } }).render().asPng();
  writeFileSync(resolve(out, file), png);
  console.log(`${file} (${size} px)`);
};

render(tile(1024, "#F0B44C", full("#2B231D", "#FFFCF8")), 1024, "source.png");
// Zone de notification : tuile pleine, lisible sur barre claire comme sombre.
render(tile(64, "#F0B44C", simple("#2B231D", "#FFFCF8")), 64, "tray.png");
render(tile(64, "#D9622B", simple("#FFFCF8", "#D9622B")), 64, "tray-listening.png");
