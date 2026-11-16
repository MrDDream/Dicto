<script lang="ts">
  // Transcription d'un fichier audio (message vocal, réunion, interview) avec le moteur en service,
  // puis export en texte, sous-titres (SRT, VTT) ou Markdown.
  import { onDestroy, onMount } from "svelte";
  import { api, events, formatDuration, type FileResult } from "../lib/api";
  import { app } from "../lib/store.svelte";

  let path = $state<string | null>(null);
  let progress = $state<number | null>(null);
  let result = $state<FileResult | null>(null);
  let unlisten: (() => void) | undefined;

  onMount(async () => {
    unlisten = await events.fileProgress((f) => {
      if (progress !== null) progress = f;
    });
  });
  onDestroy(() => unlisten?.());

  const fileName = $derived(path ? path.split(/[\\/]/).pop() : null);

  async function pick() {
    const p = await api.pickAudioFile();
    if (p) {
      path = p;
      result = null;
      await transcribe();
    }
  }

  async function transcribe() {
    if (!path) return;
    progress = 0;
    result = null;
    try {
      result = await api.transcribeFile(path);
    } catch (e) {
      app.show(String(e));
    } finally {
      progress = null;
    }
  }

  async function exportAs(format: "txt" | "srt" | "vtt" | "md") {
    try {
      if (await api.exportFile(format)) app.show(`Transcription enregistrée (${format.toUpperCase()}).`);
    } catch (e) {
      app.show(String(e));
    }
  }

  async function copy() {
    if (!result) return;
    await api.copyText(result.text);
    app.show("Texte copié.");
  }
</script>

<div class="screen">
  <div>
    <h1>Fichiers audio</h1>
    <p class="lead">Un message vocal, une réunion, une interview : Dicto le transcrit avec le moteur en service.</p>
  </div>

  <section class="card drop">
    {#if progress !== null}
      <div class="busy">
        <div class="name">{fileName}</div>
        <div class="progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(progress * 100)}>
          <span style:width="{progress * 100}%"></span>
        </div>
        <div class="row-actions">
          <span class="muted">{Math.round(progress * 100)} %</span>
          <button class="pill-btn" onclick={() => api.cancelFile()}>Annuler</button>
        </div>
      </div>
    {:else}
      <p class="muted">MP3, M4A, WAV, FLAC, OGG… Le fichier est découpé aux silences et transcrit morceau par morceau.</p>
      <div>
        <button class="pill-btn primary" onclick={pick} disabled={app.snapshot.engine.kind !== "ready"}>Choisir un fichier</button>
      </div>
      {#if app.snapshot.engine.kind !== "ready"}
        <p class="muted">Choisis d'abord un modèle ou un service dans Modèles.</p>
      {/if}
    {/if}
  </section>

  {#if result}
    <section class="card result" aria-labelledby="res-title">
      <div class="head">
        <h2 id="res-title">{result.name}</h2>
        <span class="muted">{formatDuration(result.durationMs)} · {result.engine}</span>
      </div>
      <p class="text">{result.text}</p>
      <div class="row-actions">
        <button class="pill-btn" onclick={copy}>Copier</button>
        <button class="pill-btn" onclick={() => exportAs("txt")}>Texte (.txt)</button>
        <button class="pill-btn" onclick={() => exportAs("srt")} disabled={!result.segments}>Sous-titres (.srt)</button>
        <button class="pill-btn" onclick={() => exportAs("vtt")} disabled={!result.segments}>Sous-titres (.vtt)</button>
        <button class="pill-btn" onclick={() => exportAs("md")}>Markdown (.md)</button>
      </div>
    </section>
  {/if}
</div>

<style>
  .screen {
    display: flex;
    flex-direction: column;
    gap: 20px;
    max-width: 900px;
  }

  .drop,
  .result {
    padding: 22px;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .muted {
    margin: 0;
    font-size: 13px;
    color: var(--muted);
  }

  .busy {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .name {
    font-weight: 700;
  }

  .progress {
    height: 8px;
    border-radius: 4px;
    background: var(--sunken);
    overflow: hidden;
  }

  .progress span {
    display: block;
    height: 100%;
    background: var(--accent);
    transition: width 0.3s;
  }

  .row-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }

  .head {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: 12px;
  }

  .text {
    margin: 0;
    font-size: 16px;
    line-height: 1.6;
    white-space: pre-wrap;
    user-select: text;
    max-height: 420px;
    overflow-y: auto;
  }
</style>
