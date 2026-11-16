<script lang="ts">
  // Pastille d'enregistrement : petite fenêtre toujours visible, sans focus (le texte part
  // dans l'app active). Un clic sur la croix annule la dictée.
  import { onDestroy, onMount } from "svelte";
  import { api, formatDuration } from "./lib/api";
  import { app } from "./lib/store.svelte";
  import { t } from "./lib/i18n.svelte";
  import Waveform from "./lib/Waveform.svelte";

  onMount(() => app.init());

  let now = $state(Date.now());
  const timer = setInterval(() => (now = Date.now()), 250);
  onDestroy(() => clearInterval(timer));

  const snap = $derived(app.snapshot);
  const label = $derived(
    snap.active
      ? snap.startedAt
        ? formatDuration(now - snap.startedAt)
        : "00:00"
      : snap.pending > 0
        ? t("Transcription…")
        : "",
  );
</script>

<div class="pill" class:busy={!snap.active}>
  <span class="mic" class:idle={!snap.active}>
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#FFFCF8" stroke-width="2.6" stroke-linecap="round" aria-hidden="true"
      ><rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0 0 14 0" /></svg
    >
  </span>
  {#if snap.active}
    <Waveform bars={10} height={20} />
  {/if}
  <span class="time">{label}</span>
  {#if snap.active}
    <button aria-label={t("Annuler la dictée")} onclick={() => api.cancel()}><kbd>{t("Échap")}</kbd></button>
  {/if}
</div>

<style>
  :global(html),
  :global(body.pill-body) {
    height: 100%;
    overflow: hidden;
  }

  :global(body.pill-body) {
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .pill {
    display: inline-flex;
    align-items: center;
    gap: 10px;
    height: 46px;
    margin: 9px;
    padding: 0 10px 0 8px;
    border-radius: 23px;
    background: var(--overlay);
    box-shadow: var(--shadow-pop);
    color: var(--overlay-ink);
  }

  .mic {
    width: 30px;
    height: 30px;
    border-radius: 15px;
    background: var(--rec);
    display: flex;
    align-items: center;
    justify-content: center;
    flex: none;
  }

  .mic.idle {
    background: var(--accent-fill);
  }

  .time {
    font-size: 13px;
    font-weight: 700;
    white-space: nowrap;
  }

  button {
    border: none;
    background: transparent;
    padding: 0;
    display: flex;
  }

  kbd {
    font-size: 11px;
    padding: 2px 6px;
    background: var(--overlay-line);
    color: var(--overlay-muted);
    box-shadow: none;
  }
</style>
