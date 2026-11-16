<script lang="ts">
  // Transcription d'un fichier audio (message vocal, réunion, interview) avec le moteur en service,
  // puis export en texte, sous-titres (SRT, VTT) ou Markdown.
  import { onDestroy, onMount } from "svelte";
  import { api, events, formatDuration, type FileResult } from "../lib/api";
  import { app } from "../lib/store.svelte";
  import { t } from "../lib/i18n.svelte";

  let path = $state<string | null>(null);
  let progress = $state<number | null>(null);
  /** « model » : téléchargement du modèle de séparation des voix. */
  let stage = $state<"model" | "transcribe">("transcribe");
  let result = $state<FileResult | null>(null);
  let minutes = $state<string | null>(null);
  let minutesBusy = $state(false);
  const unlisten: (() => void)[] = [];

  onMount(async () => {
    unlisten.push(
      await events.fileProgress((f) => {
        if (progress !== null) progress = f;
      }),
      await events.fileStage((s) => {
        stage = s;
        if (progress !== null) progress = 0;
      }),
    );
  });
  onDestroy(() => unlisten.forEach((f) => f()));

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
    stage = "transcribe";
    result = null;
    minutes = null;
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
      if (await api.exportFile(format)) app.show(t("Transcription enregistrée (%1).", format.toUpperCase()));
    } catch (e) {
      app.show(String(e));
    }
  }

  async function copy(text: string | undefined) {
    if (!text) return;
    await api.copyText(text);
    app.show(t("Texte copié."));
  }

  /** Le texte avec « Voix N » en tête de chaque prise de parole. */
  const voiced = $derived.by(() => {
    if (!result || !result.speakers) return null;
    const turns: { speaker: number | null; startMs: number; text: string }[] = [];
    for (const l of result.lines) {
      const last = turns[turns.length - 1];
      if (last && last.speaker === l.speaker) last.text += " " + l.text.trim();
      else turns.push({ speaker: l.speaker, startMs: l.startMs, text: l.text.trim() });
    }
    return turns;
  });
  const copyText = $derived(
    voiced ? voiced.map((v) => `${v.speaker !== null ? t("Voix %1", v.speaker + 1) + " : " : ""}${v.text}`).join("\n\n") : result?.text,
  );

  async function makeMinutes() {
    minutesBusy = true;
    try {
      minutes = await api.meetingMinutes();
    } catch (e) {
      app.show(String(e));
    } finally {
      minutesBusy = false;
    }
  }
</script>

<div class="screen">
  <div>
    <h1>{t("Fichiers audio")}</h1>
    <p class="lead">{t("Un message vocal, une réunion, une interview : Dicto le transcrit avec le moteur en service.")}</p>
  </div>

  <section class="card drop">
    {#if progress !== null}
      <div class="busy">
        <div class="name">{fileName}</div>
        {#if stage === "model"}<p class="muted">{t("Téléchargement du modèle de séparation des voix (une seule fois)…")}</p>{/if}
        <div class="progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(progress * 100)}>
          <span style:width="{progress * 100}%"></span>
        </div>
        <div class="row-actions">
          <span class="muted">{Math.round(progress * 100)} %</span>
          <button class="pill-btn" onclick={() => api.cancelFile()}>{t("Annuler")}</button>
        </div>
      </div>
    {:else}
      <p class="muted">{t("MP3, M4A, WAV, FLAC, OGG… Le fichier est découpé aux silences et transcrit morceau par morceau.")}</p>
      {#if app.settings}
        <label class="inline-switch">
          <span>
            <span class="title">{t("Séparer les voix")}</span>
            <span class="muted">{t("Pour les réunions : « Voix 1 », « Voix 2 »… Le modèle (46 Mo) est téléchargé la première fois.")}</span>
          </span>
          <input type="checkbox" class="switch" checked={app.settings.diarize} onchange={(e) => app.update({ diarize: e.currentTarget.checked })} />
        </label>
      {/if}
      <div>
        <button class="pill-btn primary" onclick={pick} disabled={app.snapshot.engine.kind !== "ready"}>{t("Choisir un fichier")}</button>
      </div>
      {#if app.snapshot.engine.kind !== "ready"}
        <p class="muted">{t("Choisis d'abord un modèle ou un service dans Modèles.")}</p>
      {/if}
    {/if}
  </section>

  {#if result}
    <section class="card result" aria-labelledby="res-title">
      <div class="head">
        <h2 id="res-title">{result.name}</h2>
        <span class="muted">{formatDuration(result.durationMs)} · {result.engine}</span>
      </div>
      {#if voiced}
        <div class="text voices">
          {#each voiced as v, i (i)}
            <p>
              <span class="voice">{v.speaker !== null ? t("Voix %1", v.speaker + 1) : "—"}</span>
              <span class="time">{formatDuration(v.startMs)}</span>
              {v.text}
            </p>
          {/each}
        </div>
      {:else}
        <p class="text">{result.text}</p>
      {/if}
      <div class="row-actions">
        <button class="pill-btn" onclick={() => copy(copyText)}>{t("Copier")}</button>
        <button class="pill-btn" onclick={() => exportAs("txt")}>{t("Texte (.txt)")}</button>
        <button class="pill-btn" onclick={() => exportAs("srt")} disabled={!result.segments}>{t("Sous-titres (.srt)")}</button>
        <button class="pill-btn" onclick={() => exportAs("vtt")} disabled={!result.segments}>{t("Sous-titres (.vtt)")}</button>
        <button class="pill-btn" onclick={() => exportAs("md")}>Markdown (.md)</button>
        {#if app.settings?.refineProvider}
          <button class="pill-btn tonal" disabled={minutesBusy} onclick={makeMinutes}>{minutesBusy ? t("Rédaction…") : t("Compte rendu")}</button>
        {/if}
      </div>
    </section>
  {/if}

  {#if minutes}
    <section class="card result" aria-labelledby="cr-title">
      <h2 id="cr-title">{t("Compte rendu")}</h2>
      <p class="text">{minutes}</p>
      <div class="row-actions">
        <button class="pill-btn" onclick={() => copy(minutes ?? "")}>{t("Copier")}</button>
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
    background: var(--accent-fill);
    transition: width 0.3s;
  }

  .inline-switch {
    display: flex;
    align-items: center;
    gap: 14px;
    cursor: pointer;
    padding: 12px 14px;
    border-radius: 16px;
    background: var(--bg);
  }

  .inline-switch > span {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .title {
    font-size: 14px;
    font-weight: 700;
  }

  .voices p {
    margin: 0 0 12px;
  }

  .voice {
    font-weight: 700;
    color: var(--accent);
    margin-right: 6px;
  }

  .time {
    font-family: var(--mono);
    font-size: 12px;
    color: var(--muted);
    margin-right: 8px;
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
