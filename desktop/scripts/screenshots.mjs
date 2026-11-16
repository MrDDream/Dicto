// Captures de l'app de bureau pour le README et le site : l'interface Svelte compilée (dist/), rendue par
// Chromium avec un faux cœur (les commandes Tauri renvoient des données d'exemple).
//
//   npm run build && node scripts/screenshots.mjs [dossier de sortie] [--lang fr|en] [--theme light|dark]
//
// Chromium : celui de Playwright (npx playwright install chromium), ou CHROMIUM=/chemin/vers/chrome.
import { createServer } from "node:http";
import { readFile, mkdir } from "node:fs/promises";
import { existsSync, readFileSync } from "node:fs";
import { extname, join, resolve } from "node:path";
import { chromium } from "playwright-core";

const root = resolve(import.meta.dirname, "..");
const dist = join(root, "dist");
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : fallback;
};
const positional = args.filter((a, i) => !a.startsWith("--") && !(i > 0 && args[i - 1].startsWith("--")));
const out = resolve(positional[0] ?? join(root, "../docs/assets/shots"));
const lang = option("lang", "fr") === "en" ? "en" : "fr";
const theme = option("theme", "light") === "dark" ? "dark" : "light";
const fr = lang === "fr";
const catalog = JSON.parse(readFileSync(join(root, "../core/catalog/models.json"), "utf8"));

const TYPES = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".ttf": "font/ttf", ".svg": "image/svg+xml", ".png": "image/png" };
const server = createServer(async (req, res) => {
  const path = join(dist, decodeURIComponent(new URL(req.url, "http://x").pathname));
  try {
    const body = await readFile(path.endsWith("/") ? join(path, "index.html") : path);
    res.writeHead(200, { "content-type": TYPES[extname(path)] ?? "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404).end();
  }
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const base = `http://127.0.0.1:${server.address().port}`;

// --- Données d'exemple ---------------------------------------------------------------------

const installed = new Set(["parakeet-tdt-0.6b-v3-int8", "whisper-large-v3-turbo-q8_0"]);
const models = catalog.models
  .filter((m) => m.platforms.includes("desktop"))
  .map((m) => ({
    id: m.id,
    name: m.name,
    description: m.description,
    engine: m.engine,
    license: m.license,
    languages: m.languages,
    multilingual: m.multilingual,
    punctuation: m.punctuation,
    streaming: !!m.streaming,
    livePreview: !!m.streaming,
    speed: m.speed,
    accuracy: m.accuracy,
    sizeBytes: m.files.reduce((a, f) => a + f.size, 0),
    recommended: (m.recommended ?? []).includes("desktop"),
    installed: installed.has(m.id),
    downloadedBytes: 0,
  }));

const now = Date.now();
const settings = {
  model: "parakeet-tdt-0.6b-v3-int8",
  useGpu: true,
  language: "auto",
  voiceCommands: true,
  livePreview: true,
  paragraphPauseMs: 2500,
  spokenPunctuation: false,
  removeFillers: true,
  output: "type",
  mode: "continuous",
  shortcutToggle: "Ctrl+Alt+Space",
  shortcutContinuous: "Ctrl+Alt+C",
  shortcutHold: "Ctrl+Shift+Space",
  shortcutTranslate: "Ctrl+Alt+T",
  translateTo: "en",
  mouseBack: "hold",
  mouseForward: "none",
  pill: "bottom",
  sounds: true,
  cloud: null,
  cloudConfigs: { openai: { model: "gpt-4o-transcribe", baseUrl: null } },
  refineProvider: "anthropic",
  refineConfigs: { anthropic: { model: "claude-opus-5-5", baseUrl: null } },
  refinePreset: "clean",
  refineCustom: "",
  autoRefine: false,
  uiLanguage: lang,
  theme,
  diarize: false,
};

// Le texte d'exemple, dans la langue des captures.
const SAMPLE = fr
  ? {
      first: "Bonjour à tous, voici le compte rendu de la réunion de ce matin.",
      partial: "On commence par le planning de la semaine",
      history: [
        "Bonjour à tous, voici le compte rendu de la réunion de ce matin.",
        "Penser à rappeler le garage avant 18 h pour le contrôle technique.",
        "Liste de courses : tomates, feta, huile d'olive, citrons et basilic.",
        "Je serai en retard d'un quart d'heure, commencez sans moi.",
        "Pour la présentation de jeudi, il faut mettre à jour les chiffres du trimestre.",
      ],
    }
  : {
      first: "Hi everyone, here are the notes from this morning's meeting.",
      partial: "Let's start with this week's schedule",
      history: [
        "Hi everyone, here are the notes from this morning's meeting.",
        "Remember to call the garage before 6 pm about the MOT.",
        "Shopping list: tomatoes, feta, olive oil, lemons and basil.",
        "I'll be fifteen minutes late, start without me.",
        "For Thursday's presentation, we need to update the quarterly figures.",
      ],
    };

const provider = (slug, label, brand, extra = {}) => ({
  slug,
  label,
  brand,
  needsKey: true,
  keyHint: "",
  urlPlaceholder: null,
  urlHint: null,
  chatFilters: false,
  hasKey: false,
  model: null,
  baseUrl: null,
  configured: false,
  active: false,
  ...extra,
});

const mock = {
  settings,
  snapshot: {
    state: "listening",
    active: true,
    mode: "continuous",
    pending: 0,
    partial: SAMPLE.partial,
    engine: { kind: "ready", label: "Parakeet v3", detail: "local" },
    startedAt: now - 42_000,
  },
  models,
  providers: {
    transcription: [
      provider("openai", "OpenAI", "openai", { hasKey: true, model: "gpt-4o-transcribe", configured: true }),
      provider("mistral", "Mistral", "mistral"),
      provider("gemini", "Google Gemini", "gemini"),
      provider("xai", "Grok (xAI)", "xai"),
      provider("custom", "Mon serveur", "server", { needsKey: false, urlPlaceholder: "http://IP-du-serveur:8000/v1" }),
    ],
    refine: [
      provider("anthropic", "Claude (Anthropic)", "claude", { hasKey: true, model: "claude-opus-5-5", configured: true, active: true, chatFilters: true }),
      provider("openai", "OpenAI", "openai", { chatFilters: true }),
      provider("gemini", "Google Gemini", "gemini", { chatFilters: true }),
      provider("ollama", "Local (Ollama, LM Studio…)", "ollama", { needsKey: false, urlPlaceholder: "http://localhost:11434" }),
      provider("compat", "Autre (compatible OpenAI)", "server", { needsKey: false, urlPlaceholder: "http://localhost:1234/v1" }),
    ],
  },
  history: [
    [SAMPLE.history[0], 12, 5200],
    [SAMPLE.history[1], 95, 3900],
    [SAMPLE.history[2], 260, 4600],
    [SAMPLE.history[3], 1500, 2800],
    [SAMPLE.history[4], 3000, 6100],
  ].map(([text, minutesAgo, durationMs], i) => ({
    id: i + 1,
    createdAtMs: now - minutesAgo * 60_000,
    text,
    engine: "Parakeet v3",
    durationMs,
    pinned: i === 0,
  })),
  replacements: (fr
    ? [
        ["mon adresse", "12 rue des Lilas, 69003 Lyon"],
        ["ma signature", "Bonne journée,\nCamille"],
      ]
    : [
        ["my address", "12 Lilac Street, Bristol"],
        ["my signature", "Best,\nCamille"],
      ]
  ).map(([phrase, replacement], i) => ({ id: i + 1, phrase, replacement })),
  profiles: [
    { id: 1, appId: "slack", label: "Slack", language: "en", mode: null, refinePreset: "message" },
    { id: 2, appId: "thunderbird", label: "Thunderbird", language: null, mode: "HOLD", refinePreset: "email" },
  ],
  vocabulary: ["Dicto", "Tauri", "sherpa-onnx", "Parakeet", "Nextcloud"].map((term, i) => ({ id: i + 1, term })),
  presets: [
    ["clean", "Nettoyer"],
    ["structure", "Structurer"],
    ["email", "Mail"],
    ["notes", "Notes"],
    ["message", "Message"],
    ["formal", "Formel"],
    ["translate_en", "Traduire en anglais"],
    ["translate_fr", "Traduire en français"],
  ].map(([id, name]) => ({ id, name })),
  update: { current: "0.1.0", consent: true, available: null, downloading: false },
};

/** Le faux cœur, installé dans la page avant l'interface. */
function fakeTauri(mock) {
  const callbacks = {};
  const listeners = {};
  let next = 1;
  const handlers = {
    snapshot: () => mock.snapshot,
    get_settings: () => mock.settings,
    set_settings: () => [],
    list_models: () => mock.models,
    providers: ({ kind }) => mock.providers[kind],
    history: () => mock.history,
    vocabulary: () => mock.vocabulary,
    refine_presets: () => mock.presets,
    credits: () => [],
    update_state: () => mock.update,
    replacements: () => mock.replacements,
    profiles: () => mock.profiles,
    last_app: () => null,
    platform_info: () => ({ os: "linux", wayland: false }),
    translation_targets: () => ["en", "fr", "es", "de", "it", "pt", "nl"],
    pause_shortcuts: () => [],
    set_ui_locale: () => null,
  };
  window.__TAURI_INTERNALS__ = {
    metadata: { currentWindow: { label: "main" }, currentWebview: { windowLabel: "main", label: "main" } },
    transformCallback(cb) {
      const id = next++;
      callbacks[id] = cb;
      return id;
    },
    unregisterCallback(id) {
      delete callbacks[id];
    },
    async invoke(cmd, args) {
      if (cmd === "plugin:event|listen") {
        (listeners[args.event] ??= []).push(args.handler);
        return args.handler;
      }
      if (cmd.startsWith("plugin:")) return null;
      const h = handlers[cmd];
      return h ? structuredClone(h(args ?? {})) : null;
    },
    convertFileSrc: (p) => p,
  };
  window.__TAURI_EVENT_PLUGIN_INTERNALS__ = { unregisterListener() {} };
  window.__emit = (event, payload) => (listeners[event] ?? []).forEach((id) => callbacks[id]?.({ event, id, payload }));
}

// --- Captures ------------------------------------------------------------------------------

const executablePath =
  process.env.CHROMIUM ??
  [join(process.env.HOME, ".cache/ms-playwright/chromium-1248/chrome-linux64/chrome")].find((p) => existsSync(p));
const browser = await chromium.launch({ executablePath });
await mkdir(out, { recursive: true });

const context = await browser.newContext({
  viewport: { width: 1120, height: 760 },
  deviceScaleFactor: 2,
  locale: fr ? "fr-FR" : "en-GB",
  colorScheme: theme,
});
const page = await context.newPage();
await page.addInitScript(fakeTauri, mock);
await page.goto(`${base}/index.html`);
await page.waitForSelector("nav");
await page.evaluate(() => document.fonts.ready);

// Dictée en cours : deux phrases déjà écrites, une en train d'être dite.
await page.evaluate((first) => {
  window.__emit("dicto://result", { text: first, engine: "Parakeet v3", durationMs: 5200, written: true });
  window.__emit("dicto://level", 0.55);
}, SAMPLE.first);
await page.waitForTimeout(400);
await page.screenshot({ path: join(out, "desktop-dictee.png") });

for (const [label, file] of [
  [fr ? "Modèles" : "Models", "desktop-modeles"],
  [fr ? "Raccourcis" : "Shortcuts", "desktop-raccourcis"],
  [fr ? "Historique" : "History", "desktop-historique"],
  [fr ? "Réglages" : "Settings", "desktop-reglages"],
]) {
  await page.getByRole("button", { name: label, exact: true }).click();
  await page.waitForTimeout(400);
  await page.screenshot({ path: join(out, `${file}.png`) });
}

// La pastille, sur fond transparent.
const pill = await context.newPage();
await pill.setViewportSize({ width: 300, height: 64 });
await pill.addInitScript(fakeTauri, mock);
await pill.goto(`${base}/pill.html`);
await pill.evaluate(() => document.fonts.ready);
await pill.evaluate(() => window.__emit("dicto://level", 0.6));
await pill.waitForTimeout(300);
await pill.screenshot({ path: join(out, "desktop-pastille.png"), omitBackground: true });

await browser.close();
server.close();
console.log(`Captures dans ${out}`);
