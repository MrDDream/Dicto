// État partagé de l'interface, alimenté par les événements du cœur.
import { api, events, type Settings, type Snapshot } from "./api";

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
  #messageTimer: ReturnType<typeof setTimeout> | undefined;

  async init() {
    await Promise.all([
      events.snapshot((s) => (this.snapshot = s)),
      events.level((l) => (this.level = l)),
      events.result((r) => {
        if (r.text.trim()) this.transcript.push({ text: r.text, written: r.written });
      }),
      events.message((m) => this.show(m)),
    ]);
    this.snapshot = await api.snapshot();
    this.settings = await api.getSettings();
  }

  show(message: string) {
    this.message = message;
    clearTimeout(this.#messageTimer);
    this.#messageTimer = setTimeout(() => (this.message = null), 6000);
  }

  /** Enregistre les réglages modifiés ; les erreurs de raccourcis s'affichent. */
  async update(patch: Partial<Settings>) {
    if (!this.settings) return;
    const next = { ...this.settings, ...patch };
    this.settings = next;
    const errors = await api.setSettings(next);
    if (errors.length) this.show(errors.join("\n"));
  }
}

export const app = new AppState();
