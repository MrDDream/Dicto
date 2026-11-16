<script lang="ts">
  import { LOGOS, type Brand } from "./logos";

  let { brand, size = 22 }: { brand: Brand; size?: number } = $props();
</script>

<span class="logo" style:width="{size}px" style:height="{size}px" aria-hidden="true">{@html LOGOS[brand]}</span>

<style>
  .logo {
    display: inline-flex;
    flex: none;
  }

  .logo :global(svg) {
    width: 100%;
    height: 100%;
  }
</style>
