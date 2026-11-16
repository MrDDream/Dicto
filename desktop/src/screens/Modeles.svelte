<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { api, events, formatSize, type ModelView } from "../lib/api";
  import BrandLogo from "../lib/BrandLogo.svelte";
  import Icon from "../lib/Icon.svelte";
  import type { Brand } from "../lib/logos";
  import ProviderList from "../lib/ProviderList.svelte";
  import { app } from "../lib/store.svelte";
  import { t, tr } from "../lib/i18n.svelte";

  let models = $state<ModelView[]>([]);
  /** Téléchargements en cours : octets reçus / total. */
  let progress = $state<Record<string, { downloaded: number; total: number }>>({});
  let unlisten: (() => void) | undefined;

  async function refresh() {
    models = await api.listModels();
  }

  onMount(async () => {
    await refresh();
    unlisten = await events.download((d) => {
      if (d.status === "progress") {
        progress[d.id] = { downloaded: d.downloaded, total: d.total };
        return;
      }
      delete progress[d.id];
      if (d.status === "error") app.show(t("Téléchargement impossible : %1", tr(d.error)));
      refresh();
    });
  });
  onDestroy(() => unlisten?.());

  async function download(m: ModelView) {
    progress[m.id] = { downloaded: m.downloadedBytes, total: m.sizeBytes };
    try {
      await api.downloadModel(m.id);
    } catch {
      // L'erreur arrive aussi par l'événement de téléchargement.
    }
  }

  async function remove(m: ModelView) {
    if (!confirm(t("Supprimer %1 (%2) ?", m.name, formatSize(m.sizeBytes)))) return;
    try {
      await api.deleteModel(m.id);
    } catch (e) {
      app.show(String(e));
    }
    refresh();
  }

  /** Qui a créé le modèle : son logo. */
  function maker(id: string): { name: string; brand: Brand | null } {
    if (id.startsWith("whisper")) return { name: "OpenAI", brand: "openai" };
    if (/^(canary|parakeet|nemotron)/.test(id)) return { name: "NVIDIA", brand: "nvidia" };
    if (id.startsWith("omnilingual")) return { name: "Meta", brand: "meta" };
    return { name: "", brand: null };
  }

  function languages(m: ModelView): string {
    if (m.multilingual || m.languages.includes("multi")) return t("multilingue");
    return m.languages.map((l) => l.toUpperCase()).join(", ");
  }

  // Un service en ligne en service remplace le modèle local.
  const current = $derived(app.settings?.cloud ? null : app.settings?.model);
  const installed = $derived(models.filter((m) => m.installed));
  const available = $derived(models.filter((m) => !m.installed));
</script>

{#snippet dots(value: number, label: string)}
  <span class="meter" aria-label={t("%1 : %2 sur 5", label, value)}>
    {label}
    {#each [1, 2, 3, 4, 5] as i (i)}<span class="pip" class:on={i <= value}></span>{/each}
  </span>
{/snippet}

{#snippet model(m: ModelView)}
  {@const mk = maker(m.id)}
  {@const p = progress[m.id]}
  {@const inUse = current === m.id}
  <div class="model">
    <div class="mark" title={mk.name}>
      {#if mk.brand}<BrandLogo brand={mk.brand} size={26} />{:else}?{/if}
    </div>
    <div class="info">
      <div class="name">
        {tr(m.name)}
        {#if m.recommended}<span class="tag">{t("Conseillé")}</span>{/if}
        {#if m.streaming || m.livePreview}<span class="tag live">{t("En direct")}</span>{/if}
      </div>
      <div class="desc">{tr(m.description)}</div>
      <div class="meta">
        {@render dots(m.speed, t("Vitesse"))}
        {@render dots(m.accuracy, t("Précision"))}
        <span>{languages(m)}</span>
        <span>{m.punctuation ? t("ponctuation") : t("sans ponctuation")}</span>
        <span>{formatSize(m.sizeBytes)}</span>
        <span>{t("licence %1", m.license)}</span>
      </div>
      {#if p}
        <div class="progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={p.total ? Math.round((p.downloaded / p.total) * 100) : 0}>
          <span style:width="{p.total ? (p.downloaded / p.total) * 100 : 0}%"></span>
        </div>
        <div class="desc">{t("%1 sur %2", formatSize(p.downloaded), formatSize(p.total || m.sizeBytes))}</div>
      {/if}
    </div>
    <div class="act">
      {#if inUse}
        <span class="in-use"><Icon name="check" size={16} />{t("En service")}</span>
      {:else if m.installed}
        <button class="pill-btn tonal" onclick={() => api.useModel(m.id).then(() => app.update({ model: m.id }))}
          >{t("Utiliser")}</button
        >
      {:else if p}
        <button class="pill-btn" onclick={() => api.cancelDownload(m.id)}>{t("Annuler")}</button>
      {:else}
        <button class="pill-btn primary" onclick={() => download(m)}
          ><Icon name="download" size={16} />{t("Télécharger")}</button
        >
      {/if}
      {#if m.installed}
        <button class="pill-btn danger" aria-label={t("Supprimer %1", m.name)} onclick={() => remove(m)}
          ><Icon name="trash" size={16} /></button
        >
      {/if}
    </div>
  </div>
{/snippet}

<div class="screen">
  <div>
    <h1>{t("Modèles")}</h1>
    <p class="lead">{t("Les modèles locaux tournent sur cet ordinateur : ta voix ne quitte pas la machine.")}</p>
  </div>

  {#if installed.length}
    <section class="card list" aria-labelledby="installed">
      <h2 id="installed">{t("Sur cet ordinateur")}</h2>
      {#each installed as m (m.id)}{@render model(m)}{/each}
    </section>
  {/if}

  <section class="card list" aria-labelledby="catalog">
    <h2 id="catalog">{t("À télécharger")}</h2>
    {#each available as m (m.id)}{@render model(m)}{/each}
  </section>

  <section class="card list" aria-labelledby="online">
    <h2 id="online">{t("Services en ligne")}</h2>
    <p class="section-hint">
      {t("L'audio part chez le fournisseur choisi. Un seul moteur sert à la fois : activer un service le met en service à la place du modèle local.")}
    </p>
    <ProviderList kind="transcription" />
  </section>

  <label class="gpu card">
    <input
      type="checkbox"
      class="switch"
      checked={app.settings?.useGpu ?? true}
      onchange={(e) => app.update({ useGpu: e.currentTarget.checked })}
    />
    <span>
      <span class="title">{t("Utiliser la carte graphique")}</span>
      <span class="hint">{t("Plus rapide pour Whisper quand elle est prise en charge ; sinon le processeur prend le relais.")}</span>
    </span>
  </label>
</div>

<style>
  .screen {
    display: flex;
    flex-direction: column;
    gap: 20px;
    max-width: 980px;
  }

  .list {
    padding: 18px 0 6px;
  }

  .list h2 {
    padding: 0 22px 6px;
  }

  .model {
    display: flex;
    gap: 14px;
    padding: 16px 22px;
    border-bottom: 1px solid var(--sunken);
    align-items: flex-start;
  }

  .model:last-child {
    border-bottom: none;
  }

  .section-hint {
    margin: 0;
    padding: 0 22px 6px;
    font-size: 13px;
    color: var(--muted);
  }

  .mark {
    width: 44px;
    height: 44px;
    border-radius: 13px;
    background: var(--bg);
    color: var(--ink);
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 800;
    font-size: 14px;
    flex: none;
  }

  .info {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  .name {
    font-size: 15px;
    font-weight: 700;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }

  .tag {
    font-size: 11px;
    font-weight: 700;
    padding: 2px 8px;
    border-radius: 10px;
    background: var(--accent-soft);
    color: var(--accent);
  }

  .tag.live {
    background: var(--rec-container);
    color: var(--on-rec-container);
  }

  .desc {
    font-size: 13px;
    color: var(--muted);
  }

  .meta {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 14px;
    font-size: 12px;
    color: var(--muted);
    margin-top: 2px;
  }

  .meter {
    display: inline-flex;
    align-items: center;
    gap: 3px;
  }

  .pip {
    width: 6px;
    height: 6px;
    border-radius: 3px;
    background: var(--border);
  }

  .pip.on {
    background: var(--accent-fill);
  }

  .progress {
    height: 6px;
    border-radius: 3px;
    background: var(--sunken);
    overflow: hidden;
    margin-top: 6px;
  }

  .progress span {
    display: block;
    height: 100%;
    background: var(--accent-fill);
    transition: width 0.2s;
  }

  .act {
    display: flex;
    align-items: center;
    gap: 4px;
    flex: none;
  }

  .act :global(svg) {
    vertical-align: -3px;
    margin-right: 4px;
  }

  .danger :global(svg) {
    margin-right: 0;
  }

  .in-use {
    display: flex;
    align-items: center;
    gap: 6px;
    height: 40px;
    padding: 0 14px;
    border-radius: 20px;
    background: var(--accent-fill);
    color: var(--on-accent);
    font-size: 14px;
    font-weight: 700;
  }

  .gpu {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 16px 22px;
    cursor: pointer;
  }

  .gpu > span {
    display: flex;
    flex-direction: column;
  }

  .title {
    font-size: 15px;
    font-weight: 700;
  }

  .hint {
    font-size: 13px;
    color: var(--muted);
  }
</style>
