<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { GitMerge, Loader2, Pencil, Trash2 } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { apiDelete, apiGet, apiPost } from '@/lib/api'
import { renderMarkdown } from '@/lib/markdown'
import { CHILD_MERGE_MAX_TOKENS } from '@/lib/tokens'
import { useTokenCounts } from '@/lib/tokenCount'
import { useToastStore } from '@/stores/toast'
import type { ChildChunk } from '@/types/api'
import ChildChunkEditDialog from './ChildChunkEditDialog.vue'

const props = defineProps<{
  documentId: string
  parentId: string
  indexed: boolean
  editable: boolean
}>()

const queryClient = useQueryClient()
const toast = useToastStore()

const selected = ref<string[]>([])
const expanded = ref<string[]>([])
const editing = ref<ChildChunk | null>(null)
const editOpen = ref(false)
const deleteTarget = ref<ChildChunk | null>(null)
const deleting = ref(false)
const mergeOpen = ref(false)
const merging = ref(false)

const query = useQuery({
  queryKey: ['child-chunks', props.parentId],
  queryFn: () =>
    apiGet<ChildChunk[]>(
      `/api/documents/${props.documentId}/parent-chunks/${props.parentId}/children`,
    ),
})

const children = computed(() => query.data.value ?? [])

const selectedChildren = computed(() =>
  selected.value
    .map((id) => children.value.find((child) => child.id === id))
    .filter((child): child is ChildChunk => Boolean(child))
    .sort((a, b) => a.seq - b.seq),
)

const canMerge = computed(() => {
  if (selectedChildren.value.length < 2) return false
  for (let i = 1; i < selectedChildren.value.length; i += 1) {
    if (selectedChildren.value[i].seq !== selectedChildren.value[i - 1].seq + 1) return false
  }
  return true
})

const selectedTokensFallback = computed(() =>
  selectedChildren.value.reduce((sum, child) => sum + child.tokenCount, 0),
)
const mergedSelectionText = computed(() =>
  selectedChildren.value.length >= 2 ? mergeContents(selectedChildren.value) : '',
)
const {
  counts: mergeCounts,
  exact: mergeExact,
  pending: mergePending,
} = useTokenCounts(
  () => [mergedSelectionText.value],
  () => [],
  () => selectedChildren.value.length >= 2,
)
const selectedTokens = computed(
  () => mergeCounts.value[0] ?? selectedTokensFallback.value,
)
const overMergeLimit = computed(() => selectedTokens.value > CHILD_MERGE_MAX_TOKENS)

/** 与后端 mergeContents 一致：拼接相邻子块时去掉滑窗重叠。 */
function mergeContents(children: ChildChunk[]): string {
  let merged = children[0].content.trim()
  for (let i = 1; i < children.length; i += 1) {
    const next = children[i].content.trim()
    const overlap = overlapLength(merged, next)
    merged += overlap > 0 ? next.slice(overlap) : `\n\n${next}`
  }
  return merged.trim()
}

function overlapLength(previous: string, next: string): number {
  const max = Math.min(previous.length, next.length)
  for (let length = max; length > 0; length -= 1) {
    if (previous.endsWith(next.slice(0, length))) return length
  }
  return 0
}

watch(children, (list) => {
  const ids = new Set(list.map((child) => child.id))
  selected.value = selected.value.filter((id) => ids.has(id))
  expanded.value = expanded.value.filter((id) => ids.has(id))
})

async function invalidate() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['child-chunks', props.parentId] }),
    queryClient.invalidateQueries({ queryKey: ['parent-chunks'] }),
    queryClient.invalidateQueries({ queryKey: ['document', props.documentId] }),
    queryClient.invalidateQueries({ queryKey: ['documents'] }),
  ])
}

function toggleSelected(id: string) {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((item) => item !== id)
    : [...selected.value, id]
}

function clearSelection() {
  selected.value = []
}

function isExpanded(id: string): boolean {
  return expanded.value.includes(id)
}

function toggleExpanded(id: string) {
  expanded.value = isExpanded(id)
    ? expanded.value.filter((item) => item !== id)
    : [...expanded.value, id]
}

function openEdit(child: ChildChunk) {
  editing.value = child
  editOpen.value = true
}

function askDelete(child: ChildChunk) {
  deleteTarget.value = child
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await apiDelete(
      `/api/documents/${props.documentId}/parent-chunks/${props.parentId}/children/${deleteTarget.value.id}`,
    )
    const wasLast = children.value.length === 1
    toast.success(
      '子块已删除',
      wasLast ? '父块已保留，但当前没有子块，无法被检索' : '剩余子块已重新编号',
    )
    deleteTarget.value = null
    await invalidate()
  } catch (error) {
    toast.error('删除失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    deleting.value = false
  }
}

async function confirmMerge() {
  if (!canMerge.value) return
  merging.value = true
  try {
    await apiPost<ChildChunk>(
      `/api/documents/${props.documentId}/parent-chunks/${props.parentId}/children/merge`,
      { childIds: selectedChildren.value.map((child) => child.id) },
    )
    toast.success(
      '子块已合并',
      props.indexed
        ? '已保留第一个子块 ID，并重建向量与全文索引'
        : '已合并，需在文档详情手动向量化',
    )
    mergeOpen.value = false
    selected.value = []
    await invalidate()
  } catch (error) {
    toast.error('合并失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    merging.value = false
  }
}
</script>

<template>
  <div class="mt-3 rounded-xl border border-line bg-surface-2/40">
    <div class="flex items-center justify-between border-b border-line/70 px-3 py-2">
      <span class="text-[10.5px] font-semibold uppercase tracking-[0.12em] text-ink-3">
        子块 · {{ children.length }}
      </span>
      <span class="text-[10px] text-ink-3">
        {{ editable ? '子块只影响检索，父块上下文不变' : '向量化进行中，暂不可编辑' }}
      </span>
    </div>

    <div v-if="query.isLoading.value" class="space-y-2 p-3">
      <div v-for="index in 2" :key="index" class="skeleton h-14 w-full" />
    </div>

    <div v-else-if="query.isError.value" class="px-3 py-6 text-center">
      <p class="text-[11.5px] text-danger">加载子块失败</p>
      <button type="button" class="btn btn-secondary btn-sm mt-3" @click="query.refetch()">
        重试
      </button>
    </div>

    <EmptyState
      v-else-if="children.length === 0"
      title="该父块没有子块"
      description="父块会保留，但不会被检索命中。可编辑父块并重新重建子块。"
      class="!py-8"
    >
      <template #icon>
        <GitMerge class="h-5 w-5 text-ink-3" />
      </template>
    </EmptyState>

    <template v-else>
      <div class="space-y-2 p-3">
        <article
          v-for="child in children"
          :key="child.id"
          class="rounded-lg border border-line bg-white p-2.5 transition hover:border-line-strong"
        >
          <div class="flex items-start gap-2.5">
            <input
              type="checkbox"
              class="mt-1 accent-[#4176e6]"
              :checked="selected.includes(child.id)"
              :disabled="!editable"
              :aria-label="`选择第 ${child.seq + 1} 个子块`"
              @change="toggleSelected(child.id)"
            />

            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-1.5">
                <span
                  class="flex h-5 min-w-5 items-center justify-center rounded border border-line bg-surface-2 px-1 font-mono text-[10px] text-ink-3"
                >
                  {{ child.seq + 1 }}
                </span>
                <span class="chip h-5 px-1.5 text-[10px]">{{ child.tokenCount }} tok</span>
                <span v-if="child.contextPrefix" class="chip h-5 px-1.5 text-[10px]">
                  含前缀
                </span>
                <span class="ml-auto flex items-center gap-0.5">
                  <button
                    type="button"
                    class="icon-btn h-6 w-6"
                    aria-label="编辑或拆分子块"
                    :title="editable ? '编辑或拆分' : '向量化进行中，暂不可编辑'"
                    :disabled="!editable"
                    @click="openEdit(child)"
                  >
                    <Pencil class="h-3 w-3" />
                  </button>
                  <button
                    type="button"
                    class="icon-btn h-6 w-6 hover:!text-danger"
                    aria-label="删除子块"
                    :title="editable ? '删除子块' : '向量化进行中，暂不可编辑'"
                    :disabled="!editable"
                    @click="askDelete(child)"
                  >
                    <Trash2 class="h-3 w-3" />
                  </button>
                </span>
              </div>

              <div class="relative mt-1.5">
                <div
                  class="chat-markdown source-preview"
                  :class="isExpanded(child.id) ? '' : 'max-h-[92px] overflow-hidden'"
                  v-html="renderMarkdown(child.content)"
                />
                <div
                  v-if="!isExpanded(child.id)"
                  class="pointer-events-none absolute inset-x-0 bottom-0 h-8 bg-gradient-to-t from-white to-transparent"
                />
              </div>

              <button
                type="button"
                class="mt-1 text-[10px] text-ink-3 transition hover:text-brand-2"
                @click="toggleExpanded(child.id)"
              >
                {{ isExpanded(child.id) ? '收起' : '展开全文' }}
              </button>
            </div>
          </div>
        </article>
      </div>

      <div
        v-if="selected.length > 0"
        class="flex flex-wrap items-center gap-2 border-t border-line/70 bg-surface-2/40 px-3 py-2"
      >
        <span class="text-[11px] text-ink-2">已选 {{ selectedChildren.length }} 个</span>
        <span class="text-[10px] text-ink-3">
          <template v-if="mergePending">计算中…</template>
          <template v-else>{{ mergeExact[0] ? '' : '≈' }}{{ selectedTokens }} tok</template>
        </span>
        <span v-if="!canMerge && selectedChildren.length >= 2" class="text-[10px] text-warning">
          只能合并连续子块
        </span>
        <span v-if="overMergeLimit" class="text-[10px] text-warning">
          超过合并上限 {{ CHILD_MERGE_MAX_TOKENS }} tok
        </span>
        <span class="ml-auto flex items-center gap-2">
          <button type="button" class="btn btn-ghost btn-sm" @click="clearSelection">
            清除
          </button>
          <button
            type="button"
            class="btn btn-primary btn-sm"
            :disabled="!editable || !canMerge || overMergeLimit || mergePending"
            @click="mergeOpen = true"
          >
            <GitMerge class="h-3.5 w-3.5" />
            合并选中
          </button>
        </span>
      </div>
    </template>

    <ChildChunkEditDialog
      v-model:open="editOpen"
      :document-id="documentId"
      :parent-id="parentId"
      :child="editing"
      :indexed="indexed"
      @saved="editing = null"
    />

    <ConfirmDialog
      :open="Boolean(deleteTarget)"
      title="删除子块"
      :message="
        deleteTarget
          ? children.length === 1
            ? '这是该父块最后一个子块。删除后父块会保留，但无法被检索命中。'
            : '删除后剩余子块会自动重新编号，父块上下文不变。'
          : ''
      "
      confirm-text="删除"
      danger
      :loading="deleting"
      @update:open="deleteTarget = null"
      @confirm="confirmDelete"
    />

    <ConfirmDialog
      :open="mergeOpen"
      title="合并子块"
      :message="`将合并选中的 ${selectedChildren.length} 个连续子块，保留第一个子块 ID；重叠文本会自动去重，${indexed ? '并重建向量。' : '合并后需手动向量化。'}`"
      confirm-text="合并"
      :loading="merging"
      @update:open="mergeOpen = false"
      @confirm="confirmMerge"
    />
  </div>
</template>

