<script setup lang="ts">
import { computed } from 'vue'
import { statusText } from '@/lib/format'
import type { DocumentStatus } from '@/types/api'

const props = defineProps<{ status: DocumentStatus }>()

const className = computed(() => `status-${props.status.toLowerCase().replace('_', '-')}`)
const label = computed(() => statusText(props.status))
</script>

<template>
  <span class="status-pill" :class="className">
    <span class="status-dot" />
    {{ label }}
  </span>
</template>
