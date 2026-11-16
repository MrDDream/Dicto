<script lang="ts">
  import { onMount } from "svelte";
  import { api, type HistoryView } from "../lib/api";
  import Icon from "../lib/Icon.svelte";
  import { app } from "../lib/store.svelte";

  const PAGE = 50;
  let items = $state<HistoryView[]>([]);
  let query = $state("");
  let more = $state(false);

  async function load(reset = true) {
    const page = await api.history(query || null, PAGE, reset ? 0 : items.length);
    items = reset ? page : [...items, ...page];
    more = page.length === PAGE;
  }

  onMount(() => load());

  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  function search() {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => load(), 250);
  }

  async function copy(text: string) {
    await api.copyText(text);
    app.show("Texte copié.");
  }

  async function remove(id: number) {
    await api.deleteHistory(id);
    items = items.filter((i) => i.id !== id);
  }

  async function clearAll() {
    if (!confirm("Effacer tout l'historique ?")) return;
    await api.clearHistory();
    items = [];
  }

  const dateFormat = new Intl.DateTimeFormat("fr-FR", { dateStyle: "medium", timeStyle: "short" });
</script>

<div class="screen">
  <div class="top">
    <div>
      <h1>Historique</h1>
      <p class="lead">Tout ce que tu as dicté, gardé sur cet ordinateur.</p>
    </div>
    <label class="search">
      <Icon name="search" size={16} />
      <span class="sr-only">Rechercher</span>
      <input type="search" placeholder="Rechercher" bind:value={query} oninput={search} />
    </label>
  </div>

  <section class="card list" aria-label="Dictées">
    {#each items as item (item.id)}
      <article>
        <p>{item.text}</p>
        <div class="meta">
          <span>{dateFormat.format(new Date(item.createdAtMs))}</span>
          <span>{item.engine}</span>
          <button class="pill-btn ghost" onclick={() => copy(item.text)}><Icon name="copy" size={15} />Copier</button>
          <button class="pill-btn ghost" aria-label="Supprimer cette dictée" onclick={() => remove(item.id)}
            ><Icon name="trash" size={15} /></button
          >
        </div>
      </article>
    {:else}
      <p class="empty">{query ? "Aucun résultat." : "Rien pour l'instant : tes dictées apparaîtront ici."}</p>
    {/each}
  </section>

  <div class="foot">
    {#if more}<button class="pill-btn" onclick={() => load(false)}>Plus ancien</button>{/if}
    {#if items.length}<button class="pill-btn danger" onclick={clearAll}>Tout effacer</button>{/if}
  </div>
</div>

<style>
  .screen {
    display: flex;
    flex-direction: column;
    gap: 20px;
    max-width: 900px;
  }

  .top {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    justify-content: space-between;
    gap: 14px;
  }

  .search {
    display: flex;
    align-items: center;
    gap: 8px;
    height: 42px;
    padding: 0 14px;
    border-radius: 14px;
    border: 1px solid var(--border);
    background: var(--surface);
    color: var(--muted);
  }

  .search input {
    border: none;
    background: transparent;
    outline: none;
    font-size: 14px;
    width: 220px;
  }

  .list {
    padding: 6px 0;
  }

  article {
    padding: 14px 22px;
    border-bottom: 1px solid var(--sunken);
  }

  article:last-child {
    border-bottom: none;
  }

  article p {
    margin: 0 0 6px;
    font-size: 16px;
    line-height: 1.5;
    user-select: text;
  }

  .meta {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px 14px;
    font-size: 12px;
    color: var(--muted);
  }

  .meta .pill-btn {
    height: 30px;
    padding: 0 10px;
    font-size: 13px;
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }

  .meta .pill-btn:first-of-type {
    margin-left: auto;
  }

  .empty {
    margin: 0;
    padding: 22px;
    color: var(--muted);
  }

  .foot {
    display: flex;
    gap: 8px;
  }
</style>
