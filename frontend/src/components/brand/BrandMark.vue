<script setup lang="ts">
withDefaults(
  defineProps<{
    size?: number
    withWordmark?: boolean
  }>(),
  { size: 34, withWordmark: true },
)
</script>

<template>
  <div class="flex select-none items-center gap-2.5">
    <svg
      :width="size"
      :height="size"
      viewBox="0 0 64 64"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <rect x="2" y="2" width="60" height="60" rx="17" fill="#0F1115" />
      <path
        d="M20 21h24M20 32h17M20 43h24"
        stroke="#FFFFFF"
        stroke-width="3.6"
        stroke-linecap="round"
      />
      <circle cx="49" cy="15" r="3.2" fill="#4176E6" />
    </svg>

    <div v-if="withWordmark" class="leading-none">
      <div class="text-[15px] font-semibold tracking-[0.01em] text-ink">Eon</div>
      <div class="mt-1 text-[8px] font-medium uppercase tracking-[0.22em] text-ink-3">
        RAG Workspace
      </div>
    </div>
  </div>
</template>
