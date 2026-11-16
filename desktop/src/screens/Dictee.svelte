<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { api, formatDuration, MODE_LABELS, shortcutKeys, type ListenMode, type PresetView } from "../lib/api";
  import { app, type Chunk } from "../lib/store.svelte";
  import { t, tr } from "../lib/i18n.svelte";
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
        ? t("En écoute · %1", elapsed)
        : t("Je t'écoute · %1", elapsed)
      : snap.pending > 0
        ? t("Transcription…")
        : null,
  );

  /** Les phrases groupées en paragraphes (une longue pause en ouvre un nouveau). */
  const paragraphs = $derived.by(() => {
    const out: Chunk[][] = [];
    for (const c of app.transcript) {
      if (out.length === 0 || c.newParagraph) out.push([c]);
      else out[out.length - 1].push(c);
    }
    return out;
  });
  const fullText = $derived(paragraphs.map((p) => p.map((c) => c.text).join(" ")).join("\n\n"));

  async function copy() {
    try {
      await api.copyText(fullText);
      app.show(t("Texte copié."));
    } catch (e) {
      app.show(String(e));
    }
  }

  // « Reformuler » : retouche tout le texte avec un préréglage (fournisseur réglé dans Réglages).
  let presets = $state<PresetView[]>([]);
  let preset = $state(app.settings?.refinePreset ?? "clean");
  let refining = $state(false);
  onMount(async () => (presets = await api.refinePresets()));

  async function refine() {
    refining = true;
    try {
      const text = await api.refineText(preset, fullText);
      app.transcript = [{ text, written: false }];
    } catch (e) {
      app.show(String(e));
    } finally {
      refining = false;
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
      <h1>{t("Bonjour")}</h1>
      <p class="lead">
        {t("Parle, j'écris. Avec un raccourci, le texte part dans la fenêtre active ; avec le bouton ci-dessous, il reste ici.")}
      </p>
    </div>
    <div role="radiogroup" aria-label={t("Mode d'écoute")} class="modes">
      {#each MODES as m (m)}
        <button
          role="radio"
          aria-checked={mode === m}
          class:on={mode === m}
          disabled={snap.active}
          onclick={() => app.update({ mode: m })}>{t(MODE_LABELS[m])}</button
        >
      {/each}
    </div>
  </header>

  <section class="transcript card" aria-label={t("Transcription")}>
    <div class="head">
      <span class="caption">{t("Transcription")}</span>
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
            ? t("Touche le micro, ou utilise ton raccourci depuis n'importe quelle app.")
            : t("Choisis d'abord un modèle dans Modèles.")}
        </p>
      {/if}
      {#each paragraphs as para, i (i)}
        <p>{#each para as chunk, j (j)}{j > 0 ? " " : ""}<span title={chunk.written ? t("Écrit dans la fenêtre active") : undefined}>{chunk.text}</span>{/each}</p>
      {/each}
      {#if snap.partial}<p class="partial">{snap.partial}</p>{/if}
    </div>
    <div class="actions">
      <button class="pill-btn" disabled={!fullText} onclick={copy}>{t("Copier")}</button>
      {#if app.settings?.refineProvider}
        <label class="refine">
          <span class="sr-only">{t("Consigne de retouche")}</span>
          <select bind:value={preset} disabled={refining}>
            {#each presets as p (p.id)}<option value={p.id}>{tr(p.name)}</option>{/each}
            {#if app.settings.refineCustom}<option value="custom">{t("Personnalisée")}</option>{/if}
          </select>
        </label>
        <button class="pill-btn tonal" disabled={!fullText || refining} onclick={refine}>
          {refining ? t("Retouche…") : t("Reformuler")}
        </button>
      {/if}
      <button class="pill-btn ghost end" disabled={!fullText} onclick={() => (app.transcript = [])}>{t("Effacer")}</button>
    </div>
  </section>

  <div class="bar">
    <button
      class="mic"
      class:active={snap.active}
      aria-label={snap.active ? (mode === "hold" ? t("Relâche pour transcrire") : t("Arrêter la dictée")) : t("Dicter")}
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
        {t("Raccourci")}
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
    background: var(--accent-fill);
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
    margin: 0 0 14px;
    white-space: pre-wrap;
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

  .refine select {
    height: 40px;
    border-radius: 20px;
    border: 1px solid var(--border);
    background: var(--surface);
    padding: 0 12px;
    font-size: 14px;
    font-weight: 600;
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
    background: var(--accent-fill);
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
