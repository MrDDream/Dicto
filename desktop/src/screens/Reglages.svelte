<script lang="ts">
  import { onMount } from "svelte";
  import {
    api,
    type AppInfo,
    type Credit,
    type PlatformView,
    type PresetView,
    type ProfileView,
    type Replacement,
    type Term,
    type Theme,
    type UiLanguage,
  } from "../lib/api";
  import Icon, { type IconName } from "../lib/Icon.svelte";
  import ProviderList from "../lib/ProviderList.svelte";
  import { app, settingsTab, type SettingsTab } from "../lib/store.svelte";
  import { t, tr } from "../lib/i18n.svelte";

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

  // Les noms des langues de l'interface restent dans leur propre langue.
  const UI_LANGUAGES: [UiLanguage, string][] = [
    ["auto", "Automatique"],
    ["fr", "Français"],
    ["en", "English"],
  ];
  /** Pauses qui ouvrent un paragraphe (ms, 0 = jamais). */
  const PARAGRAPH_PAUSES: [number, string][] = [
    [0, "Jamais"],
    [1500, "Après 1,5 s"],
    [2500, "Après 2,5 s"],
    [4000, "Après 4 s"],
  ];
  const THEMES: [Theme, string][] = [
    ["auto", "Automatique"],
    ["light", "Clair"],
    ["dark", "Sombre"],
  ];

  /** Rubriques : chacune affiche ses cartes, la dernière choisie reste ouverte. */
  const TABS: [SettingsTab, string, IconName][] = [
    ["dictee", "Dictée", "mic"],
    ["mots", "Mots et remplacements", "pencil"],
    ["retouche", "Retouche par IA", "sparkle"],
    ["profils", "Profils par app", "models"],
    ["general", "Général", "settings"],
  ];

  let terms = $state<Term[]>([]);
  let newTerm = $state("");
  let credits = $state<Credit[]>([]);
  let showCredits = $state(false);

  let presets = $state<PresetView[]>([]);
  let replacements = $state<Replacement[]>([]);
  let phrase = $state("");
  let written = $state("");
  let profiles = $state<ProfileView[]>([]);
  let newApp = $state<AppInfo | null>(null);
  let newAppId = $state("");
  let platform = $state<PlatformView>({ os: "", wayland: false });

  /** Modes d'un profil (mêmes valeurs que sur Android, pour l'export d'un appareil à l'autre). */
  const PROFILE_MODES: [string, string][] = [
    ["", "Mode du raccourci"],
    ["PUSH_TO_TALK", "Marche/arrêt"],
    ["CONTINUOUS", "Continu"],
    ["HOLD", "Maintenu"],
  ];

  onMount(async () => {
    terms = await api.vocabulary();
    presets = await api.refinePresets();
    replacements = await api.replacements();
    profiles = await api.profiles();
    platform = await api.platformInfo();
  });

  async function addReplacement(e: SubmitEvent) {
    e.preventDefault();
    if (!phrase.trim() || !written.trim()) return;
    await api.setReplacement(phrase.trim(), written);
    phrase = "";
    written = "";
    replacements = await api.replacements();
  }

  async function removeReplacement(id: number) {
    await api.removeReplacement(id);
    replacements = await api.replacements();
  }

  async function useLastApp() {
    const last = await api.lastApp();
    if (last) {
      newApp = last;
      newAppId = last.id;
    } else {
      app.show(t("Ouvre d'abord l'app voulue, puis reviens ici."));
    }
  }

  async function addProfile(e: SubmitEvent) {
    e.preventDefault();
    const id = newAppId.trim();
    if (!id) return;
    try {
      const label = newApp && newApp.id === id ? newApp.label : null;
      await api.saveProfile({ id: null, appId: id, label, language: null, mode: null, refinePreset: null });
      newAppId = "";
      newApp = null;
      profiles = await api.profiles();
    } catch (err) {
      app.show(String(err));
    }
  }

  async function updateProfile(p: ProfileView, patch: Partial<ProfileView>) {
    await api.saveProfile({ ...p, ...patch });
    profiles = await api.profiles();
  }

  async function deleteProfile(id: number | null) {
    if (id === null) return;
    await api.deleteProfile(id);
    profiles = await api.profiles();
  }

  async function exportBundle() {
    try {
      if (await api.exportBundle()) app.show(t("Réglages exportés."));
    } catch (err) {
      app.show(String(err));
    }
  }

  async function importBundle() {
    try {
      const c = await api.importBundle();
      if (!c) return;
      app.show(t("Importé : %1 mots, %2 remplacements, %3 profils.", c.vocabulary, c.replacements, c.profiles));
      terms = await api.vocabulary();
      replacements = await api.replacements();
      profiles = await api.profiles();
    } catch (err) {
      app.show(String(err));
    }
  }

  // La consigne personnalisée s'enregistre après la frappe.
  let customTimer: ReturnType<typeof setTimeout> | undefined;
  function setCustom(text: string) {
    if (app.settings) app.settings.refineCustom = text;
    clearTimeout(customTimer);
    customTimer = setTimeout(() => app.update({ refineCustom: text }), 600);
  }

  async function addTerm(e: SubmitEvent) {
    e.preventDefault();
    const term = newTerm.trim();
    if (!term) return;
    await api.addTerm(term);
    newTerm = "";
    terms = await api.vocabulary();
  }

  async function removeTerm(id: number) {
    await api.removeTerm(id);
    terms = await api.vocabulary();
  }

  let checking = $state(false);
  let updateStatus = $state<string | null>(null);

  async function setAutoUpdate(on: boolean) {
    app.updates = await api.updateSetConsent(on);
  }

  // « Vérifier maintenant » : une version plus récente ouvre la fenêtre de mise à jour.
  async function checkUpdates() {
    checking = true;
    updateStatus = null;
    try {
      const u = await api.updateCheck();
      app.updates = u;
      updateStatus = u.available ? t("Dicto %1 est disponible.", u.available.tag) : t("Dicto est à jour.");
    } catch (e) {
      updateStatus = tr(String(e));
    } finally {
      checking = false;
    }
  }

  async function copyJournal() {
    const text = await api.readJournal();
    if (!text.trim()) {
      app.show(t("Le journal est vide."));
      return;
    }
    await api.copyText(text);
    app.show(t("Journal copié."));
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
      <h1>{t("Réglages")}</h1>
    </div>

    <div class="tabs" role="tablist" aria-label={t("Rubriques des réglages")}>
      {#each TABS as [id, name, icon] (id)}
        <button role="tab" aria-selected={settingsTab.current === id} class:on={settingsTab.current === id} onclick={() => (settingsTab.current = id)}
          ><Icon name={icon} size={16} />{t(name)}</button
        >
      {/each}
    </div>

    {#if settingsTab.current === "dictee"}
      <section class="card" aria-labelledby="pendant">
        <h2 id="pendant" class="pad">{t("Texte")}</h2>
        <div class="row">
          <div class="label">
            <div class="title">{t("Langue de la dictée")}</div>
            <div class="hint">{t("« Automatique » détecte la langue à chaque phrase.")}</div>
          </div>
          <label>
            <span class="sr-only">{t("Langue de la dictée")}</span>
            <select class="field" value={s.language} onchange={(e) => app.update({ language: e.currentTarget.value })}>
              {#each LANGUAGES as [code, name] (code)}<option value={code}>{t(name)}</option>{/each}
            </select>
          </label>
        </div>
        <label class="row">
          <span class="label">
            <span class="title">{t("Commandes vocales")}</span>
            <span class="hint">{t("« nouvelle ligne », « efface ça », « majuscule », « tout en majuscules », « envoie le message »")}</span>
          </span>
          <input type="checkbox" class="switch" checked={s.voiceCommands} onchange={(e) => app.update({ voiceCommands: e.currentTarget.checked })} />
        </label>
        <label class="row">
          <span class="label">
            <span class="title">{t("Ponctuation dictée")}</span>
            <span class="hint">{t("Dis « virgule », « point », « point d'interrogation », « ouvrez les guillemets »… pour les écrire.")}</span>
          </span>
          <input type="checkbox" class="switch" checked={s.spokenPunctuation} onchange={(e) => app.update({ spokenPunctuation: e.currentTarget.checked })} />
        </label>
        <label class="row">
          <span class="label">
            <span class="title">{t("Retirer les hésitations")}</span>
            <span class="hint">{t("« euh », « hum » et les mots répétés par erreur (« le le »), sans IA.")}</span>
          </span>
          <input type="checkbox" class="switch" checked={s.removeFillers} onchange={(e) => app.update({ removeFillers: e.currentTarget.checked })} />
        </label>
        <div class="row">
          <div class="label">
            <div class="title">{t("Paragraphes automatiques")}</div>
            <div class="hint">{t("Une pause marquée ouvre un nouveau paragraphe, entre deux phrases ou dans un long enregistrement.")}</div>
          </div>
          <label>
            <span class="sr-only">{t("Paragraphes automatiques")}</span>
            <select class="field" value={String(s.paragraphPauseMs ?? 2500)} onchange={(e) => app.update({ paragraphPauseMs: Number(e.currentTarget.value) })}>
              {#each PARAGRAPH_PAUSES as [ms, name] (ms)}<option value={String(ms)}>{t(name)}</option>{/each}
            </select>
          </label>
        </div>
      </section>
      <section class="card" aria-labelledby="ecoute">
        <h2 id="ecoute" class="pad">{t("Pendant l'écoute")}</h2>
        <label class="row">
          <span class="label">
            <span class="title">{t("Texte en direct")}</span>
            <span class="hint">{t("Affiche la phrase pendant que tu parles, avec les modèles rapides. Consomme un peu plus de processeur.")}</span>
          </span>
          <input type="checkbox" class="switch" checked={s.livePreview} onchange={(e) => app.update({ livePreview: e.currentTarget.checked })} />
        </label>
        <label class="row">
          <span class="label">
            <span class="title">{t("Sons de début et de fin")}</span>
            <span class="hint">{t("Deux notes douces quand Dicto commence et arrête d'écouter.")}</span>
          </span>
          <input type="checkbox" class="switch" checked={s.sounds} onchange={(e) => app.update({ sounds: e.currentTarget.checked })} />
        </label>
      </section>
    {:else if settingsTab.current === "mots"}
      <section class="card vocab" aria-labelledby="vocabulaire">
        <h2 id="vocabulaire">{t("Vocabulaire")}</h2>
        <p class="hint">{t("Noms propres et mots techniques que le modèle doit connaître.")}</p>
        <form onsubmit={addTerm}>
          <label class="grow">
            <span class="sr-only">{t("Nouveau mot")}</span>
            <input class="field" placeholder={t("Ajouter un mot")} bind:value={newTerm} />
          </label>
          <button class="pill-btn tonal" type="submit"><Icon name="plus" size={16} />{t("Ajouter")}</button>
        </form>
        {#if terms.length}
          <ul class="chips">
            {#each terms as term (term.id)}
              <li>
                {term.term}
                <button aria-label={t("Retirer %1", term.term)} onclick={() => removeTerm(term.id)}><Icon name="close" size={14} /></button>
              </li>
            {/each}
          </ul>
        {/if}
      </section>
      <section class="card vocab" aria-labelledby="remplacements">
        <h2 id="remplacements">{t("Remplacements")}</h2>
        <p class="hint">{t("Ce que tu dis est remplacé par ton texte : « mon adresse » devient ton adresse complète.")}</p>
        <form onsubmit={addReplacement} class="pair">
          <label class="grow">
            <span class="sr-only">{t("Ce que tu dis")}</span>
            <input class="field" placeholder={t("Ce que tu dis")} bind:value={phrase} />
          </label>
          <label class="grow">
            <span class="sr-only">{t("Ce qui est écrit")}</span>
            <input class="field" placeholder={t("Ce qui est écrit")} bind:value={written} />
          </label>
          <button class="pill-btn tonal" type="submit"><Icon name="plus" size={16} />{t("Ajouter")}</button>
        </form>
        {#if replacements.length}
          <ul class="pairs">
            {#each replacements as r (r.id)}
              <li>
                <span class="said">{r.phrase}</span>
                <span class="arrow" aria-hidden="true">→</span>
                <span class="written">{r.replacement}</span>
                <button aria-label={t("Retirer %1", r.phrase)} onclick={() => removeReplacement(r.id)}><Icon name="close" size={14} /></button>
              </li>
            {/each}
          </ul>
        {/if}
      </section>
    {:else if settingsTab.current === "retouche"}
      <section class="card" aria-labelledby="retouche">
        <h2 id="retouche" class="pad">{t("Retouche par IA")}</h2>
        <p class="hint pad-x">
          {t("Ponctuation, style, traduction. Le fournisseur activé reçoit le texte transcrit (jamais l'audio) ; Ollama peut tourner sur cet ordinateur. Les clés restent dans le coffre du système.")}
        </p>
        <ProviderList kind="refine" />
      </section>
      {#if s.refineProvider}
        <section class="card vocab" aria-labelledby="consigne">
          <h2 id="consigne">{t("Consigne")}</h2>
          <div class="presets" role="radiogroup" aria-label={t("Consigne de retouche")}>
            {#each [...presets, { id: "custom", name: "Personnalisée" }] as p (p.id)}
              <button role="radio" aria-checked={s.refinePreset === p.id} class:on={s.refinePreset === p.id} onclick={() => app.update({ refinePreset: p.id })}
                >{tr(p.name)}</button
              >
            {/each}
          </div>
          {#if s.refinePreset === "custom"}
            <label class="custom">
              <span class="sr-only">{t("Ta consigne")}</span>
              <textarea
                class="field"
                rows="3"
                placeholder={t("Ex. : mets le texte au format d'un e-mail poli, en vouvoyant.")}
                value={s.refineCustom}
                oninput={(e) => setCustom(e.currentTarget.value)}
              ></textarea>
            </label>
          {/if}
          <label class="inline-switch">
            <span>
              <span class="title">{t("Retoucher chaque dictée")}</span>
              <span class="hint">{t("Sinon, utilise « Reformuler » dans l'écran Dictée.")}</span>
            </span>
            <input type="checkbox" class="switch" checked={s.autoRefine} onchange={(e) => app.update({ autoRefine: e.currentTarget.checked })} />
          </label>
        </section>
      {/if}
    {:else if settingsTab.current === "profils"}
      <section class="card vocab" aria-labelledby="profils">
        <h2 id="profils">{t("Profils par app")}</h2>
        <p class="hint">
          {t("Une langue, une retouche ou un mode propres à une app, appliqués quand tu dictes avec un raccourci pendant qu'elle est au premier plan.")}
        </p>
        {#if platform.wayland}
          <p class="note">{t("Sous Wayland, Dicto ne peut pas savoir quelle app est au premier plan : les profils ne s'appliquent pas.")}</p>
        {/if}
        {#if profiles.length}
          <ul class="profiles">
            {#each profiles as p (p.id)}
              <li>
                <div class="who">
                  <span class="title">{p.label ?? p.appId}</span>
                  {#if p.label}<span class="hint">{p.appId}</span>{/if}
                </div>
                <label>
                  <span class="sr-only">{t("Langue de la dictée")}</span>
                  <select class="field" value={p.language ?? ""} onchange={(e) => updateProfile(p, { language: e.currentTarget.value || null })}>
                    <option value="">{t("Langue par défaut")}</option>
                    {#each LANGUAGES as [code, name] (code)}<option value={code}>{t(name)}</option>{/each}
                  </select>
                </label>
                <label>
                  <span class="sr-only">{t("Consigne de retouche")}</span>
                  <select class="field" value={p.refinePreset ?? ""} onchange={(e) => updateProfile(p, { refinePreset: e.currentTarget.value || null })}>
                    <option value="">{t("Retouche par défaut")}</option>
                    <option value="none">{t("Sans retouche")}</option>
                    {#each presets as pr (pr.id)}<option value={pr.id}>{tr(pr.name)}</option>{/each}
                  </select>
                </label>
                <label>
                  <span class="sr-only">{t("Mode")}</span>
                  <select class="field" value={p.mode ?? ""} onchange={(e) => updateProfile(p, { mode: e.currentTarget.value || null })}>
                    {#each PROFILE_MODES as [id, name] (id)}<option value={id}>{t(name)}</option>{/each}
                  </select>
                </label>
                <button class="pill-btn ghost" aria-label={t("Supprimer le profil %1", p.label ?? p.appId)} onclick={() => deleteProfile(p.id)}
                  ><Icon name="trash" size={15} /></button
                >
              </li>
            {/each}
          </ul>
          <p class="hint">{t("Le mode s'applique au raccourci Marche/arrêt ; la retouche demande un fournisseur actif.")}</p>
        {/if}
        <form onsubmit={addProfile}>
          <label class="grow">
            <span class="sr-only">{t("App")}</span>
            <input class="field" placeholder={t("App (ex. : slack, firefox, com.apple.mail)")} bind:value={newAppId} />
          </label>
          {#if !platform.wayland}
            <button class="pill-btn" type="button" onclick={useLastApp}>{t("Dernière app active")}</button>
          {/if}
          <button class="pill-btn tonal" type="submit"><Icon name="plus" size={16} />{t("Ajouter")}</button>
        </form>
      </section>
    {:else}
      <section class="card" aria-labelledby="apparence">
        <h2 id="apparence" class="pad">{t("Apparence")}</h2>
        <div class="row">
          <div class="label">
            <div class="title">{t("Langue de l'app")}</div>
            <div class="hint">{t("« Automatique » suit la langue du système.")}</div>
          </div>
          <div class="segmented" role="radiogroup" aria-label={t("Langue de l'app")}>
            {#each UI_LANGUAGES as [code, name] (code)}
              <button role="radio" aria-checked={s.uiLanguage === code} class:on={s.uiLanguage === code} onclick={() => app.update({ uiLanguage: code })}
                >{code === "auto" ? t(name) : name}</button
              >
            {/each}
          </div>
        </div>
        <div class="row">
          <div class="label">
            <div class="title">{t("Thème")}</div>
            <div class="hint">{t("« Automatique » suit le mode clair ou sombre du système.")}</div>
          </div>
          <div class="segmented" role="radiogroup" aria-label={t("Thème")}>
            {#each THEMES as [code, name] (code)}
              <button role="radio" aria-checked={s.theme === code} class:on={s.theme === code} onclick={() => app.update({ theme: code })}>{t(name)}</button>
            {/each}
          </div>
        </div>
      </section>
      <section class="card vocab" aria-labelledby="sauvegarde">
        <h2 id="sauvegarde">{t("Sauvegarde")}</h2>
        <p class="hint">{t("Vocabulaire, remplacements et profils, pour les retrouver sur un autre appareil (téléphone ou ordinateur).")}</p>
        <div class="buttons">
          <button class="pill-btn tonal" onclick={exportBundle}>{t("Exporter")}</button>
          <button class="pill-btn" onclick={importBundle}>{t("Importer")}</button>
        </div>
      </section>
      <section class="card" aria-labelledby="majs">
        <h2 id="majs" class="pad">{t("Mises à jour")}</h2>
        <label class="row">
          <span class="label">
            <span class="title">{t("Vérifier automatiquement")}</span>
            <span class="hint">{t("Une fois par jour, Dicto regarde sur GitHub si une version plus récente existe.")}</span>
          </span>
          <input type="checkbox" class="switch" checked={app.updates?.consent === true} onchange={(e) => setAutoUpdate(e.currentTarget.checked)} />
        </label>
        <div class="row">
          <span class="label">
            <span class="title">{t("Version %1", app.updates?.current ?? "")}</span>
            {#if updateStatus}<span class="hint">{updateStatus}</span>{/if}
          </span>
          <button class="pill-btn tonal" disabled={checking} onclick={checkUpdates}>{checking ? t("Vérification…") : t("Vérifier maintenant")}</button>
        </div>
      </section>
      <section class="card vocab" aria-labelledby="journal">
        <h2 id="journal">{t("Journal")}</h2>
        <p class="hint">{t("Les erreurs et plantages de Dicto, à copier dans ton message si quelque chose ne marche pas.")}</p>
        <div class="buttons">
          <button class="pill-btn tonal" onclick={copyJournal}>{t("Copier le journal")}</button>
        </div>
      </section>

      <section class="card about" aria-labelledby="apropos">
        <h2 id="apropos">{t("À propos")}</h2>
        <p class="hint">
          {t("Dicto %1 · transcription locale par whisper.cpp et sherpa-onnx. Modèles de NVIDIA, Meta, OpenAI et Bofeng Huang.", app.updates?.current ?? "")}
        </p>
        <div><button class="pill-btn tonal" onclick={toggleCredits}>{t("Crédits et licences")}</button></div>
        {#if showCredits}
          <ul class="credits">
            {#each credits as c (c.name)}
              <li><strong>{c.name}</strong> · {c.license} · {tr(c.credit)}</li>
            {/each}
          </ul>
        {/if}
      </section>
    {/if}
  </div>
{/if}

<style>
  .tabs {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    padding: 5px;
    border-radius: 18px;
    background: var(--surface-sunken, var(--bg));
    border: 1px solid var(--border);
    align-self: flex-start;
  }

  .tabs button {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    height: 38px;
    padding: 0 14px;
    border: none;
    border-radius: 13px;
    background: transparent;
    color: var(--muted);
    font-size: 14px;
    font-weight: 600;
  }

  .tabs button:hover {
    color: var(--ink);
  }

  .tabs button.on {
    background: var(--surface);
    color: var(--accent);
    font-weight: 700;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
  }

  .segmented {
    display: inline-flex;
    padding: 4px;
    gap: 4px;
    border-radius: 14px;
    background: var(--bg);
  }

  .segmented button {
    height: 34px;
    padding: 0 14px;
    border: none;
    border-radius: 10px;
    background: transparent;
    color: var(--muted);
    font-size: 13px;
    font-weight: 600;
  }

  .segmented button.on {
    background: var(--surface);
    color: var(--accent);
    font-weight: 700;
    box-shadow: 0 1px 2px rgba(0, 0, 0, 0.12);
  }

  .screen {
    display: flex;
    flex-direction: column;
    gap: 20px;
    max-width: 860px;
  }

  .pad {
    padding: 18px 22px 6px;
  }

  .pad-x {
    padding: 0 22px 6px;
  }

  .presets {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .presets button {
    height: 36px;
    padding: 0 14px;
    border-radius: 18px;
    border: 1px solid var(--border);
    background: var(--bg);
    font-size: 13px;
    font-weight: 600;
  }

  .presets button.on {
    border-color: var(--accent);
    background: var(--accent-soft);
    color: var(--accent);
    font-weight: 700;
  }

  .custom textarea {
    width: 100%;
    height: auto;
    padding: 10px 12px;
    font-weight: 500;
    resize: vertical;
  }

  .inline-switch {
    display: flex;
    align-items: center;
    gap: 14px;
    cursor: pointer;
  }

  .inline-switch > span {
    flex: 1;
    display: flex;
    flex-direction: column;
  }

  .title {
    font-size: 15px;
    font-weight: 700;
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

  .pair {
    flex-wrap: wrap;
  }

  .pairs,
  .profiles {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .pairs li {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 8px 8px 14px;
    border-radius: 14px;
    background: var(--bg);
    font-size: 14px;
  }

  .pairs .said {
    font-weight: 700;
  }

  .pairs .arrow {
    color: var(--muted);
  }

  .pairs .written {
    flex: 1;
    min-width: 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }

  .pairs button {
    border: none;
    background: transparent;
    display: flex;
    padding: 4px;
    border-radius: 10px;
    color: var(--muted);
  }

  .profiles li {
    display: grid;
    grid-template-columns: minmax(100px, 1fr) repeat(3, minmax(0, 150px)) auto;
    align-items: center;
    gap: 8px;
    padding: 10px 6px 10px 14px;
    border-radius: 16px;
    background: var(--bg);
  }

  .profiles .who {
    display: flex;
    flex-direction: column;
    min-width: 0;
    overflow-wrap: anywhere;
  }

  .profiles label {
    min-width: 0;
  }

  .profiles select {
    width: 100%;
  }

  .profiles .pill-btn {
    padding: 0 10px;
  }

  @media (max-width: 760px) {
    .profiles li {
      grid-template-columns: 1fr 1fr;
    }
  }

  .note {
    margin: 0;
    padding: 12px 14px;
    border-radius: 14px;
    background: var(--bg);
    font-size: 13px;
    line-height: 1.5;
    color: var(--ink-2);
  }

  .buttons {
    display: flex;
    gap: 8px;
  }

  .credits {
    margin: 0;
    padding-left: 18px;
    font-size: 13px;
    color: var(--ink-2);
    line-height: 1.6;
  }
</style>
