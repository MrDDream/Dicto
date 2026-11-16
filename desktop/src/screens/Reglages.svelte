<script lang="ts">
  import { onMount } from "svelte";
  import { api, type Credit, type Term } from "../lib/api";
  import Icon from "../lib/Icon.svelte";
  import { app } from "../lib/store.svelte";

  const LANGUAGES = [
    ["auto", "Automatique"],
    ["fr", "Français"],
    ["en", "Anglais"],
    ["es", "Espagnol"],
    ["de", "Allemand"],
    ["it", "Italien"],
    ["pt", "Portugais"],
    ["nl", "Néerlandais"],
  ];

  let terms = $state<Term[]>([]);
  let newTerm = $state("");
  let credits = $state<Credit[]>([]);
  let showCredits = $state(false);

  onMount(async () => {
    terms = await api.vocabulary();
  });

  async function addTerm(e: SubmitEvent) {
    e.preventDefault();
    const t = newTerm.trim();
    if (!t) return;
    await api.addTerm(t);
    newTerm = "";
    terms = await api.vocabulary();
  }

  async function removeTerm(id: number) {
    await api.removeTerm(id);
    terms = await api.vocabulary();
  }

  async function toggleCredits() {
    showCredits = !showCredits;
    if (showCredits && !credits.length) credits = await api.credits();
  }
</script>

{#if app.settings}
  {@const s = app.settings}
  <div class="screen">
    <div>
      <h1>Réglages</h1>
    </div>

    <section class="card" aria-labelledby="langue">
      <h2 id="langue" class="pad">Langue</h2>
      <div class="row">
        <div class="label">
          <div class="title">Langue parlée</div>
          <div class="hint">« Automatique » détecte la langue à chaque phrase.</div>
        </div>
        <label>
          <span class="sr-only">Langue parlée</span>
          <select class="field" value={s.language} onchange={(e) => app.update({ language: e.currentTarget.value })}>
            {#each LANGUAGES as [code, name] (code)}<option value={code}>{name}</option>{/each}
          </select>
        </label>
      </div>
    </section>

    <section class="card" aria-labelledby="pendant">
      <h2 id="pendant" class="pad">Pendant la dictée</h2>
      <label class="row">
        <span class="label">
          <span class="title">Commandes vocales</span>
          <span class="hint">« nouvelle ligne », « nouveau paragraphe », « efface ça »</span>
        </span>
        <input type="checkbox" class="switch" checked={s.voiceCommands} onchange={(e) => app.update({ voiceCommands: e.currentTarget.checked })} />
      </label>
      <label class="row">
        <span class="label">
          <span class="title">Texte en direct</span>
          <span class="hint">Affiche la phrase pendant que tu parles, avec les modèles rapides. Consomme un peu plus de processeur.</span>
        </span>
        <input type="checkbox" class="switch" checked={s.livePreview} onchange={(e) => app.update({ livePreview: e.currentTarget.checked })} />
      </label>
    </section>

    <section class="card vocab" aria-labelledby="vocabulaire">
      <h2 id="vocabulaire">Vocabulaire</h2>
      <p class="hint">Noms propres et mots techniques que le modèle doit connaître.</p>
      <form onsubmit={addTerm}>
        <label class="grow">
          <span class="sr-only">Nouveau mot</span>
          <input class="field" placeholder="Ajouter un mot" bind:value={newTerm} />
        </label>
        <button class="pill-btn tonal" type="submit"><Icon name="plus" size={16} />Ajouter</button>
      </form>
      {#if terms.length}
        <ul class="chips">
          {#each terms as t (t.id)}
            <li>
              {t.term}
              <button aria-label="Retirer {t.term}" onclick={() => removeTerm(t.id)}><Icon name="close" size={14} /></button>
            </li>
          {/each}
        </ul>
      {/if}
    </section>

    <section class="card about" aria-labelledby="apropos">
      <h2 id="apropos">À propos</h2>
      <p class="hint">
        Dicto 0.1.0 · transcription locale par whisper.cpp et sherpa-onnx. Modèles de NVIDIA, Meta, OpenAI et Bofeng
        Huang.
      </p>
      <div><button class="pill-btn tonal" onclick={toggleCredits}>Crédits et licences</button></div>
      {#if showCredits}
        <ul class="credits">
          {#each credits as c (c.name)}
            <li><strong>{c.name}</strong> · {c.license} · {c.credit}</li>
          {/each}
        </ul>
      {/if}
    </section>
  </div>
{/if}

<style>
  .screen {
    display: flex;
    flex-direction: column;
    gap: 20px;
    max-width: 860px;
  }

  .pad {
    padding: 18px 22px 6px;
  }

  label.row {
    cursor: pointer;
  }

  .row .label {
    display: flex;
    flex-direction: column;
  }

  .vocab,
  .about {
    padding: 18px 22px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .hint {
    margin: 0;
    font-size: 13px;
    color: var(--muted);
  }

  form {
    display: flex;
    gap: 8px;
  }

  .grow {
    flex: 1;
    display: flex;
  }

  .grow input {
    flex: 1;
    font-weight: 500;
  }

  form .pill-btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    height: 42px;
  }

  .chips {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .chips li {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 4px 6px 4px 12px;
    border-radius: 16px;
    background: var(--bg);
    border: 1px solid var(--border);
    font-size: 14px;
  }

  .chips button {
    border: none;
    background: transparent;
    display: flex;
    padding: 4px;
    border-radius: 10px;
    color: var(--muted);
  }

  .credits {
    margin: 0;
    padding-left: 18px;
    font-size: 13px;
    color: var(--ink-2);
    line-height: 1.6;
  }
</style>
