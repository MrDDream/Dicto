// État partagé de l'interface, alimenté par les événements du cœur.
import { api, events, type Settings, type Snapshot, type UpdateView } from "./api";
import { i18n, tr } from "./i18n.svelte";

export interface Chunk {
  text: string;
  written: boolean;
}

class AppState {
  snapshot = $state<Snapshot>({
    state: "idle",
    active: false,
    mode: "pushToTalk",
    pending: 0,
    partial: "",
    engine: { kind: "none", label: "Aucun modèle", detail: null },
    startedAt: null,
  });
  settings = $state<Settings | null>(null);
  /** Phrases dictées depuis l'ouverture, affichées dans Dictée. */
  transcript = $state<Chunk[]>([]);
  level = $state(0);
  message = $state<string | null>(null);
  /** Mises à jour (la fenêtre s'ouvre quand une version est proposée, ou pour la question du début). */
  updates = $state<UpdateView | null>(null);
  #messageTimer: ReturnType<typeof setTimeout> | undefined;

  async init() {
    await Promise.all([
      events.snapshot((s) => (this.snapshot = s)),
      events.level((l) => (this.level = l)),
      events.result((r) => {
        if (r.text.trim()) this.transcript.push({ text: r.text, written: r.written });
      }),
      events.message((m) => this.show(m)),
      events.update((u) => (this.updates = u)),
      // Réglages changés dans l'autre fenêtre (la pastille suit la langue et le thème).
      events.settings((s) => {
        this.settings = s;
        this.applyLook(s);
      }),
    ]);
    this.snapshot = await api.snapshot();
    this.settings = await api.getSettings();
    this.applyLook(this.settings);
    this.updates = await api.updateState();
  }

  /** Langue et thème de l'interface ; la langue effective part aussi au cœur (menu de l'icône, fenêtres). */
  applyLook(s: Settings) {
    const before = i18n.lang;
    i18n.pref = s.uiLanguage ?? "auto";
    document.documentElement.lang = i18n.lang;
    document.documentElement.dataset.theme = s.theme ?? "auto";
    if (this.#localeSent !== i18n.lang || before !== i18n.lang) {
      this.#localeSent = i18n.lang;
      api.setUiLocale(i18n.lang).catch(() => {});
    }
  }
  #localeSent: string | null = null;

  /** Message à l'écran ; ceux du cœur sont traduits s'ils sont connus. */
  show(message: string) {
    message = tr(message);
    this.message = message;
    clearTimeout(this.#messageTimer);
    this.#messageTimer = setTimeout(() => (this.message = null), 6000);
  }

  /** Enregistre les réglages modifiés ; les erreurs de raccourcis s'affichent. */
  async update(patch: Partial<Settings>) {
    if (!this.settings) return;
    const next = { ...this.settings, ...patch };
    this.settings = next;
    this.applyLook(next);
    const errors = await api.setSettings(next);
    if (errors.length) this.show(errors.join("\n"));
  }
}

export const app = new AppState();
