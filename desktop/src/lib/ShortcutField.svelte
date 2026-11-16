<script lang="ts">
  // Bouton qui affiche un raccourci et en capture un nouveau au clavier.
  // Pendant la saisie, les raccourcis globaux sont suspendus (sinon ils se déclencheraient).
  import { api, shortcutFromEvent, shortcutKeys } from "./api";

  let { value, label, onchange }: { value: string; label: string; onchange: (v: string) => void } = $props();

  let capturing = $state(false);

  async function start() {
    capturing = true;
    await api.pauseShortcuts(true);
  }

  async function stop(next: string | null) {
    capturing = false;
    if (next !== null && next !== value) onchange(next);
    else await api.pauseShortcuts(false);
  }

  function keydown(e: KeyboardEvent) {
    if (!capturing) return;
    e.preventDefault();
    e.stopPropagation();
    if (e.key === "Escape" && !e.ctrlKey && !e.altKey && !e.shiftKey && !e.metaKey) {
      stop(null);
      return;
    }
    // Retour arrière seul : aucun raccourci pour ce mode.
    if (e.key === "Backspace" && !e.ctrlKey && !e.altKey && !e.shiftKey && !e.metaKey) {
      stop("");
      return;
    }
    const s = shortcutFromEvent(e);
    if (s) stop(s);
  }
</script>

<button
  class="shortcut"
  class:capturing
  aria-label="Modifier le raccourci {label}"
  onclick={() => (capturing ? stop(null) : start())}
  onkeydown={keydown}
  onblur={() => capturing && stop(null)}
>
  {#if capturing}
    <span class="prompt">Appuie sur une touche… (Échap : annuler, ⌫ : aucun)</span>
  {:else if value}
    {#each shortcutKeys(value) as k (k)}<kbd>{k}</kbd>{/each}
  {:else}
    <span class="none">Aucun</span>
  {/if}
</button>

<style>
  .shortcut {
    display: flex;
    gap: 6px;
    align-items: center;
    min-height: 42px;
    padding: 0 12px;
    border-radius: 14px;
    border: 1px solid var(--border);
    background: var(--bg);
  }

  .shortcut.capturing {
    border: 2px solid var(--accent);
    background: var(--accent-soft);
  }

  .prompt {
    font-size: 13px;
    font-weight: 700;
    color: var(--accent);
  }

  .none {
    font-size: 13px;
    color: var(--muted);
  }
</style>
