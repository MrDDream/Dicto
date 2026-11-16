<script lang="ts">
  // Mises à jour : la question du premier lancement, puis chaque nouvelle version (notes, téléchargement, installation).
  import { onMount } from "svelte";
  import { api, events } from "./api";
  import { app } from "./store.svelte";
  import { t, tr } from "./i18n.svelte";

  let dialog = $state<HTMLDialogElement>();
  let progress = $state<number | null>(null);
  let error = $state<string | null>(null);
  let busy = $state(false);

  const u = $derived(app.updates);
  const asking = $derived(u?.consent === null);
  const release = $derived(u?.consent !== null ? u?.available ?? null : null);
  const open = $derived(asking || !!release);

  onMount(() => {
    const off = events.updateProgress((p) => (progress = p.total > 0 ? p.done / p.total : 0));
    return () => void off.then((f) => f());
  });

  $effect(() => {
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  });

  /** Le Markdown des notes en texte simple : titres, gras et puces retirés. */
  function plain(md: string): string {
    return md
      .split("\n")
      .map((l) =>
        l
          .trimEnd()
          .replace(/^#{1,6}\s*/, "")
          .replace(/^\s*[-*]\s+/, "• ")
          .replaceAll("**", "")
          .replaceAll("`", ""),
      )
      .join("\n")
      .trim();
  }

  async function consent(on: boolean) {
    app.updates = await api.updateSetConsent(on);
  }

  async function install() {
    if (!release) return;
    if (!release.installable) {
      await api.updateOpenPage();
      return;
    }
    busy = true;
    error = null;
    progress = 0;
    try {
      const msg = await api.updateInstall();
      if (msg) app.show(tr(msg));
      app.updates = await api.updateState();
    } catch (e) {
      error = tr(String(e));
    } finally {
      busy = false;
      progress = null;
    }
  }

  async function later() {
    app.updates = await api.updateLater();
  }

  async function skip() {
    app.updates = await api.updateSkip();
  }
</script>

<dialog bind:this={dialog} oncancel={(e) => { e.preventDefault(); if (!asking && !busy) later(); }} aria-labelledby="update-title">
  {#if asking}
    <div class="body">
      <h2 id="update-title">{t("Vérifier les mises à jour ?")}</h2>
      <p class="muted">
        {t("Une fois par jour, Dicto peut regarder sur GitHub si une nouvelle version est sortie et te la proposer. Rien d'autre n'est envoyé. Tu pourras changer d'avis dans Réglages › Mises à jour.")}
      </p>
      <footer>
        <span class="spacer"></span>
        <button class="pill-btn" onclick={() => consent(false)}>{t("Non merci")}</button>
        <button class="pill-btn primary" onclick={() => consent(true)}>{t("Oui, vérifier")}</button>
      </footer>
    </div>
  {:else if release}
    <div class="body">
      <h2 id="update-title">{t("Dicto %1 est disponible", release.tag)}</h2>
      <p class="muted">{t("Tu as la version %1", u?.current ?? "")}</p>
      <div class="notes">{plain(release.notes) || t("Pas de notes pour cette version.")}</div>
      {#if progress !== null}
        <progress max="1" value={progress} aria-label={t("Téléchargement")}></progress>
      {/if}
      {#if error}<p class="error">{error}</p>{/if}
      <footer>
        {#if !busy}
          <button class="pill-btn" onclick={skip}>{t("Ignorer cette version")}</button>
        {/if}
        <span class="spacer"></span>
        {#if !busy}
          <button class="pill-btn" onclick={later}>{t("Plus tard")}</button>
        {/if}
        <button class="pill-btn primary" disabled={busy} onclick={install}>
          {busy ? t("Téléchargement…") : release.installable ? t("Télécharger et installer") : t("Ouvrir la page")}
        </button>
      </footer>
    </div>
  {/if}
</dialog>

<style>
  dialog {
    width: min(560px, calc(100vw - 32px));
    max-height: calc(100vh - 48px);
    border: none;
    border-radius: 24px;
    padding: 0;
    background: var(--surface);
    color: var(--ink);
    box-shadow: var(--shadow-dialog);
  }

  dialog::backdrop {
    background: var(--backdrop);
  }

  .body {
    display: flex;
    flex-direction: column;
    gap: 14px;
    padding: 22px;
  }

  h2 {
    margin: 0;
  }

  .muted {
    margin: 0;
    font-size: 13px;
    color: var(--muted);
  }

  .notes {
    max-height: 260px;
    overflow-y: auto;
    white-space: pre-wrap;
    font-size: 14px;
    line-height: 1.5;
  }

  progress {
    width: 100%;
    accent-color: var(--accent);
  }

  .error {
    margin: 0;
    font-size: 13px;
    color: var(--on-rec-container);
  }

  footer {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  .spacer {
    flex: 1;
  }
</style>
