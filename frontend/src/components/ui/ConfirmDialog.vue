<script setup lang="ts">
import { Loader2 } from '@lucide/vue'
import AppDialog from './AppDialog.vue'

withDefaults(
  defineProps<{
    open: boolean
    title: string
    message?: string
    confirmText?: string
    cancelText?: string
    danger?: boolean
    loading?: boolean
  }>(),
  {
    confirmText: '确认',
    cancelText: '取消',
    danger: false,
    loading: false,
  },
)

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'confirm'): void
}>()
</script>

<template>
  <AppDialog
    :open="open"
    :title="title"
    width="sm"
    :closeable="!loading"
    @update:open="emit('update:open', $event)"
  >
    <p class="whitespace-pre-line text-[13px] leading-relaxed text-ink-2">
      {{ message || '该操作不可撤销。' }}
    </p>

    <template #footer>
      <button
        type="button"
        class="btn btn-secondary"
        :disabled="loading"
        @click="emit('update:open', false)"
      >
        {{ cancelText }}
      </button>
      <button
        type="button"
        class="btn"
        :class="danger ? 'btn-danger' : 'btn-primary'"
        :disabled="loading"
        @click="emit('confirm')"
      >
        <Loader2 v-if="loading" class="h-3.5 w-3.5 animate-spin" />
        {{ confirmText }}
      </button>
    </template>
  </AppDialog>
</template>
