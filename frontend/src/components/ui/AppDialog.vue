<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { X } from '@lucide/vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title?: string
    width?: 'sm' | 'md' | 'lg' | 'xl'
    closeable?: boolean
  }>(),
  { width: 'md', closeable: true },
)

const emit = defineEmits<{ (event: 'update:open', value: boolean): void }>()

const card = ref<HTMLElement | null>(null)
let previousActive: HTMLElement | null = null

const widths: Record<string, string> = {
  sm: 'max-w-sm',
  md: 'max-w-lg',
  lg: 'max-w-2xl',
  xl: 'max-w-4xl',
}

function close() {
  if (props.closeable) emit('update:open', false)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') close()
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      previousActive = document.activeElement as HTMLElement | null
      document.body.style.overflow = 'hidden'
      window.addEventListener('keydown', onKeydown)
      await nextTick()
      card.value
        ?.querySelector<HTMLElement>('[autofocus], input, textarea, select, button')
        ?.focus()
    } else {
      document.body.style.overflow = ''
      window.removeEventListener('keydown', onKeydown)
      previousActive?.focus?.()
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="open"
        class="fixed inset-0 z-50 flex items-end justify-center p-4 sm:items-center"
        role="presentation"
      >
        <div class="absolute inset-0 bg-[#0f1115]/30" @click="close" />

        <div
          ref="card"
          class="modal-card relative flex max-h-[90dvh] w-full flex-col overflow-hidden rounded-[20px] border border-line bg-white shadow-[0_24px_64px_-24px_rgba(15,17,21,.25)]"
          :class="widths[width]"
          role="dialog"
          aria-modal="true"
          :aria-label="title || '对话框'"
        >
          <header
            v-if="title"
            class="flex items-start justify-between gap-4 border-b border-line px-5 py-4"
          >
            <div>
              <h2 class="text-[15px] font-semibold tracking-tight text-ink">{{ title }}</h2>
              <p v-if="$slots.subtitle" class="mt-1 text-[12px] text-ink-2">
                <slot name="subtitle" />
              </p>
            </div>
            <button
              v-if="closeable"
              type="button"
              class="icon-btn -mr-1 -mt-1"
              aria-label="关闭"
              @click="close"
            >
              <X class="h-4 w-4" />
            </button>
          </header>

          <div class="min-h-0 flex-1 overflow-y-auto px-5 py-4">
            <slot />
          </div>

          <footer
            v-if="$slots.footer"
            class="flex items-center justify-end gap-2 border-t border-line/80 px-5 py-3.5"
          >
            <slot name="footer" />
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
