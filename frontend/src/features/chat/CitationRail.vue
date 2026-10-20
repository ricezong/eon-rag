<script setup lang="ts">
import { ChevronDown, Loader2, Quote } from '@lucide/vue'
import { reactive, ref } from 'vue'
import { apiGet } from '@/lib/api'
import { metaTags } from '@/lib/format'
import { renderMarkdown, stripLeadingTitle } from '@/lib/markdown'
import type { Citation, ParentChunk } from '@/types/api'

const props = defineProps<{
  citations: Citation[]
  activeIndex: number | null
  hideHeader?: boolean
}>()

const emit = defineEmits<{ (event: 'select', index: number): void }>()

interface SourceState {
  loading: boolean
  content?: string
  title?: string | null
  error?: string
}

const sources = reactive<Record<string, SourceState>>({})
const expanded = ref<number | null>(null)

function maxScoreOf(citation: Citation): number {
  return citation.children.length
    ? Math.max(...citation.children.map((child) => child.score))
    : 0
}

function scoreOf(citation: Citation): number {
  return maxScoreOf(citation)
}

function barWidth(citation: Citation): string {
  return `${Math.max(6, Math.round(scoreOf(citation) * 100))}%`
}

async function toggleSource(citation: Citation) {
  if (expanded.value === citation.index) {
    expanded.value = null
    return
  }
  expanded.value = citation.index
  if (sources[citation.parentId]) return

  sources[citation.parentId] = { loading: true }
  try {
    const chunk = await apiGet<ParentChunk>(
      `/api/documents/${citation.documentId}/parent-chunks/${citation.parentId}`,
    )
    sources[citation.parentId] = {
      loading: false,
      content: chunk.content,
      title: chunk.title,
    }
  } catch (error) {
    sources[citation.parentId] = {
      loading: false,
      error: error instanceof Error ? error.message : '加载失败',
    }
  }
}

function select(citation: Citation) {
  emit('select', citation.index)
  document
    .getElementById(`citation-${citation.index}`)
    ?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col">
    <div v-if="!hideHeader" class="flex items-center justify-between px-4 pb-3 pt-4">
      <div class="flex items-center gap-2">
        <Quote class="h-3.5 w-3.5 text-brand" />
        <span class="text-[12px] font-semibold text-ink">引用来源</span>
        <span class="rounded-md border border-line bg-surface-2 px-1.5 text-[10px] text-ink-3">
          {{ citations.length }}
        </span>
      </div>
    </div>

    <div class="min-h-0 flex-1 overflow-y-auto px-3 pb-5" :class="hideHeader ? 'pt-2' : ''">
      <div v-if="citations.length === 0" class="flex h-full flex-col items-center justify-center px-6 text-center">
        <div class="mb-3 flex h-12 w-12 items-center justify-center rounded-2xl border border-line bg-surface-2">
          <Quote class="h-5 w-5 text-ink-3" />
        </div>
        <p class="text-[12px] font-medium text-ink-2">暂无引用</p>
        <p class="mt-1 text-[11px] leading-relaxed text-ink-3">
          回答中的角标会在这里展开为可追溯的原文片段。
        </p>
      </div>

      <div v-else class="space-y-2.5">
        <article
          v-for="citation in citations"
          :id="`citation-${citation.index}`"
          :key="citation.parentId"
          class="cursor-pointer rounded-xl border bg-white p-3 transition-all duration-200"
          :class="
            activeIndex === citation.index
              ? 'border-[#9dbcf7] bg-brand-soft'
              : 'border-line hover:border-line-strong hover:bg-surface-2'
          "
          @click="select(citation)"
        >
          <div class="flex items-start gap-2.5">
            <span
              class="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md border border-[#cfe0ff] bg-brand-soft text-[11px] font-semibold text-brand-2"
            >
              {{ citation.index }}
            </span>

            <div class="min-w-0 flex-1">
              <h3 class="truncate text-[12px] font-semibold text-ink">
                {{ citation.title || '未命名章节' }}
              </h3>

              <div class="mt-1.5 flex flex-wrap gap-1">
                <span
                  v-for="tag in metaTags(citation.meta)"
                  :key="tag"
                  class="rounded border border-line bg-surface-2/80 px-1.5 py-0.5 text-[10px] text-ink-3"
                >
                  {{ tag }}
                </span>
                <span
                  v-if="citation.children.length"
                  class="rounded border border-cyan-soft bg-cyan-soft px-1.5 py-0.5 text-[10px] text-brand-2"
                >
                  命中 {{ citation.children.length }} 段
                </span>
              </div>

              <div class="mt-2.5 flex items-center gap-2">
                <div class="h-1 flex-1 overflow-hidden rounded-full bg-surface-3">
                  <div
                    class="h-full rounded-full bg-brand"
                    :style="{ width: barWidth(citation) }"
                  />
                </div>
                <span class="font-mono text-[10px] text-ink-3">
                  {{ scoreOf(citation).toFixed(3) }}
                </span>
              </div>

              <button
                type="button"
                class="mt-2 flex items-center gap-1 text-[10.5px] text-ink-2 transition hover:text-brand-2"
                @click.stop="toggleSource(citation)"
              >
                <ChevronDown
                  class="h-3 w-3 transition-transform"
                  :class="expanded === citation.index ? 'rotate-180' : ''"
                />
                {{ expanded === citation.index ? '收起原文' : '查看原文' }}
              </button>

              <div
                v-if="expanded === citation.index"
                class="mt-2 rounded-lg border border-line bg-surface-2 p-2.5"
                @click.stop
              >
                <div v-if="sources[citation.parentId]?.loading" class="flex items-center gap-2 text-[11px] text-ink-3">
                  <Loader2 class="h-3 w-3 animate-spin" />
                  加载原文…
                </div>
                <p
                  v-else-if="sources[citation.parentId]?.error"
                  class="text-[11px] text-danger"
                >
                  {{ sources[citation.parentId]?.error }}
                </p>
                <div
                  v-else
                  class="chat-markdown source-preview max-h-56 overflow-y-auto"
                  v-html="
                    renderMarkdown(
                      stripLeadingTitle(
                        sources[citation.parentId]?.content ?? '',
                        sources[citation.parentId]?.title,
                      ),
                    )
                  "
                />
              </div>
            </div>
          </div>
        </article>
      </div>
    </div>
  </div>
</template>
