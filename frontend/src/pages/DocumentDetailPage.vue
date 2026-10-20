<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  AlertTriangle,
  ArrowLeft,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Layers,
  Loader2,
  Pencil,
  RefreshCw,
  Trash2,
  Zap,
} from '@lucide/vue'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import FileTypeIcon from '@/components/ui/FileTypeIcon.vue'
import KbTags from '@/components/ui/KbTags.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import ChildChunkList from '@/features/documents/ChildChunkList.vue'
import ParentChunkEditDialog from '@/features/documents/ParentChunkEditDialog.vue'
import { apiDelete, apiGet, apiPost } from '@/lib/api'
import { formatBytes, formatDateTime, metaTags, parseMeta, ratioText } from '@/lib/format'
import { renderMarkdown, stripLeadingTitle } from '@/lib/markdown'
import { useToastStore } from '@/stores/toast'
import type { KnowledgeBase, PageResult, ParentChunk, RagDocument } from '@/types/api'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const toast = useToastStore()

const id = route.params.id as string
const page = ref(0)
const size = 10
const expanded = ref<string[]>([])
const expandedChildren = ref<string[]>([])
const deleteOpen = ref(false)
const deleting = ref(false)
const editingParent = ref<ParentChunk | null>(null)
const parentEditOpen = ref(false)
const deleteParentTarget = ref<ParentChunk | null>(null)
const deletingParent = ref(false)
const vectorizing = ref(false)

const documentQuery = useQuery({
  queryKey: ['document', id],
  queryFn: () => apiGet<RagDocument>(`/api/documents/${id}`),
  refetchInterval: (query) => {
    const status = query.state.data?.status
    return status === 'PROCESSING' || status === 'INDEXING' ? 2000 : false
  },
})

const chunksQuery = useQuery({
  queryKey: computed(() => ['parent-chunks', id, page.value]),
  queryFn: () =>
    apiGet<PageResult<ParentChunk>>(
      `/api/documents/${id}/parent-chunks?page=${page.value}&size=${size}`,
    ),
  enabled: computed(() => Boolean(documentQuery.data.value)),
})

const kbsQuery = useQuery({
  queryKey: ['kbs'],
  queryFn: () => apiGet<KnowledgeBase[]>('/api/kbs'),
  staleTime: 30_000,
})

const doc = computed(() => documentQuery.data.value)
const chunks = computed(() => chunksQuery.data.value?.items ?? [])
const total = computed(() => chunksQuery.data.value?.total ?? 0)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const kbs = computed(() => kbsQuery.data.value ?? [])
const indexed = computed(() => doc.value?.status === 'COMPLETED')
const editable = computed(() => {
  const status = doc.value?.status
  return status === 'CHUNKED' || status === 'INDEX_FAILED' || status === 'COMPLETED'
})
const canVectorize = computed(
  () =>
    Boolean(doc.value) &&
    (doc.value!.status === 'CHUNKED' || doc.value!.status === 'INDEX_FAILED') &&
    doc.value!.childCount > 0,
)

async function vectorize() {
  if (!doc.value || vectorizing.value) return
  vectorizing.value = true
  try {
    await apiPost(`/api/documents/${id}/index`)
    toast.success('已开始向量化', '完成后文档会自动参与检索')
    await Promise.all([
      documentQuery.refetch(),
      queryClient.invalidateQueries({ queryKey: ['documents'] }),
    ])
  } catch (error) {
    toast.error('向量化失败', error instanceof Error ? error.message : '请稍后重试')
    await documentQuery.refetch()
  } finally {
    vectorizing.value = false
  }
}

function toggleChunk(chunkId: string) {
  expanded.value = expanded.value.includes(chunkId)
    ? expanded.value.filter((item) => item !== chunkId)
    : [...expanded.value, chunkId]
}

function isExpanded(chunkId: string): boolean {
  return expanded.value.includes(chunkId)
}

function isChildrenExpanded(chunkId: string): boolean {
  return expandedChildren.value.includes(chunkId)
}

function toggleChildren(chunkId: string) {
  expandedChildren.value = isChildrenExpanded(chunkId)
    ? expandedChildren.value.filter((item) => item !== chunkId)
    : [...expandedChildren.value, chunkId]
}

function openParentEdit(parent: ParentChunk) {
  editingParent.value = parent
  parentEditOpen.value = true
}

async function confirmDeleteParent() {
  if (!deleteParentTarget.value) return
  deletingParent.value = true
  try {
    await apiDelete(`/api/documents/${id}/parent-chunks/${deleteParentTarget.value.id}`)
    toast.success('父块已删除', '关联子块已级联清理，剩余父块已重新编号')
    deleteParentTarget.value = null
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['parent-chunks'] }),
      queryClient.invalidateQueries({ queryKey: ['child-chunks'] }),
      queryClient.invalidateQueries({ queryKey: ['document', id] }),
      queryClient.invalidateQueries({ queryKey: ['documents'] }),
    ])
    if (page.value > 0 && chunks.value.length === 1) {
      page.value -= 1
    }
  } catch (error) {
    toast.error('父块删除失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    deletingParent.value = false
  }
}

async function confirmDelete() {
  deleting.value = true
  try {
    await apiDelete(`/api/documents/${id}`)
    toast.success('文档已删除')
    await queryClient.invalidateQueries({ queryKey: ['documents'] })
    await queryClient.invalidateQueries({ queryKey: ['kbs'] })
    router.push('/documents')
  } catch (error) {
    toast.error('删除失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    deleting.value = false
    deleteOpen.value = false
  }
}
</script>

<template>
  <div class="px-5 py-5 lg:px-8">
    <button
      type="button"
      class="mb-4 inline-flex items-center gap-1.5 text-[11.5px] text-ink-3 transition hover:text-ink"
      @click="router.push('/documents')"
    >
      <ArrowLeft class="h-3.5 w-3.5" />
      返回文档列表
    </button>

    <div v-if="documentQuery.isLoading.value" class="space-y-4">
      <div class="skeleton h-20 w-full" />
      <div class="skeleton h-28 w-full" />
    </div>

    <div
      v-else-if="documentQuery.isError.value"
      class="panel px-6 py-16 text-center"
    >
      <p class="text-[14px] text-danger">文档不存在或加载失败</p>
      <p class="mt-1 text-[12px] text-ink-3">
        {{ documentQuery.error.value instanceof Error ? documentQuery.error.value.message : '' }}
      </p>
      <button type="button" class="btn btn-secondary mt-4" @click="router.push('/documents')">
        返回列表
      </button>
    </div>

    <template v-else-if="doc">
      <header class="flex flex-wrap items-start justify-between gap-4">
        <div class="flex min-w-0 items-start gap-3.5">
          <FileTypeIcon :type="doc.fileType" :size="46" />
          <div class="min-w-0">
            <h1 class="truncate text-[21px] font-semibold tracking-tight text-ink">
              {{ doc.fileName }}
            </h1>
            <div class="mt-1.5 flex flex-wrap items-center gap-2">
              <StatusPill :status="doc.status" />
              <span class="text-[11px] text-ink-3">
                {{ doc.fileType.toUpperCase() }} · {{ formatBytes(doc.fileSize) }} ·
                {{ doc.parseMode }} · {{ formatDateTime(doc.createdAt) }}
              </span>
            </div>
            <div class="mt-2">
              <KbTags :kb-ids="doc.kbIds" :kbs="kbs" :limit="4" />
            </div>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <button
            v-if="canVectorize"
            type="button"
            class="btn btn-primary btn-sm"
            :disabled="vectorizing"
            @click="vectorize"
          >
            <Loader2 v-if="vectorizing" class="h-3.5 w-3.5 animate-spin" />
            <Zap v-else class="h-3.5 w-3.5" />
            向量化 {{ doc.childCount }} 个子块
          </button>
          <button
            v-else-if="doc.status === 'INDEXING'"
            type="button"
            class="btn btn-secondary btn-sm"
            disabled
          >
            <Loader2 class="h-3.5 w-3.5 animate-spin" />
            向量化中
          </button>
          <button
            type="button"
            class="btn btn-secondary btn-sm"
            :disabled="documentQuery.isFetching.value"
            @click="documentQuery.refetch()"
          >
            <RefreshCw
              class="h-3.5 w-3.5"
              :class="documentQuery.isFetching.value ? 'animate-spin' : ''"
            />
            刷新
          </button>
          <button type="button" class="btn btn-danger btn-sm" @click="deleteOpen = true">
            <Trash2 class="h-3.5 w-3.5" />
            删除
          </button>
        </div>
      </header>

      <div
        v-if="doc.status === 'PROCESSING'"
        class="mt-5 flex items-center gap-3 rounded-xl border border-cyan-soft bg-cyan-soft px-4 py-3"
      >
        <span class="relative flex h-2 w-2">
          <span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand-2 opacity-60" />
          <span class="relative inline-flex h-2 w-2 rounded-full bg-brand-2" />
        </span>
        <p class="text-[12px] text-brand-2">正在解析与分块，本页会自动刷新…</p>
      </div>

      <div
        v-if="doc.status === 'CHUNKED'"
        class="mt-5 flex flex-wrap items-center gap-3 rounded-xl border border-[#fef3c7] bg-[#fffbeb] px-4 py-3"
      >
        <Zap class="h-4 w-4 shrink-0 text-warning" />
        <p class="min-w-0 flex-1 text-[12px] leading-relaxed text-warning">
          切片已就绪，但尚未向量化，当前不会参与检索。请检查/编辑切片后点击“向量化”。
        </p>
        <button
          v-if="doc.childCount > 0"
          type="button"
          class="btn btn-primary btn-sm"
          :disabled="vectorizing"
          @click="vectorize"
        >
          <Loader2 v-if="vectorizing" class="h-3.5 w-3.5 animate-spin" />
          <Zap v-else class="h-3.5 w-3.5" />
          立即向量化
        </button>
      </div>

      <div
        v-if="doc.status === 'INDEXING'"
        class="mt-5 flex items-center gap-3 rounded-xl border border-[#dbeafe] bg-[#eff6ff] px-4 py-3"
      >
        <Loader2 class="h-4 w-4 shrink-0 animate-spin text-brand-2" />
        <p class="text-[12px] text-brand-2">
          正在向量化 {{ doc.childCount }} 个子块，完成后会自动参与检索…
        </p>
      </div>

      <div
        v-if="doc.status === 'INDEX_FAILED'"
        class="mt-5 flex items-start gap-3 rounded-xl border border-danger/20 bg-[#fef2f2] px-4 py-3"
      >
        <AlertTriangle class="mt-0.5 h-4 w-4 shrink-0 text-danger" />
        <div class="min-w-0 flex-1">
          <p class="text-[12px] font-medium text-danger">向量化失败，文档当前不可检索</p>
          <p v-if="doc.errorMessage" class="mt-1 whitespace-pre-line text-[11.5px] text-ink-2">
            {{ doc.errorMessage }}
          </p>
        </div>
        <button
          v-if="doc.childCount > 0"
          type="button"
          class="btn btn-primary btn-sm shrink-0"
          :disabled="vectorizing"
          @click="vectorize"
        >
          <Loader2 v-if="vectorizing" class="h-3.5 w-3.5 animate-spin" />
          <Zap v-else class="h-3.5 w-3.5" />
          重试
        </button>
      </div>

      <div
        v-if="doc.status === 'NEEDS_OCR' || doc.status === 'FAILED'"
        class="mt-5 flex items-start gap-3 rounded-xl border border-warning/25 bg-warning/[0.07] px-4 py-3"
      >
        <AlertTriangle class="mt-0.5 h-4 w-4 shrink-0 text-warning" />
        <div>
          <p class="text-[12px] font-medium text-warning">
            {{ doc.status === 'NEEDS_OCR' ? '未检测到有效文本层' : '文档解析失败' }}
          </p>
          <p class="mt-1 whitespace-pre-line text-[11.5px] leading-relaxed text-ink-2">
            {{ doc.errorMessage || '请检查文档内容或解析配置。' }}
          </p>
        </div>
      </div>

      <div class="mt-5 grid grid-cols-2 gap-2 lg:grid-cols-4">
        <div class="panel px-4 py-3">
          <p class="text-[10.5px] text-ink-3">父块</p>
          <p class="mt-1 font-display text-[22px] font-semibold leading-none text-ink">
            {{ doc.parentCount }}
          </p>
        </div>
        <div class="panel px-4 py-3">
          <p class="text-[10.5px] text-ink-3">子块</p>
          <p class="mt-1 font-display text-[22px] font-semibold leading-none text-ink">
            {{ doc.childCount }}
          </p>
        </div>
        <div class="panel px-4 py-3">
          <p class="text-[10.5px] text-ink-3">父子比</p>
          <p class="mt-1 font-display text-[22px] font-semibold leading-none text-brand-2">
            {{ ratioText(doc.parentCount, doc.childCount) }}
          </p>
        </div>
        <div class="panel px-4 py-3">
          <p class="text-[10.5px] text-ink-3">本页父块均 Token</p>
          <p class="mt-1 font-display text-[22px] font-semibold leading-none text-ink">
            {{
              doc.parentCount
                ? Math.round(
                    chunks.reduce((sum, chunk) => sum + chunk.tokenCount, 0) /
                      Math.max(1, chunks.length),
                  )
                : 0
            }}
          </p>
        </div>
      </div>

      <section class="mt-6">
        <div class="mb-3 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <Layers class="h-4 w-4 text-brand-2" />
            <h2 class="text-[13px] font-semibold text-ink">父块内容</h2>
            <span class="chip h-5 px-1.5 text-[10px]">{{ total }}</span>
          </div>

          <div class="flex items-center gap-1">
            <button
              type="button"
              class="icon-btn h-7 w-7"
              :disabled="page === 0"
              aria-label="上一页"
              @click="page = Math.max(0, page - 1)"
            >
              <ChevronLeft class="h-3.5 w-3.5" />
            </button>
            <span class="min-w-[54px] text-center font-mono text-[10.5px] text-ink-3">
              {{ page + 1 }} / {{ totalPages }}
            </span>
            <button
              type="button"
              class="icon-btn h-7 w-7"
              :disabled="page + 1 >= totalPages"
              aria-label="下一页"
              @click="page = Math.min(totalPages - 1, page + 1)"
            >
              <ChevronRight class="h-3.5 w-3.5" />
            </button>
          </div>
        </div>

        <div v-if="chunksQuery.isLoading.value" class="space-y-2">
          <div v-for="index in 3" :key="index" class="skeleton h-24 w-full" />
        </div>

        <EmptyState
          v-else-if="chunks.length === 0"
          title="暂无父块"
          description="文档还没有完成解析，或解析未产出有效切片。"
        >
          <template #icon>
            <Layers class="h-6 w-6 text-ink-3" />
          </template>
        </EmptyState>

        <div v-else class="space-y-2">
          <article
            v-for="chunk in chunks"
            :key="chunk.id"
            class="panel overflow-hidden transition hover:border-line-strong"
          >
            <div class="flex items-start gap-3 px-4 py-3">
              <span
                class="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-lg border border-line bg-surface-2 font-mono text-[10px] text-ink-3"
              >
                {{ chunk.seq + 1 }}
              </span>

              <div class="min-w-0 flex-1">
                <button
                  type="button"
                  class="flex w-full flex-wrap items-center gap-2 text-left"
                  @click="toggleChunk(chunk.id)"
                >
                  <span class="truncate text-[12.5px] font-medium text-ink">
                    {{ chunk.title || '未命名父块' }}
                  </span>
                  <span class="chip h-5 px-1.5 text-[10px]">{{ chunk.tokenCount }} tok</span>
                  <span
                    v-for="tag in metaTags(parseMeta(chunk.meta))"
                    :key="tag"
                    class="chip h-5 px-1.5 text-[10px]"
                  >
                    {{ tag }}
                  </span>
                </button>

                <div class="relative mt-2">
                  <div
                    class="chat-markdown source-preview"
                    :class="isExpanded(chunk.id) ? '' : 'max-h-[118px] overflow-hidden'"
                    v-html="renderMarkdown(stripLeadingTitle(chunk.content, chunk.title))"
                  />
                  <div
                    v-if="!isExpanded(chunk.id)"
                    class="pointer-events-none absolute inset-x-0 bottom-0 h-10 bg-gradient-to-t from-surface to-transparent"
                  />
                </div>

                <div class="mt-2 flex flex-wrap items-center gap-1 border-t border-line/60 pt-2">
                  <button
                    type="button"
                    class="btn btn-ghost btn-sm"
                    @click="toggleChunk(chunk.id)"
                  >
                    {{ isExpanded(chunk.id) ? '收起全文' : '展开全文' }}
                  </button>
                  <button
                    type="button"
                    class="btn btn-ghost btn-sm"
                    :disabled="!editable"
                    :title="editable ? '编辑父块' : '向量化进行中，暂不可编辑'"
                    @click="openParentEdit(chunk)"
                  >
                    <Pencil class="h-3 w-3" />
                    编辑父块
                  </button>
                  <button
                    type="button"
                    class="btn btn-ghost btn-sm hover:!text-danger"
                    :disabled="!editable"
                    :title="editable ? '删除父块' : '向量化进行中，暂不可编辑'"
                    @click="deleteParentTarget = chunk"
                  >
                    <Trash2 class="h-3 w-3" />
                    删除父块
                  </button>
                  <button
                    type="button"
                    class="btn btn-ghost btn-sm"
                    @click="toggleChildren(chunk.id)"
                  >
                    <ChevronDown
                      class="h-3 w-3 transition-transform"
                      :class="isChildrenExpanded(chunk.id) ? 'rotate-180' : ''"
                    />
                    {{ isChildrenExpanded(chunk.id) ? '收起子块' : '查看子块' }}
                  </button>
                </div>

                <ChildChunkList
                  v-if="isChildrenExpanded(chunk.id)"
                  :document-id="id"
                  :parent-id="chunk.id"
                  :indexed="indexed"
                  :editable="editable"
                />
              </div>
            </div>
          </article>
        </div>
      </section>

      <ParentChunkEditDialog
        v-model:open="parentEditOpen"
        :document-id="id"
        :parent="editingParent"
        :indexed="indexed"
        @saved="editingParent = null"
      />

      <ConfirmDialog
        :open="Boolean(deleteParentTarget)"
        title="删除父块"
        :message="`确定删除「${deleteParentTarget?.title || '未命名父块'}」吗？\n该父块下的全部子块会级联删除，相关引用将失效。`"
        confirm-text="删除"
        danger
        :loading="deletingParent"
        @update:open="deleteParentTarget = null"
        @confirm="confirmDeleteParent"
      />

      <ConfirmDialog
        :open="deleteOpen"
        title="删除文档"
        :message="`确定删除「${doc.fileName}」吗？\n关联的父块、子块与知识库绑定会一并清理。`"
        confirm-text="删除"
        danger
        :loading="deleting"
        @update:open="deleteOpen = false"
        @confirm="confirmDelete"
      />
    </template>
  </div>
</template>
