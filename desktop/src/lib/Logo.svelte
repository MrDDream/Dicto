<script lang="ts">
  let { size = 32 }: { size?: number } = $props();
</script>

<div class="logo" style:width="{size}px" style:height="{size}px" style:border-radius="{size * 0.34}px">
  <svg width={size * 0.78} height={size * 0.78} viewBox="0 0 100 100" aria-hidden="true">
    <rect x="36" y="14" width="28" height="48" rx="14" stroke="#2B231D" stroke-width="7" fill="#FFFCF8" />
    <path d="M44 32H56" stroke="#2B231D" stroke-width="6" stroke-linecap="round" />
    <path d="M44 44H52" stroke="#2B231D" stroke-width="6" stroke-linecap="round" />
    <path d="M26 50A24 24 0 0 0 74 50" stroke="#2B231D" stroke-width="7" stroke-linecap="round" fill="none" />
    <path d="M50 74V86" stroke="#2B231D" stroke-width="7" stroke-linecap="round" />
  </svg>
</div>

<style>
  .logo {
    background: var(--brand);
    display: flex;
    align-items: center;
    justify-content: center;
    flex: none;
  }
</style>
