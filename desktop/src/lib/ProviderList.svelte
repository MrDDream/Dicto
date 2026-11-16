<script lang="ts">
  // Liste des fournisseurs (transcription en ligne ou retouche par IA) : un seul actif à la fois.
  // L'interrupteur l'active (réglage demandé d'abord s'il manque quelque chose), la roue le règle.
  import { onMount } from "svelte";
  import { api, prettyModel, type ProviderKind, type ProviderView } from "./api";
  import BrandLogo from "./BrandLogo.svelte";
  import ProviderDialog from "./ProviderDialog.svelte";
  import { app } from "./store.svelte";

  let { kind }: { kind: ProviderKind } = $props();

  let list = $state<ProviderView[]>([]);
  let editing = $state<ProviderView | null>(null);
  /** Activation en attente du réglage, et le fournisseur actif avant (rétabli si on annule). */
  let enableAfter = false;
  let previous: string | null = null;

  async function refresh() {
    list = await api.providers(kind);
  }
  onMount(refresh);

  async function activate(slug: string | null) {
    app.settings = kind === "transcription" ? await api.setCloudEngine(slug) : await api.setRefineProvider(slug);
    await refresh();
  }

  async function toggle(p: ProviderView, on: boolean) {
    if (!on) return activate(null);
    if (p.configured) return activate(p.slug);
    previous = list.find((x) => x.active)?.slug ?? null;
    // Retouche : l'activation pose le modèle conseillé, modifiable dans la fenêtre qui s'ouvre.
    if (kind === "refine") await activate(p.slug);
    enableAfter = true;
    editing = list.find((x) => x.slug === p.slug) ?? p;
  }

  async function closed(saved: boolean) {
    const p = editing;
    editing = null;
    await refresh();
    if (enableAfter && p) {
      const configured = list.find((x) => x.slug === p.slug)?.configured;
      // Réglage complet : on l'active ; sinon on revient à l'état d'avant.
      await activate(saved && configured ? p.slug : previous);
    }
    enableAfter = false;
  }
</script>

<div class="list">
  {#each list as p (p.slug)}
    <div class="row">
      <span class="brand"><BrandLogo brand={p.brand} size={24} /></span>
      <div class="label">
        <div class="title">
          {p.label}
          {#if p.active}<span class="tag">{kind === "transcription" ? "En service" : "Actif"}</span>{/if}
        </div>
        <div class="hint">
          {#if p.configured}{prettyModel(p.model ?? "")}{:else if p.hasKey}Choisis un modèle{:else}Non configuré{/if}
        </div>
      </div>
      <button class="gear" aria-label="Régler {p.label}" onclick={() => { enableAfter = false; editing = p; }}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"
          ><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" /></svg
        >
      </button>
      <input
        type="checkbox"
        class="switch"
        aria-label="Activer {p.label}"
        checked={p.active && p.configured}
        onchange={(e) => toggle(p, e.currentTarget.checked)}
      />
    </div>
  {/each}
</div>

{#if editing}
  <ProviderDialog {kind} provider={editing} onclose={closed} />
{/if}

<style>
  .row {
    gap: 14px;
  }

  .brand {
    width: 40px;
    height: 40px;
    border-radius: 12px;
    background: var(--bg);
    display: flex;
    align-items: center;
    justify-content: center;
    flex: none;
  }

  .label {
    flex: 1 1 200px;
  }

  .title {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .tag {
    font-size: 11px;
    font-weight: 700;
    padding: 2px 8px;
    border-radius: 10px;
    background: var(--accent);
    color: var(--on-accent);
  }

  .gear {
    border: none;
    background: transparent;
    color: var(--muted);
    width: 38px;
    height: 38px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .gear:hover {
    background: var(--bg);
  }
</style>
