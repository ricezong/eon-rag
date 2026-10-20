<script setup lang="ts">
import { computed } from 'vue'
import type { KnowledgeBase } from '@/types/api'

const props = withDefaults(
  defineProps<{
    kbIds?: string[]
    kbs?: KnowledgeBase[]
    limit?: number
  }>(),
  { kbIds: () => [], kbs: () => [], limit: 2 },
)

const matched = computed(() =>
  (props.kbIds ?? [])
    .map((id) => props.kbs?.find((kb) => kb.id === id))
    .filter((kb): kb is KnowledgeBase => Boolean(kb)),
)

const visible = computed(() => matched.value.slice(0, props.limit))
const extra = computed(() => Math.max(0, matched.value.length - visible.value.length))
</script>

<template>
  <div class="flex flex-wrap items-center gap-1">
    <span
      v-for="kb in visible"
      :key="kb.id"
      class="inline-flex h-[22px] max-w-[132px] items-center gap-1.5 rounded-full border border-line bg-surface-2/70 px-2 text-[10.5px] text-ink-2"
    >
      <span class="h-1.5 w-1.5 shrink-0 rounded-full" :style="{ background: kb.color }" />
      <span class="truncate">{{ kb.name }}</span>
    </span>
    <span
      v-if="extra > 0"
      class="inline-flex h-[22px] items-center rounded-full border border-line bg-surface-2/70 px-2 text-[10.5px] text-ink-3"
    >
      +{{ extra }}
    </span>
    <span v-if="matched.length === 0" class="text-[11px] text-ink-3">—</span>
  </div>
</template>
