// Lien avec le cœur Rust : types, commandes et événements.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type { Brand } from "./logos";

export type ListenMode = "pushToTalk" | "continuous" | "hold";
export type Output = "type" | "paste" | "review";
export type PillPosition = "bottom" | "hidden";

export interface EngineStatus {
  kind: "none" | "loading" | "ready" | "error";
  label: string;
  detail: string | null;
}

export interface Snapshot {
  state: "idle" | "recording" | "listening" | "speaking";
  active: boolean;
  mode: ListenMode;
  pending: number;
  partial: string;
  engine: EngineStatus;
  startedAt: number | null;
}

export interface Settings {
  model: string | null;
  useGpu: boolean;
  language: string;
  voiceCommands: boolean;
  livePreview: boolean;
  output: Output;
  mode: ListenMode;
  shortcutToggle: string;
  shortcutContinuous: string;
  shortcutHold: string;
  pill: PillPosition;
  sounds: boolean;
  cloud: string | null;
  cloudConfigs: Record<string, { model: string | null; baseUrl: string | null }>;
  refineProvider: string | null;
  refineConfigs: Record<string, { model: string | null; baseUrl: string | null }>;
  refinePreset: string;
  refineCustom: string;
  autoRefine: boolean;
}

export interface ResultView {
  text: string;
  engine: string;
  durationMs: number;
  written: boolean;
}

export interface ModelView {
  id: string;
  name: string;
  description: string;
  engine: string;
  license: string;
  languages: string[];
  multilingual: boolean;
  punctuation: boolean;
  streaming: boolean;
  livePreview: boolean;
  speed: number;
  accuracy: number;
  sizeBytes: number;
  recommended: boolean;
  installed: boolean;
  downloadedBytes: number;
}

export interface DownloadEvent {
  id: string;
  downloaded: number;
  total: number;
  status: "progress" | "done" | "error" | "cancelled";
  error: string | null;
}

export interface HistoryView {
  id: number;
  createdAtMs: number;
  text: string;
  engine: string;
  durationMs: number;
}

export interface Term {
  id: number;
  term: string;
}

export interface Credit {
  name: string;
  license: string;
  credit: string;
  url: string;
}

export type ProviderKind = "transcription" | "refine";

export interface ProviderView {
  slug: string;
  label: string;
  brand: Brand;
  needsKey: boolean;
  keyHint: string;
  urlPlaceholder: string | null;
  urlHint: string | null;
  chatFilters: boolean;
  hasKey: boolean;
  model: string | null;
  baseUrl: string | null;
  configured: boolean;
  active: boolean;
}

export interface PresetView {
  id: string;
  name: string;
}

export interface FileResult {
  name: string;
  text: string;
  durationMs: number;
  engine: string;
  segments: number;
}

export const api = {
  snapshot: () => invoke<Snapshot>("snapshot"),
  getSettings: () => invoke<Settings>("get_settings"),
  setSettings: (settings: Settings) => invoke<string[]>("set_settings", { settings }),
  press: () => invoke<void>("press"),
  release: () => invoke<void>("release"),
  cancel: () => invoke<void>("cancel"),
  copyText: (text: string) => invoke<void>("copy_text", { text }),
  pauseShortcuts: (paused: boolean) => invoke<string[]>("pause_shortcuts", { paused }),
  listModels: () => invoke<ModelView[]>("list_models"),
  downloadModel: (id: string) => invoke<void>("download_model", { id }),
  cancelDownload: (id: string) => invoke<void>("cancel_download", { id }),
  deleteModel: (id: string) => invoke<void>("delete_model", { id }),
  useModel: (id: string) => invoke<void>("use_model", { id }),
  history: (query: string | null, limit: number, offset: number) =>
    invoke<HistoryView[]>("history", { query, limit, offset }),
  deleteHistory: (id: number) => invoke<void>("delete_history", { id }),
  clearHistory: () => invoke<void>("clear_history"),
  vocabulary: () => invoke<Term[]>("vocabulary"),
  addTerm: (term: string) => invoke<void>("add_term", { term }),
  removeTerm: (id: number) => invoke<void>("remove_term", { id }),
  credits: () => invoke<Credit[]>("credits"),
  providers: (kind: ProviderKind) => invoke<ProviderView[]>("providers", { kind }),
  saveProvider: (kind: ProviderKind, slug: string, key: string | null, model: string, baseUrl: string | null) =>
    invoke<Settings>("save_provider", { kind, slug, key, model, baseUrl }),
  forgetProviderKey: (kind: ProviderKind, slug: string) => invoke<Settings>("forget_provider_key", { kind, slug }),
  providerModels: (kind: ProviderKind, slug: string, key: string | null, baseUrl: string | null) =>
    invoke<string[]>("provider_models", { kind, slug, key, baseUrl }),
  setCloudEngine: (slug: string | null) => invoke<Settings>("set_cloud_engine", { slug }),
  setRefineProvider: (slug: string | null) => invoke<Settings>("set_refine_provider", { slug }),
  refinePresets: () => invoke<PresetView[]>("refine_presets"),
  refineText: (preset: string, text: string) => invoke<string>("refine_text", { preset, text }),
  pickAudioFile: () => invoke<string | null>("pick_audio_file"),
  transcribeFile: (path: string) => invoke<FileResult>("transcribe_file", { path }),
  cancelFile: () => invoke<void>("cancel_file"),
  exportFile: (format: "txt" | "srt" | "vtt" | "md") => invoke<boolean>("export_file", { format }),
};

/** Nom lisible d'un identifiant de modèle (« gpt-4o-mini-transcribe » → « GPT-4o mini transcribe »). */
export function prettyModel(id: string): string {
  const m = id.split("/").pop() ?? id;
  return m
    .split("-")
    .filter(Boolean)
    .map((p, i) => {
      if (/^gpt$/i.test(p)) return "GPT";
      if (i === 0 || /^[a-z]/.test(p) === false) return p.charAt(0).toUpperCase() + p.slice(1);
      return p;
    })
    .join(" ")
    .replace(/^GPT (\d)/, "GPT-$1");
}

export const events = {
  snapshot: (f: (s: Snapshot) => void): Promise<UnlistenFn> => listen<Snapshot>("dicto://snapshot", (e) => f(e.payload)),
  level: (f: (l: number) => void): Promise<UnlistenFn> => listen<number>("dicto://level", (e) => f(e.payload)),
  result: (f: (r: ResultView) => void): Promise<UnlistenFn> => listen<ResultView>("dicto://result", (e) => f(e.payload)),
  message: (f: (m: string) => void): Promise<UnlistenFn> => listen<string>("dicto://message", (e) => f(e.payload)),
  download: (f: (d: DownloadEvent) => void): Promise<UnlistenFn> =>
    listen<DownloadEvent>("dicto://download", (e) => f(e.payload)),
  fileProgress: (f: (fraction: number) => void): Promise<UnlistenFn> =>
    listen<number>("dicto://file-progress", (e) => f(e.payload)),
};

export const MODE_LABELS: Record<ListenMode, string> = {
  pushToTalk: "Marche/arrêt",
  continuous: "Continu",
  hold: "Maintenu",
};

/** « Ctrl+Alt+Space » → ["Ctrl", "Alt", "Espace"], pour l'affichage en touches. */
export function shortcutKeys(shortcut: string): string[] {
  const names: Record<string, string> = {
    space: "Espace",
    escape: "Échap",
    enter: "Entrée",
    backspace: "Retour",
    tab: "Tab",
    commandorcontrol: navigator.platform.startsWith("Mac") ? "⌘" : "Ctrl",
    super: navigator.platform.startsWith("Mac") ? "⌘" : "Win",
    shift: "Maj",
  };
  const arrows: Record<string, string> = { arrowup: "↑", arrowdown: "↓", arrowleft: "←", arrowright: "→" };
  return shortcut
    .split("+")
    .map((k) => k.trim())
    .filter(Boolean)
    .map((k) => {
      const low = k.toLowerCase();
      if (names[low]) return names[low];
      if (arrows[low]) return arrows[low];
      // Codes du navigateur : KeyA → A, Digit1 → 1, Numpad1 → Pavé 1.
      if (/^key[a-z]$/i.test(k)) return k.slice(3).toUpperCase();
      if (/^digit\d$/i.test(k)) return k.slice(5);
      if (/^numpad\d$/i.test(k)) return `Pavé ${k.slice(6)}`;
      return k.length === 1 ? k.toUpperCase() : k;
    });
}

/** Raccourci au format du cœur à partir d'une touche pressée ; null tant qu'il manque la touche principale. */
export function shortcutFromEvent(e: KeyboardEvent): string | null {
  const modifiers = ["Control", "Alt", "Shift", "Meta", "AltGraph", "OS"];
  if (modifiers.includes(e.key)) return null;
  const parts: string[] = [];
  if (e.ctrlKey) parts.push("Ctrl");
  if (e.altKey) parts.push("Alt");
  if (e.shiftKey) parts.push("Shift");
  if (e.metaKey) parts.push("Super");
  parts.push(e.code);
  return parts.join("+");
}

export function formatSize(bytes: number): string {
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1).replace(".", ",")} Go`;
  return `${Math.max(1, Math.round(bytes / 1e6))} Mo`;
}

export function formatDuration(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}
