<script setup lang="ts">
import { Check, Copy, RotateCcw } from '@lucide/vue'
import { computed, nextTick, ref, watch } from 'vue'
import BrandMark from '@/components/brand/BrandMark.vue'
import { toPlainText, renderMarkdown } from '@/lib/markdown'
import { useToastStore } from '@/stores/toast'
import type { ChatMessage } from '@/types/chat'

const props = defineProps<{
  message: ChatMessage
  activeCitation: number | null
}>()

const emit = defineEmits<{
  (event: 'cite', index: number): void
  (event: 'retry'): void
}>()

const toast = useToastStore()
const body = ref<HTMLElement | null>(null)
const copied = ref(false)

const html = computed(() => (props.message.role === 'assistant' ? renderMarkdown(props.message.content) : ''))
const time = computed(() =>
  new Date(props.message.createdAt).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  }),
)

watch(
  [() => props.message.content, () => props.activeCitation],
  async () => {
    await nextTick()
    body.value?.querySelectorAll<HTMLElement>('.cite').forEach((element) => {
      element.classList.toggle('is-active', Number(element.dataset.cite) === props.activeCitation)
    })
  },
  { immediate: true },
)

function onClick(event: MouseEvent) {
  const target = (event.target as HTMLElement).closest<HTMLElement>('[data-cite]')
  if (!target?.dataset.cite) return
  const index = Number(target.dataset.cite)
  if (Number.isFinite(index)) emit('cite', index)
}

async function copy() {
  try {
    await navigator.clipboard.writeText(toPlainText(props.message.content))
    copied.value = true
    window.setTimeout(() => (copied.value = false), 1600)
    toast.success('已复制回答')
  } catch {
    toast.error('复制失败', '当前浏览器未授予剪贴板权限')
  }
}
</script>

<template>
  <div v-if="message.role === 'user'" class="flex justify-end">
    <div
      class="max-w-[76%] rounded-2xl rounded-br-md border border-[#dce8ff] bg-[#edf3fe] px-4 py-2.5 text-[14px] leading-relaxed text-ink"
    >
      {{ message.content }}
    </div>
  </div>

  <article v-else class="group flex gap-3">
    <div class="pt-0.5">
      <BrandMark :size="28" :with-wordmark="false" />
    </div>

    <div class="min-w-0 flex-1">
      <span class="sr-only" role="status">
        {{
          message.status === 'streaming'
            ? '正在生成回答'
            : message.status === 'done'
              ? '回答生成完成'
              : message.status === 'error'
                ? '回答生成失败'
                : '已停止生成'
        }}
      </span>

      <div class="mb-1.5 flex items-center gap-2">
        <span class="text-[12.5px] font-semibold text-ink">Eon</span>
        <span class="text-[10.5px] text-ink-3">{{ time }}</span>
        <span
          v-if="message.status === 'stopped'"
          class="rounded-full border border-line px-2 py-0.5 text-[10px] text-ink-3"
        >
          已停止
        </span>
      </div>

      <div v-if="message.content" ref="body" class="relative" @click="onClick">
        <div class="chat-markdown" v-html="html" />
        <span
          v-if="message.status === 'streaming'"
          class="stream-caret ml-0.5 inline-block h-[15px] w-[2px] translate-y-[3px] rounded-full bg-brand-2"
        />
      </div>

      <div v-else-if="message.status === 'streaming'" class="flex items-center gap-1.5 py-2">
        <span class="typing-dot" />
        <span class="typing-dot" />
        <span class="typing-dot" />
        <span class="ml-2 text-[11.5px] text-ink-3">正在检索知识库…</span>
      </div>

      <div
        v-else-if="message.status === 'stopped'"
        class="rounded-xl border border-line bg-surface-2 px-3 py-2 text-[12px] text-ink-3"
      >
        已停止生成。
      </div>

      <div
        v-if="message.status === 'error'"
        class="mt-3 flex items-start justify-between gap-3 rounded-xl border border-danger/30 bg-danger/10 px-3.5 py-3"
      >
        <div class="min-w-0">
          <p class="text-[12px] font-semibold text-danger">生成失败</p>
          <p class="mt-0.5 break-words text-[11.5px] text-ink-2">
            {{ message.error || '请稍后重试' }}
          </p>
        </div>
        <button type="button" class="btn btn-secondary btn-sm shrink-0" @click="emit('retry')">
          <RotateCcw class="h-3 w-3" />
          重试
        </button>
      </div>

      <div
        v-if="message.status === 'done'"
        class="mt-2.5 flex items-center gap-1 opacity-0 transition-opacity duration-150 group-hover:opacity-100"
      >
        <button
          type="button"
          class="flex items-center gap-1 rounded-md px-1.5 py-1 text-[10.5px] text-ink-3 transition hover:bg-surface-2 hover:text-ink-2"
          @click="copy"
        >
          <Check v-if="copied" class="h-3 w-3" />
          <Copy v-else class="h-3 w-3" />
          {{ copied ? '已复制' : '复制' }}
        </button>
      </div>
    </div>
  </article>
</template>

<style scoped>
.typing-dot {
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: #4176e6;
  animation: typing 1.1s ease-in-out infinite;
}

.typing-dot:nth-child(2) {
  animation-delay: 0.14s;
}

.typing-dot:nth-child(3) {
  animation-delay: 0.28s;
}

@keyframes typing {
  0%,
  60%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

.stream-caret {
  animation: caret 1s steps(2, start) infinite;
}

@keyframes caret {
  50% {
    opacity: 0;
  }
}
</style>

