<script setup lang="ts">
import { ArrowUp, ChevronDown, Database, Square } from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { KnowledgeBase } from '@/types/api'

const props = defineProps<{
  kbs: KnowledgeBase[]
  selectedIds: string[]
  streaming: boolean
}>()

const emit = defineEmits<{
  (event: 'send', question: string): void
  (event: 'stop'): void
  (event: 'update:selectedIds', ids: string[]): void
}>()

const text = ref('')
const textarea = ref<HTMLTextAreaElement | null>(null)
const scopeOpen = ref(false)
const triggerRef = ref<HTMLElement | null>(null)
const panelRef = ref<HTMLElement | null>(null)

const scopeLabel = computed(() => {
  const count = props.selectedIds.length
  if (count === 0) return '全部知识库'
  if (count === 1) {
    const kb = props.kbs.find((item) => item.id === props.selectedIds[0])
    return kb?.name ?? '1 个知识库'
  }
  return `${count} 个知识库`
})

const canSend = computed(() => text.value.trim().length > 0 && !props.streaming)

function resize() {
  const element = textarea.value
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 160)}px`
}

watch(text, () => nextTick(resize))

function submit() {
  if (!canSend.value) return
  const question = text.value.trim()
  emit('send', question)
  text.value = ''
  nextTick(() => {
    resize()
    textarea.value?.focus()
  })
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  submit()
}

function toggleScope() {
  scopeOpen.value = !scopeOpen.value
}

function selectAll() {
  emit('update:selectedIds', [])
}

function toggleKb(id: string) {
  const next = props.selectedIds.includes(id)
    ? props.selectedIds.filter((item) => item !== id)
    : [...props.selectedIds, id]
  emit('update:selectedIds', next)
}

function onDocumentClick(event: MouseEvent) {
  if (!scopeOpen.value) return
  const target = event.target as Node
  if (triggerRef.value?.contains(target) || panelRef.value?.contains(target)) return
  scopeOpen.value = false
}

onMounted(() => document.addEventListener('mousedown', onDocumentClick))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocumentClick))

defineExpose({
  focus: () => textarea.value?.focus(),
})
</script>

<template>
  <div
    class="relative rounded-[22px] border border-line bg-white p-2.5 shadow-[0_2px_12px_rgba(15,17,21,.05)] transition focus-within:border-[#9dbcf7] focus-within:shadow-[0_0_0_3px_rgba(65,118,230,.08),0_2px_12px_rgba(15,17,21,.05)]"
  >
    <textarea
      ref="textarea"
      v-model="text"
      data-testid="composer"
      class="max-h-40 w-full resize-none bg-transparent px-3 pb-2 pt-2 text-[14px] leading-relaxed text-ink outline-none placeholder:text-ink-3"
      rows="1"
      aria-label="输入问题"
      placeholder="问点什么…"
      @keydown="onKeydown"
      @input="resize"
    />

    <div class="flex items-center justify-between gap-2 px-1.5 pb-0.5">
      <button
        ref="triggerRef"
        type="button"
        class="flex h-7 items-center gap-1.5 rounded-lg border border-line bg-surface-2 px-2.5 text-[11px] text-ink-2 transition hover:border-line-strong hover:text-ink"
        :aria-expanded="scopeOpen"
        @click="toggleScope"
      >
        <Database class="h-3 w-3 text-brand" />
        <span class="max-w-[150px] truncate">{{ scopeLabel }}</span>
        <ChevronDown class="h-3 w-3 text-ink-3" :class="scopeOpen ? 'rotate-180' : ''" />
      </button>

      <div class="flex items-center gap-2">
        <span class="hidden text-[10px] text-ink-3 sm:inline">Enter 发送 · Shift + Enter 换行</span>

        <button
          v-if="streaming"
          type="button"
          class="flex h-8 w-8 items-center justify-center rounded-lg border border-[#fecaca] bg-[#fef2f2] text-danger transition hover:bg-[#fee2e2]"
          aria-label="停止生成"
          @click="emit('stop')"
        >
          <Square class="h-3 w-3 fill-current" />
        </button>

        <button
          v-else
          type="button"
          data-testid="send"
          class="flex h-8 w-8 items-center justify-center rounded-lg text-white transition disabled:bg-surface-3 disabled:text-ink-3"
          :class="canSend ? 'bg-ink hover:bg-[#2c2c2e]' : 'bg-surface-3'"
          :disabled="!canSend"
          aria-label="发送"
          @click="submit"
        >
          <ArrowUp class="h-4 w-4" :stroke-width="2" />
        </button>
      </div>
    </div>

    <Transition name="pop">
      <div
        v-if="scopeOpen"
        ref="panelRef"
        class="absolute bottom-[calc(100%+8px)] left-2 z-30 w-[276px] overflow-hidden rounded-xl border border-line bg-white shadow-[0_12px_32px_rgba(15,17,21,.12)]"
      >
        <div class="flex items-center justify-between border-b border-line px-3 py-2">
          <span class="text-[11px] font-medium text-ink-2">检索范围</span>
          <button
            type="button"
            class="text-[10.5px] text-ink-3 transition hover:text-brand"
            @click="selectAll"
          >
            全部
          </button>
        </div>

        <div class="max-h-64 overflow-y-auto p-1.5">
          <label
            class="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-[12px] text-ink-2 transition hover:bg-surface-2"
          >
            <input
              type="checkbox"
              class="accent-[#4176e6]"
              :checked="selectedIds.length === 0"
              @change="selectAll"
            />
            <span>全部知识库</span>
          </label>

          <label
            v-for="kb in kbs"
            :key="kb.id"
            class="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-1.5 text-[12px] text-ink-2 transition hover:bg-surface-2"
          >
            <input
              type="checkbox"
              class="accent-[#4176e6]"
              :checked="selectedIds.includes(kb.id)"
              @change="toggleKb(kb.id)"
            />
            <span class="h-1.5 w-1.5 rounded-full" :style="{ background: kb.color }" />
            <span class="truncate">{{ kb.name }}</span>
          </label>

          <p v-if="kbs.length === 0" class="px-2 py-3 text-[11px] text-ink-3">
            暂无知识库，上传文档后会自动创建关联。
          </p>
        </div>
      </div>
    </Transition>
  </div>
</template>
