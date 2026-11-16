<script lang="ts">
  import { onDestroy } from "svelte";
  import { api, formatDuration, MODE_LABELS, shortcutKeys, type ListenMode } from "../lib/api";
  import { app } from "../lib/store.svelte";
  import Waveform from "../lib/Waveform.svelte";

  const MODES: ListenMode[] = ["pushToTalk", "continuous", "hold"];

  const snap = $derived(app.snapshot);
  const mode = $derived(app.settings?.mode ?? "pushToTalk");
  const shortcut = $derived(
    mode === "continuous"
      ? app.settings?.shortcutContinuous
      : mode === "hold"
        ? app.settings?.shortcutHold
        : app.settings?.shortcutToggle,
  );

  // Chronomètre de l'écoute.
  let now = $state(Date.now());
  const timer = setInterval(() => (now = Date.now()), 500);
  onDestroy(() => clearInterval(timer));
  const elapsed = $derived(snap.startedAt ? formatDuration(now - snap.startedAt) : "");

  const status = $derived(
    snap.active
      ? snap.state === "listening"
        ? `En écoute · ${elapsed}`
        : `Je t'écoute · ${elapsed}`
      : snap.pending > 0
        ? "Transcription…"
        : null,
  );

  const fullText = $derived(app.transcript.map((c) => c.text).join(" "));

  async function copy() {
    try {
      await api.copyText(fullText);
      app.show("Texte copié.");
    } catch (e) {
      app.show(String(e));
    }
  }

  // Maintenu : on parle tant que le bouton est enfoncé. Sinon un appui démarre, un autre arrête.
  function down(e: PointerEvent) {
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    api.press();
  }
  function up() {
    if (mode === "hold") api.release();
  }
  function keyPress(e: KeyboardEvent) {
    if ((e.key === " " || e.key === "Enter") && !e.repeat) {
      e.preventDefault();
      api.press();
      if (mode === "hold") api.release();
    }
  }
</script>

<div class="screen">
  <header>
    <div class="intro">
      <h1>Bonjour</h1>
      <p class="lead">
        Parle, j'écris. Avec un raccourci, le texte part dans la fenêtre active ; avec le bouton ci-dessous, il reste
        ici.
      </p>
    </div>
    <div role="radiogroup" aria-label="Mode d'écoute" class="modes">
      {#each MODES as m (m)}
        <button
          role="radio"
          aria-checked={mode === m}
          class:on={mode === m}
          disabled={snap.active}
          onclick={() => app.update({ mode: m })}>{MODE_LABELS[m]}</button
        >
      {/each}
    </div>
  </header>

  <section class="transcript card" aria-label="Transcription">
    <div class="head">
      <span class="caption">Transcription</span>
      {#if status}
        <span class="status" class:rec={snap.active}>
          {#if snap.active}<span class="rec-dot"></span>{/if}{status}
        </span>
      {/if}
    </div>
    <div class="text" aria-live="polite">
      {#if app.transcript.length === 0 && !snap.partial}
        <p class="placeholder">
          {snap.engine.kind === "ready"
            ? "Touche le micro, ou utilise ton raccourci depuis n'importe quelle app."
            : "Choisis d'abord un modèle dans Modèles."}
        </p>
      {/if}
      {#each app.transcript as chunk, i (i)}
        <p title={chunk.written ? "Écrit dans la fenêtre active" : undefined}>{chunk.text}</p>
      {/each}
      {#if snap.partial}<p class="partial">{snap.partial}</p>{/if}
    </div>
    <div class="actions">
      <button class="pill-btn" disabled={!fullText} onclick={copy}>Copier</button>
      <button class="pill-btn ghost end" disabled={!fullText} onclick={() => (app.transcript = [])}>Effacer</button>
    </div>
  </section>

  <div class="bar">
    <button
      class="mic"
      class:active={snap.active}
      aria-label={snap.active ? (mode === "hold" ? "Relâche pour transcrire" : "Arrêter la dictée") : "Dicter"}
      onpointerdown={down}
      onpointerup={up}
      onpointercancel={up}
      onkeydown={keyPress}
    >
      {#if snap.active && mode !== "hold"}
        <svg width="20" height="20" viewBox="0 0 24 24" fill="#FFFCF8" aria-hidden="true"
          ><rect x="6" y="6" width="12" height="12" rx="3" /></svg
        >
      {:else}
        <svg
          width="24"
          height="24"
          viewBox="0 0 24 24"
          fill="none"
          stroke="#FFFCF8"
          stroke-width="2.4"
          stroke-linecap="round"
          aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0 0 14 0" /></svg
        >
      {/if}
    </button>
    <div class="wave"><Waveform bars={28} /></div>
    {#if shortcut}
      <div class="shortcut">
        Raccourci
        {#each shortcutKeys(shortcut) as k (k)}<kbd>{k}</kbd>{/each}
      </div>
    {/if}
  </div>
</div>

<style>
  .screen {
    display: flex;
    flex-direction: column;
    gap: 20px;
    min-height: 100%;
  }

  header {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    gap: 14px;
  }

  .intro {
    margin-right: auto;
    max-width: 560px;
  }

  .modes {
    padding: 4px;
    border-radius: 16px;
    background: var(--sunken);
    display: flex;
    gap: 4px;
  }

  .modes button {
    height: 36px;
    padding: 0 16px;
    border-radius: 12px;
    border: none;
    background: transparent;
    color: var(--ink-2);
    font-size: 14px;
    font-weight: 600;
  }

  .modes button.on {
    background: var(--accent);
    color: var(--on-accent);
    font-weight: 700;
  }

  .transcript {
    flex: 1;
    min-height: 320px;
    border-radius: 28px;
    padding: 28px 32px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .head {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .caption {
    font-size: 13px;
    font-weight: 600;
    color: var(--muted);
  }

  .status {
    display: flex;
    align-items: center;
    gap: 6px;
    height: 26px;
    padding: 0 10px;
    border-radius: 13px;
    background: var(--sunken);
    font-size: 12px;
    font-weight: 700;
    color: var(--ink-2);
  }

  .status.rec {
    background: var(--rec-container);
    color: var(--on-rec-container);
  }

  .rec-dot {
    width: 7px;
    height: 7px;
    border-radius: 4px;
    background: var(--rec);
  }

  .text {
    flex: 1;
    overflow-y: auto;
  }

  .text p {
    margin: 0 0 10px;
    font-size: 21px;
    line-height: 1.6;
    max-width: 68ch;
    user-select: text;
  }

  .text .partial {
    color: var(--disabled);
  }

  .text .placeholder {
    font-size: 17px;
    color: var(--muted);
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    border-top: 1px solid var(--sunken);
    padding-top: 14px;
  }

  .actions .end {
    margin-left: auto;
  }

  .bar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 20px;
    padding: 14px 22px 14px 14px;
    border-radius: 40px;
    background: var(--overlay);
    color: var(--overlay-ink);
  }

  .mic {
    width: 56px;
    height: 56px;
    border-radius: 28px;
    border: none;
    background: var(--accent);
    box-shadow: 0 0 0 6px var(--overlay-line);
    display: flex;
    align-items: center;
    justify-content: center;
    touch-action: none;
  }

  .mic.active {
    background: var(--rec);
    box-shadow: 0 0 0 6px var(--overlay-rec);
  }

  .wave {
    flex: 1;
    min-width: 160px;
  }

  .shortcut {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    color: var(--overlay-muted);
  }

  .shortcut kbd {
    background: var(--overlay-line);
    color: var(--overlay-ink);
    box-shadow: none;
  }
</style>
