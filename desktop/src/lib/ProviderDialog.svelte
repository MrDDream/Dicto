<script lang="ts">
  // Réglage d'un fournisseur : clé d'API (gardée dans le coffre du système), adresse, modèle.
  import { onMount, untrack } from "svelte";
  import { api, prettyModel, type ProviderKind, type ProviderView } from "./api";
  import BrandLogo from "./BrandLogo.svelte";
  import { app } from "./store.svelte";

  let {
    kind,
    provider,
    onclose,
  }: { kind: ProviderKind; provider: ProviderView; onclose: (saved: boolean) => void } = $props();

  let dialog: HTMLDialogElement;
  let key = $state("");
  // Le formulaire part des valeurs enregistrées.
  const initial = untrack(() => provider);
  let baseUrl = $state(initial.baseUrl ?? "");
  let model = $state(initial.model ?? "");
  let models = $state<string[] | null>(null);
  let loading = $state(false);
  let error = $state<string | null>(null);
  let query = $state("");
  let filter = $state<"chat" | "all">(initial.chatFilters ? "chat" : "all");
  let saving = $state(false);

  onMount(() => {
    dialog.showModal();
    if (provider.configured || provider.hasKey || !provider.needsKey) loadModels();
  });

  async function loadModels() {
    loading = true;
    error = null;
    try {
      models = await api.providerModels(kind, provider.slug, key || null, baseUrl || null);
      if (!model && models.length) model = models[0];
    } catch (e) {
      error = String(e);
      models = null;
    } finally {
      loading = false;
    }
  }

  // Modèles de discussion : on écarte ce qui ne sert pas à retoucher un texte.
  const NOT_CHAT = /(embed|tts|whisper|transcribe|audio|image|dall-e|vision-preview|moderation|realtime|search|similarity|davinci|babbage|rerank)/i;
  const shown = $derived(
    (models ?? []).filter(
      (m) =>
        (filter === "all" || !NOT_CHAT.test(m)) &&
        (!query || m.toLowerCase().includes(query.toLowerCase()) || prettyModel(m).toLowerCase().includes(query.toLowerCase())),
    ),
  );

  const canSave = $derived(
    !!model.trim() && (!provider.needsKey || provider.hasKey || !!key.trim()) && (!provider.urlPlaceholder || !!baseUrl.trim()),
  );

  async function save() {
    saving = true;
    try {
      app.settings = await api.saveProvider(kind, provider.slug, key || null, model, baseUrl || null);
      close(true);
    } catch (e) {
      error = String(e);
    } finally {
      saving = false;
    }
  }

  async function forget() {
    app.settings = await api.forgetProviderKey(kind, provider.slug);
    close(true);
  }

  function close(saved: boolean) {
    dialog.close();
    onclose(saved);
  }
</script>

<dialog bind:this={dialog} oncancel={(e) => { e.preventDefault(); close(false); }} aria-labelledby="provider-title">
  <form method="dialog" onsubmit={(e) => { e.preventDefault(); if (canSave) save(); }}>
    <header>
      <BrandLogo brand={provider.brand} size={28} />
      <h2 id="provider-title">{provider.label}</h2>
    </header>

    {#if provider.urlPlaceholder}
      <label class="field-block">
        <span>Adresse</span>
        <input class="field" placeholder={provider.urlPlaceholder} bind:value={baseUrl} onchange={() => loadModels()} />
        {#if provider.urlHint}<small>{provider.urlHint}</small>{/if}
      </label>
    {/if}

    <label class="field-block">
      <span>Clé d'API{provider.needsKey ? "" : " (facultative)"}</span>
      <input
        class="field"
        type="password"
        autocomplete="off"
        placeholder={provider.hasKey ? "Clé enregistrée · en saisir une autre pour la remplacer" : provider.keyHint}
        bind:value={key}
        onchange={() => key && loadModels()}
      />
      <small>Gardée dans le coffre de l'ordinateur (Gestionnaire d'identification, Trousseau…), jamais en clair.</small>
    </label>

    <div class="field-block">
      <span>Modèle</span>
      {#if loading}
        <p class="muted">Chargement des modèles…</p>
      {:else if error}
        <p class="error">{error}</p>
        <button type="button" class="pill-btn" onclick={loadModels}>Réessayer</button>
      {:else if models}
        <div class="picker-tools">
          <input class="field grow" type="search" placeholder="Rechercher un modèle" bind:value={query} />
          {#if provider.chatFilters}
            <select class="field" bind:value={filter} aria-label="Filtre des modèles">
              <option value="chat">Discussion</option>
              <option value="all">Tous</option>
            </select>
          {/if}
        </div>
        <div class="models" role="listbox" aria-label="Modèles">
          {#each shown as m (m)}
            <button type="button" role="option" aria-selected={m === model} class:on={m === model} onclick={() => (model = m)}>
              <span>{prettyModel(m)}</span><code>{m}</code>
            </button>
          {:else}
            <p class="muted">Aucun modèle.</p>
          {/each}
        </div>
      {:else}
        <p class="muted">Saisis la clé{provider.urlPlaceholder ? " et l'adresse" : ""} pour voir les modèles.</p>
        <button type="button" class="pill-btn" onclick={loadModels}>Charger les modèles</button>
      {/if}
      <input class="field" placeholder="ou saisis le nom du modèle" bind:value={model} />
    </div>

    <footer>
      {#if provider.hasKey}<button type="button" class="pill-btn danger" onclick={forget}>Oublier la clé</button>{/if}
      <span class="spacer"></span>
      <button type="button" class="pill-btn" onclick={() => close(false)}>Annuler</button>
      <button type="submit" class="pill-btn primary" disabled={!canSave || saving}>Enregistrer</button>
    </footer>
  </form>
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
    box-shadow: 0 24px 64px rgba(43, 35, 29, 0.3);
  }

  dialog::backdrop {
    background: rgba(43, 35, 29, 0.35);
  }

  form {
    display: flex;
    flex-direction: column;
    gap: 16px;
    padding: 22px;
  }

  header {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  .field-block {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .field-block > span {
    font-size: 13px;
    font-weight: 700;
  }

  .field-block input {
    font-weight: 500;
  }

  small,
  .muted {
    margin: 0;
    font-size: 12px;
    color: var(--muted);
  }

  .error {
    margin: 0;
    font-size: 13px;
    color: var(--on-rec-container);
  }

  .picker-tools {
    display: flex;
    gap: 8px;
  }

  .grow {
    flex: 1;
  }

  .models {
    max-height: 220px;
    overflow-y: auto;
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 4px;
    display: flex;
    flex-direction: column;
  }

  .models button {
    display: flex;
    justify-content: space-between;
    gap: 12px;
    border: none;
    background: transparent;
    text-align: left;
    padding: 8px 10px;
    border-radius: 10px;
    font-size: 14px;
  }

  .models button.on {
    background: var(--accent-soft);
    color: var(--accent);
    font-weight: 700;
  }

  .models code {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--muted);
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
