<script lang="ts">
  import { onMount } from "svelte";
  import { api, type HistoryView, type PresetView } from "../lib/api";
  import Icon from "../lib/Icon.svelte";
  import { app } from "../lib/store.svelte";
  import { i18n, t, tr } from "../lib/i18n.svelte";

  const PAGE = 50;
  let items = $state<HistoryView[]>([]);
  let query = $state("");
  let more = $state(false);

  async function load(reset = true) {
    const page = await api.history(query || null, PAGE, reset ? 0 : items.length);
    items = reset ? page : [...items, ...page];
    more = page.length === PAGE;
  }

  let presets = $state<PresetView[]>([]);
  /** Dictée en cours de retouche et consigne choisie. */
  let refiningId = $state<number | null>(null);
  let refinePreset = $state("clean");
  let refineBusy = $state(false);

  onMount(async () => {
    await load();
    presets = await api.refinePresets();
    refinePreset = app.settings?.refinePreset && app.settings.refinePreset !== "custom" ? app.settings.refinePreset : "clean";
  });

  async function togglePin(item: HistoryView) {
    await api.setHistoryPinned(item.id, !item.pinned);
    await load();
  }

  async function refine(item: HistoryView) {
    refineBusy = true;
    try {
      const text = await api.refineText(refinePreset, item.text);
      await api.updateHistoryText(item.id, text);
      item.text = text;
      refiningId = null;
      app.show(t("Dictée retouchée."));
    } catch (e) {
      app.show(String(e));
    } finally {
      refineBusy = false;
    }
  }

  /** Tout l'historique (pas seulement la page affichée), en texte ou en Markdown. */
  async function exportAll(format: "txt" | "md") {
    const all: HistoryView[] = [];
    for (;;) {
      const page = await api.history(null, 500, all.length);
      all.push(...page);
      if (page.length < 500) break;
    }
    if (!all.length) return;
    const blocks = all.map((i) => {
      const date = dateFormat.format(new Date(i.createdAtMs));
      const pin = i.pinned ? ` (${t("épinglée")})` : "";
      return format === "md" ? `## ${date}${pin}\n\n${i.text}\n` : `${date}${pin}\n${i.text}\n`;
    });
    const content = (format === "md" ? `# ${t("Historique de Dicto")}\n\n` : "") + blocks.join("\n");
    try {
      if (await api.saveText(t("dicto-historique"), format, content)) app.show(t("Historique exporté (%1).", format.toUpperCase()));
    } catch (e) {
      app.show(String(e));
    }
  }

  let searchTimer: ReturnType<typeof setTimeout> | undefined;
  function search() {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => load(), 250);
  }

  async function copy(text: string) {
    await api.copyText(text);
    app.show(t("Texte copié."));
  }

  async function remove(id: number) {
    await api.deleteHistory(id);
    items = items.filter((i) => i.id !== id);
  }

  async function clearAll() {
    if (!confirm(t("Effacer tout l'historique ? Les dictées épinglées sont gardées."))) return;
    await api.clearHistory();
    items = items.filter((i) => i.pinned);
  }

  const dateFormat = $derived(new Intl.DateTimeFormat(i18n.locale, { dateStyle: "medium", timeStyle: "short" }));
</script>

<div class="screen">
  <div class="top">
    <div>
      <h1>{t("Historique")}</h1>
      <p class="lead">{t("Tout ce que tu as dicté, gardé sur cet ordinateur.")}</p>
    </div>
    <label class="search">
      <Icon name="search" size={16} />
      <span class="sr-only">{t("Rechercher")}</span>
      <input type="search" placeholder={t("Rechercher")} bind:value={query} oninput={search} />
    </label>
  </div>

  <section class="card list" aria-label={t("Dictées")}>
    {#each items as item (item.id)}
      <article class:pinned={item.pinned}>
        <p>{item.text}</p>
        <div class="meta">
          {#if item.pinned}<span class="badge"><Icon name="pin" size={12} />{t("Épinglée")}</span>{/if}
          <span>{dateFormat.format(new Date(item.createdAtMs))}</span>
          <span>{item.engine}</span>
          <button class="pill-btn ghost first" onclick={() => copy(item.text)}><Icon name="copy" size={15} />{t("Copier")}</button>
          {#if app.settings?.refineProvider}
            <button class="pill-btn ghost" onclick={() => (refiningId = refiningId === item.id ? null : item.id)}
              ><Icon name="sparkle" size={15} />{t("Retoucher")}</button
            >
          {/if}
          <button
            class="pill-btn ghost"
            class:on={item.pinned}
            aria-pressed={item.pinned}
            aria-label={item.pinned ? t("Désépingler") : t("Épingler")}
            title={item.pinned ? t("Désépingler") : t("Épingler")}
            onclick={() => togglePin(item)}><Icon name="pin" size={15} /></button
          >
          <button class="pill-btn ghost" aria-label={t("Supprimer cette dictée")} onclick={() => remove(item.id)}
            ><Icon name="trash" size={15} /></button
          >
        </div>
        {#if refiningId === item.id}
          <div class="refine">
            <label>
              <span class="sr-only">{t("Consigne de retouche")}</span>
              <select class="field" bind:value={refinePreset} disabled={refineBusy}>
                {#each presets as p (p.id)}<option value={p.id}>{tr(p.name)}</option>{/each}
                {#if app.settings?.refineCustom}<option value="custom">{t("Personnalisée")}</option>{/if}
              </select>
            </label>
            <button class="pill-btn tonal" disabled={refineBusy} onclick={() => refine(item)}>{refineBusy ? t("Retouche…") : t("Appliquer")}</button>
            <button class="pill-btn ghost" disabled={refineBusy} onclick={() => (refiningId = null)}>{t("Annuler")}</button>
          </div>
        {/if}
      </article>
    {:else}
      <p class="empty">{query ? t("Aucun résultat.") : t("Rien pour l'instant : tes dictées apparaîtront ici.")}</p>
    {/each}
  </section>

  <div class="foot">
    {#if more}<button class="pill-btn" onclick={() => load(false)}>{t("Plus ancien")}</button>{/if}
    {#if items.length}
      <button class="pill-btn" onclick={() => exportAll("txt")}>{t("Exporter (.txt)")}</button>
      <button class="pill-btn" onclick={() => exportAll("md")}>{t("Exporter (.md)")}</button>
      <span class="spacer"></span>
      <button class="pill-btn danger" onclick={clearAll}>{t("Tout effacer")}</button>
    {/if}
  </div>
  {#if items.some((i) => i.pinned)}
    <p class="note">{t("« Tout effacer » garde les dictées épinglées.")}</p>
  {/if}
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

  .meta .pill-btn.first {
    margin-left: auto;
  }

  .meta .pill-btn.on {
    color: var(--accent);
  }

  .badge {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 2px 8px;
    border-radius: 10px;
    background: var(--accent-soft);
    color: var(--accent);
    font-weight: 700;
  }

  article.pinned {
    background: color-mix(in srgb, var(--accent-soft) 35%, transparent);
  }

  .refine {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-top: 10px;
  }

  .spacer {
    flex: 1;
  }

  .note {
    margin: 0;
    font-size: 13px;
    color: var(--muted);
  }

  .empty {
    margin: 0;
    padding: 22px;
    color: var(--muted);
  }

  .foot {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
</style>
