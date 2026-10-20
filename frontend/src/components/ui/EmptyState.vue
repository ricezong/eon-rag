<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <div class="flex flex-col items-center justify-center px-6 py-16 text-center">
    <div
      class="mb-5 flex h-14 w-14 items-center justify-center rounded-2xl border border-line bg-surface-2"
    >
      <slot name="icon">
        <div class="h-5 w-5 rounded-full border border-[#c7d9ff] bg-brand-soft" />
      </slot>
    </div>

    <h3 class="text-[14px] font-medium text-ink">{{ title }}</h3>
    <p v-if="description" class="mt-1.5 max-w-md text-[12.5px] leading-relaxed text-ink-2">
      {{ description }}
    </p>

    <div v-if="$slots.action" class="mt-5">
      <slot name="action" />
    </div>
  </div>
</template>
