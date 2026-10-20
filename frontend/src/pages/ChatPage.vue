<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import {
  MessageSquarePlus,
  PanelRightClose,
  PanelRightOpen,
  Quote,
  X,
} from '@lucide/vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { apiGet } from '@/lib/api'
import ChatComposer from '@/features/chat/ChatComposer.vue'
import ChatEmpty from '@/features/chat/ChatEmpty.vue'
import ChatMessage from '@/features/chat/ChatMessage.vue'
import CitationRail from '@/features/chat/CitationRail.vue'
import BrandMark from '@/components/brand/BrandMark.vue'
import { useChatStore } from '@/stores/chat'
import { useUiStore } from '@/stores/ui'
import type { KnowledgeBase } from '@/types/api'

const chat = useChatStore()
const ui = useUiStore()

const { data: kbs } = useQuery({
  queryKey: ['kbs'],
  queryFn: () => apiGet<KnowledgeBase[]>('/api/kbs'),
  staleTime: 30_000,
})

const scrollArea = ref<HTMLElement | null>(null)
const composer = ref<{ focus: () => void } | null>(null)
const stickToBottom = ref(true)

const citations = computed(() => chat.latestCitations)
const lastAssistantId = computed(() => chat.lastAssistant?.id ?? null)
const hasCitations = computed(() => citations.value.length > 0)
const sessionTitle = computed(() => chat.currentSession?.title || '新对话')

function onScroll() {
  const element = scrollArea.value
  if (!element) return
  stickToBottom.value = element.scrollHeight - element.scrollTop - element.clientHeight < 160
}

async function scrollToBottom(force = false) {
  await nextTick()
  const element = scrollArea.value
  if (!element || (!force && !stickToBottom.value)) return
  element.scrollTo({ top: element.scrollHeight, behavior: force ? 'smooth' : 'auto' })
}

async function send(question: string) {
  stickToBottom.value = true
  await chat.send(question)
  void scrollToBottom(true)
}

function pickSuggestion(question: string) {
  void send(question)
}

function newChat() {
  chat.newSession()
  ui.citationRailOpen = false
  nextTick(() => {
    composer.value?.focus()
    scrollToBottom(true)
  })
}

function handleCite(index: number) {
  chat.setActiveCitation(index)
  if (window.innerWidth < 1280) {
    ui.setMobileCitations(true)
  } else {
    ui.citationRailOpen = true
  }
  nextTick(() => {
    document
      .getElementById(`citation-${index}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  })
}

function retry() {
  chat.retry()
  void scrollToBottom(true)
}

watch(
  [
    () => chat.messages.length,
    () => chat.lastAssistant?.content.length ?? 0,
    () => chat.lastAssistant?.citations.length ?? 0,
  ],
  () => {
    if (stickToBottom.value) void scrollToBottom()
  },
)

watch(
  hasCitations,
  (value) => {
    if (value) ui.citationRailOpen = true
  },
  { immediate: true },
)

watch(
  () => chat.currentId,
  () => {
    stickToBottom.value = true
    void scrollToBottom(true)
  },
)

onMounted(async () => {
  await chat.hydrate()
  await scrollToBottom(true)
  composer.value?.focus()
})

onBeforeUnmount(() => {
  ui.setMobileCitations(false)
})
</script>

<template>
  <div class="flex h-full min-h-0 flex-col overflow-hidden bg-white">
    <header class="flex h-14 shrink-0 items-center justify-between gap-3 px-5 lg:px-8">
      <div class="min-w-0">
        <h1 class="truncate text-[14px] font-semibold text-ink">{{ sessionTitle }}</h1>
        <p class="mt-0.5 hidden text-[11px] text-ink-3 sm:block">
          基于知识库的检索增强回答，每个角标都可回溯原文
        </p>
      </div>

      <div class="flex shrink-0 items-center gap-1.5">
        <button
          v-if="hasCitations"
          type="button"
          class="btn btn-secondary btn-sm xl:hidden"
          @click="ui.setMobileCitations(true)"
        >
          <Quote class="h-3.5 w-3.5" />
          引用 {{ citations.length }}
        </button>

        <button
          type="button"
          class="btn btn-secondary btn-sm hidden xl:inline-flex"
          :aria-label="ui.citationRailOpen ? '收起引用栏' : '打开引用栏'"
          :title="ui.citationRailOpen ? '收起引用栏' : '打开引用栏'"
          @click="ui.toggleCitationRail()"
        >
          <PanelRightClose v-if="ui.citationRailOpen" class="h-3.5 w-3.5" />
          <PanelRightOpen v-else class="h-3.5 w-3.5" />
          {{ ui.citationRailOpen ? '收起引用' : '引用' }}
          <span v-if="hasCitations" class="font-mono text-[10px] text-ink-3">
            {{ citations.length }}
          </span>
        </button>

        <button
          type="button"
          class="btn btn-secondary btn-sm lg:hidden"
          aria-label="新对话"
          @click="newChat"
        >
          <MessageSquarePlus class="h-3.5 w-3.5" />
          <span class="hidden sm:inline">新对话</span>
        </button>
      </div>
    </header>

    <div class="flex min-h-0 flex-1">
      <section class="flex min-w-0 flex-1 flex-col">
        <div
          ref="scrollArea"
          class="min-h-0 flex-1 overflow-y-auto px-5 lg:px-8"
          @scroll.passive="onScroll"
        >
          <div class="mx-auto w-full max-w-[820px] py-4">
            <ChatEmpty v-if="!chat.hasMessages" @pick="pickSuggestion" />

            <div v-else class="space-y-7 pb-4">
              <ChatMessage
                v-for="message in chat.messages"
                :key="message.id"
                :message="message"
                :active-citation="message.id === lastAssistantId ? chat.activeCitation : null"
                @cite="handleCite"
                @retry="retry"
              />
            </div>
          </div>
        </div>

        <div class="shrink-0 px-5 pb-5 pt-2 lg:px-8">
          <div class="mx-auto w-full max-w-[820px]">
            <div
              v-if="chat.lastError && !chat.streaming"
              class="mb-2 flex items-center justify-between gap-3 rounded-lg border border-danger/20 bg-[#fef2f2] px-3 py-2"
            >
              <p class="text-[11.5px] text-danger">{{ chat.lastError }}</p>
              <button
                type="button"
                class="text-[11px] font-medium text-ink-2 hover:text-ink"
                @click="retry"
              >
                重试
              </button>
            </div>

            <ChatComposer
              ref="composer"
              :kbs="kbs ?? []"
              :selected-ids="chat.selectedKbIds"
              :streaming="chat.streaming"
              @send="send"
              @stop="chat.stop()"
              @update:selected-ids="chat.setScope"
            />

            <p class="mt-2 text-center text-[10.5px] text-ink-3">
              每次提问独立检索 · 历史仅保存在本地
            </p>
          </div>
        </div>
      </section>

      <aside
        v-if="ui.citationRailOpen"
        class="hidden w-[340px] shrink-0 border-l border-line bg-[#fbfcfd] xl:block"
      >
        <CitationRail
          :citations="citations"
          :active-index="chat.activeCitation"
          @select="chat.setActiveCitation"
        />
      </aside>
    </div>

    <Teleport to="body">
      <Transition name="modal">
        <div v-if="ui.mobileCitationsOpen" class="fixed inset-0 z-50 xl:hidden">
          <div class="absolute inset-0 bg-[#0f1115]/30" @click="ui.setMobileCitations(false)" />
          <div
            class="modal-card absolute inset-x-0 bottom-0 flex h-[76dvh] flex-col overflow-hidden rounded-t-[20px] border-t border-line bg-white shadow-2xl"
          >
            <div class="mx-auto mt-2 h-1 w-10 rounded-full bg-surface-3" />
            <div class="flex items-center justify-between px-4 pt-2">
              <div class="flex items-center gap-2">
                <BrandMark :size="22" :with-wordmark="false" />
                <span class="text-[12px] font-semibold text-ink">引用来源</span>
                <span class="chip h-5 px-1.5 text-[10px]">{{ citations.length }}</span>
              </div>
              <button
                type="button"
                class="icon-btn"
                aria-label="关闭引用"
                @click="ui.setMobileCitations(false)"
              >
                <X class="h-4 w-4" />
              </button>
            </div>
            <div class="min-h-0 flex-1">
              <CitationRail
                hide-header
                :citations="citations"
                :active-index="chat.activeCitation"
                @select="chat.setActiveCitation"
              />
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
