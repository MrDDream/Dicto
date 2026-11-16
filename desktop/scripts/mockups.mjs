// Maquettes pour le README et le site : les vraies captures (docs/assets/shots/) mises dans un téléphone ou une
// fenêtre, puis composées. Écrit dans docs/assets/mockups/.
//
//   node scripts/mockups.mjs
//
// Chromium : comme scripts/screenshots.mjs (CHROMIUM=/chemin/vers/chrome sinon celui de Playwright).
import { existsSync, readFileSync } from "node:fs";
import { mkdir } from "node:fs/promises";
import { join, resolve } from "node:path";
import { chromium } from "playwright-core";

const root = resolve(import.meta.dirname, "..");
const shots = join(root, "../docs/assets/shots");
const out = join(root, "../docs/assets/mockups");
const fonts = join(root, "src/assets/fonts");

const img = (name) => `data:image/png;base64,${readFileSync(join(shots, `${name}.png`)).toString("base64")}`;
const font = (file) => `data:font/ttf;base64,${readFileSync(join(fonts, file)).toString("base64")}`;

const CSS = `
@font-face { font-family: "Bricolage Grotesque"; src: url(${font("bricolage_grotesque.ttf")}); font-weight: 200 800; }
@font-face { font-family: "Figtree"; src: url(${font("figtree.ttf")}); font-weight: 300 900; }
* { box-sizing: border-box; }
html, body { margin: 0; background: transparent; }
body { font-family: Figtree, sans-serif; color: #2b231d; }
.phone { width: 390px; padding: 11px; border-radius: 62px; background: #231d18; flex: none;
  box-shadow: inset 0 0 0 2px #3a322b, 0 30px 60px rgba(43,35,29,.22), 0 6px 16px rgba(43,35,29,.12); position: relative; }
.phone .screen { border-radius: 51px; overflow: hidden; }
.phone .top { height: 44px; }
.phone img { display: block; width: 100%; }
.phone::after { content: ""; position: absolute; top: 24px; left: 50%; width: 92px; height: 26px; margin-left: -46px;
  border-radius: 14px; background: #120e0b; }
.window { width: 1120px; border-radius: 14px; overflow: hidden; background: #fffcf8; flex: none;
  box-shadow: 0 0 0 1px rgba(43,35,29,.12), 0 34px 70px rgba(43,35,29,.22), 0 8px 20px rgba(43,35,29,.10); }
.bar { height: 38px; display: flex; align-items: center; gap: 8px; padding: 0 14px; background: #efe7dc;
  border-bottom: 1px solid #e4d9cb; font-weight: 600; font-size: 13px; color: #6f6258; }
.bar i { width: 12px; height: 12px; border-radius: 50%; display: block; }
.bar span { flex: 1; text-align: center; margin-right: 52px; }
.window img { display: block; width: 100%; }
.stage { display: flex; align-items: flex-end; }
`;

// La caméra a sa propre bande en haut (les captures commencent sous la barre d'état), de la couleur de l'écran.
const TOP = { "android-clavier": "#eef0f3" };
const phone = (name) =>
  `<div class="phone"><div class="screen"><div class="top" style="background: ${TOP[name] ?? "#f6f1ea"}"></div><img src="${img(name)}" alt=""></div></div>`;
const win = (name, scale = 1) =>
  `<div class="window" style="transform: scale(${scale}); transform-origin: top left">
     <div class="bar"><i style="background:#d9622b"></i><i style="background:#f0b44c"></i><i style="background:#3e7d4f"></i><span>Dicto</span></div>
     <img src="${img(name)}" alt="">
   </div>`;

const executablePath =
  process.env.CHROMIUM ??
  [join(process.env.HOME, ".cache/ms-playwright/chromium-1248/chrome-linux64/chrome")].find((p) => existsSync(p));
const browser = await chromium.launch({ executablePath });
await mkdir(out, { recursive: true });

async function render(file, width, height, body, { background = "transparent", scale = 2 } = {}) {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: scale });
  await page.setContent(`<!doctype html><style>${CSS} body { background: ${background}; }</style>${body}`);
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: join(out, file), omitBackground: background === "transparent" });
  await page.close();
  console.log(file);
}

// Une capture par téléphone, fond transparent (galerie du site, README).
for (const name of ["android-dictee", "android-historique", "android-declencheurs", "android-clavier", "android-en-ligne", "android-retouche"]) {
  await render(`${name}.png`, 490, 1020, `<div style="padding: 30px 50px 80px">${phone(name)}</div>`, { scale: 1.5 });
}

// Une capture par fenêtre de bureau.
for (const name of ["desktop-dictee", "desktop-modeles", "desktop-raccourcis", "desktop-historique", "desktop-reglages"]) {
  await render(`${name}.png`, 1240, 940, `<div style="padding: 40px 60px 100px">${win(name)}</div>`, { scale: 1.25 });
}

// En-tête : la fenêtre du bureau et le téléphone, sur le fond crème de Dicto.
await render(
  "hero.png",
  1400,
  900,
  `<div style="position: relative; width: 1400px; height: 900px; overflow: hidden;
       background: radial-gradient(ellipse at 70% 20%, #fbe5d8 0%, rgba(251,229,216,0) 55%), #f6f1ea">
     <div style="position: absolute; left: 60px; top: 70px">${win("desktop-dictee", 0.92)}</div>
     <div style="position: absolute; left: 1020px; top: 36px; transform: scale(.9); transform-origin: top left">${phone("android-dictee")}</div>
   </div>`,
  { background: "#f6f1ea", scale: 1.5 },
);

// Rangée de quatre téléphones pour le README.
await render(
  "android-row.png",
  1760,
  1000,
  `<div class="stage" style="gap: 36px; padding: 20px 20px 60px">
     ${["android-dictee", "android-clavier", "android-declencheurs", "android-historique"].map(phone).join("")}
   </div>`,
  { scale: 1 },
);

await browser.close();
