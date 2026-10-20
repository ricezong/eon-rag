<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  AlertTriangle,
  ArrowUpRight,
  CheckCircle2,
  Clock3,
  FilePlus2,
  RefreshCw,
  Search,
  Trash2,
  Zap,
} from '@lucide/vue'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import FileTypeIcon from '@/components/ui/FileTypeIcon.vue'
import KbTags from '@/components/ui/KbTags.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import UploadDialog from '@/features/documents/UploadDialog.vue'
import { apiDelete, apiGet } from '@/lib/api'
import { formatBytes, formatRelative, ratioText } from '@/lib/format'
import { useToastStore } from '@/stores/toast'
import type { DocumentStatus, KnowledgeBase, RagDocument } from '@/types/api'

const router = useRouter()
const queryClient = useQueryClient()
const toast = useToastStore()

const status = ref<'ALL' | DocumentStatus>('ALL')
const keyword = ref('')
const kbFilter = ref('ALL')
const uploadOpen = ref(false)
const pendingDelete = ref<RagDocument | null>(null)
const deleting = ref(false)

const documentsQuery = useQuery({
  queryKey: ['documents'],
  queryFn: () => apiGet<RagDocument[]>('/api/documents'),
  refetchInterval: (query) => {
    const data = query.state.data
    return Array.isArray(data) &&
      data.some((doc) => doc.status === 'PROCESSING' || doc.status === 'INDEXING')
      ? 2500
      : false
  },
})

const kbsQuery = useQuery({
  queryKey: ['kbs'],
  queryFn: () => apiGet<KnowledgeBase[]>('/api/kbs'),
  staleTime: 30_000,
})

const documents = computed(() => documentsQuery.data.value ?? [])
const kbs = computed(() => kbsQuery.data.value ?? [])

const stats = computed(() => {
  const all = documents.value
  return {
    total: all.length,
    processing: all.filter(
      (doc) => doc.status === 'PROCESSING' || doc.status === 'INDEXING',
    ).length,
    chunked: all.filter(
      (doc) => doc.status === 'CHUNKED' || doc.status === 'INDEX_FAILED',
    ).length,
    completed: all.filter((doc) => doc.status === 'COMPLETED').length,
    attention: all.filter((doc) => doc.status === 'NEEDS_OCR' || doc.status === 'FAILED').length,
  }
})

const statusTabs = computed(() => [
  { value: 'ALL' as const, label: '全部', count: stats.value.total },
  { value: 'PROCESSING' as const, label: '解析中', count: documents.value.filter((doc) => doc.status === 'PROCESSING').length },
  { value: 'CHUNKED' as const, label: '待向量化', count: documents.value.filter((doc) => doc.status === 'CHUNKED').length },
  { value: 'INDEXING' as const, label: '向量化中', count: documents.value.filter((doc) => doc.status === 'INDEXING').length },
  { value: 'INDEX_FAILED' as const, label: '向量化失败', count: documents.value.filter((doc) => doc.status === 'INDEX_FAILED').length },
  { value: 'COMPLETED' as const, label: '已完成', count: stats.value.completed },
  { value: 'NEEDS_OCR' as const, label: '需 OCR', count: documents.value.filter((doc) => doc.status === 'NEEDS_OCR').length },
  { value: 'FAILED' as const, label: '失败', count: documents.value.filter((doc) => doc.status === 'FAILED').length },
])

const filtered = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return documents.value.filter((doc) => {
    if (status.value !== 'ALL' && doc.status !== status.value) return false
    if (kbFilter.value !== 'ALL' && !(doc.kbIds ?? []).includes(kbFilter.value)) return false
    if (query && !doc.fileName.toLowerCase().includes(query)) return false
    return true
  })
})

function openDocument(doc: RagDocument) {
  router.push(`/documents/${doc.id}`)
}

function ratioTone(doc: RagDocument): string {
  const parent = doc.parentCount || 0
  const child = doc.childCount || 0
  if (!parent) return 'text-ink-3'
  const ratio = child / parent
  return ratio < 2 || ratio > 8 ? 'text-warning' : 'text-ink-2'
}

async function refresh() {
  await documentsQuery.refetch()
}

async function invalidateAll() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['documents'] }),
    queryClient.invalidateQueries({ queryKey: ['kbs'] }),
  ])
}

function askDelete(doc: RagDocument) {
  pendingDelete.value = doc
}

async function confirmDelete() {
  if (!pendingDelete.value) return
  deleting.value = true
  try {
    await apiDelete(`/api/documents/${pendingDelete.value.id}`)
    toast.success('文档已删除', '关联的父块与子块已级联清理')
    pendingDelete.value = null
    await invalidateAll()
  } catch (error) {
    toast.error('删除失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    deleting.value = false
  }
}

function onUploaded() {
  void invalidateAll()
}
</script>

<template>
  <div class="px-5 py-5 lg:px-8">
    <header class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 class="text-[22px] font-semibold tracking-tight text-ink">文档</h1>
        <p class="mt-1 text-[12px] text-ink-2">
          上传后自动解析与分块；手动向量化后才建立三路检索索引
        </p>
      </div>

      <div class="flex items-center gap-2">
        <button type="button" class="icon-btn" aria-label="刷新" title="刷新" @click="refresh">
          <RefreshCw class="h-4 w-4" :class="documentsQuery.isFetching.value ? 'animate-spin' : ''" />
        </button>
        <button type="button" class="btn btn-primary" @click="uploadOpen = true">
          <FilePlus2 class="h-4 w-4" />
          上传文档
        </button>
      </div>
    </header>

    <div class="mt-5 grid grid-cols-2 gap-2 lg:grid-cols-5">
      <div class="panel flex items-center gap-3 px-4 py-3">
        <div class="flex h-9 w-9 items-center justify-center rounded-xl border border-line bg-surface-2">
          <FilePlus2 class="h-4 w-4 text-ink-2" />
        </div>
        <div>
          <p class="font-display text-[20px] font-semibold leading-none text-ink">
            {{ stats.total }}
          </p>
          <p class="mt-1 text-[10.5px] text-ink-3">文档总数</p>
        </div>
      </div>

      <div class="panel flex items-center gap-3 px-4 py-3">
        <div class="flex h-9 w-9 items-center justify-center rounded-xl border border-cyan-soft bg-cyan-soft">
          <Clock3 class="h-4 w-4 text-brand-2" />
        </div>
        <div>
          <p class="font-display text-[20px] font-semibold leading-none text-brand-2">
            {{ stats.processing }}
          </p>
          <p class="mt-1 text-[10.5px] text-ink-3">处理中</p>
        </div>
      </div>

      <div class="panel flex items-center gap-3 px-4 py-3">
        <div class="flex h-9 w-9 items-center justify-center rounded-xl border border-warning/25 bg-warning/10">
          <Zap class="h-4 w-4 text-warning" />
        </div>
        <div>
          <p class="font-display text-[20px] font-semibold leading-none text-warning">
            {{ stats.chunked }}
          </p>
          <p class="mt-1 text-[10.5px] text-ink-3">待向量化</p>
        </div>
      </div>

      <div class="panel flex items-center gap-3 px-4 py-3">
        <div class="flex h-9 w-9 items-center justify-center rounded-xl border border-success/25 bg-success/10">
          <CheckCircle2 class="h-4 w-4 text-success" />
        </div>
        <div>
          <p class="font-display text-[20px] font-semibold leading-none text-success">
            {{ stats.completed }}
          </p>
          <p class="mt-1 text-[10.5px] text-ink-3">已完成</p>
        </div>
      </div>

      <div class="panel flex items-center gap-3 px-4 py-3">
        <div class="flex h-9 w-9 items-center justify-center rounded-xl border border-warning/25 bg-warning/10">
          <AlertTriangle class="h-4 w-4 text-warning" />
        </div>
        <div>
          <p class="font-display text-[20px] font-semibold leading-none text-warning">
            {{ stats.attention }}
          </p>
          <p class="mt-1 text-[10.5px] text-ink-3">需处理</p>
        </div>
      </div>
    </div>

    <div class="mt-5 flex flex-wrap items-center gap-2">
      <div class="flex flex-wrap items-center gap-1 rounded-xl border border-line bg-surface-2 p-1">
        <button
          v-for="tab in statusTabs"
          :key="tab.value"
          type="button"
          class="flex h-7 items-center gap-1.5 rounded-lg px-2.5 text-[11px] transition"
          :class="
            status === tab.value
              ? 'bg-surface-3 text-ink shadow-sm'
              : 'text-ink-3 hover:text-ink-2'
          "
          @click="status = tab.value"
        >
          {{ tab.label }}
          <span class="font-mono text-[10px] opacity-70">{{ tab.count }}</span>
        </button>
      </div>

      <div class="relative min-w-[180px] flex-1 sm:max-w-[240px]">
        <Search class="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-3" />
        <input v-model="keyword" class="input h-8 pl-8 text-[12px]" placeholder="按文件名搜索" />
      </div>

      <select v-model="kbFilter" class="select h-8 w-[150px] text-[12px]">
        <option value="ALL">全部知识库</option>
        <option v-for="kb in kbs" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
      </select>

      <span class="ml-auto text-[11px] text-ink-3">{{ filtered.length }} 条结果</span>
    </div>

    <div class="panel mt-3 overflow-hidden">
      <div v-if="documentsQuery.isLoading.value" class="divide-y divide-line/70">
        <div v-for="index in 5" :key="index" class="flex items-center gap-3 px-4 py-4">
          <div class="skeleton h-9 w-9 rounded-[10px]" />
          <div class="flex-1 space-y-2">
            <div class="skeleton h-3 w-1/3" />
            <div class="skeleton h-2.5 w-1/5" />
          </div>
          <div class="skeleton h-3 w-20" />
        </div>
      </div>

      <div v-else-if="documentsQuery.isError.value" class="px-4 py-12 text-center">
        <p class="text-[13px] text-danger">加载文档失败</p>
        <p class="mt-1 text-[11.5px] text-ink-3">
          {{ documentsQuery.error.value instanceof Error ? documentsQuery.error.value.message : '请检查后端连接' }}
        </p>
        <button type="button" class="btn btn-secondary btn-sm mt-4" @click="refresh">重试</button>
      </div>

      <EmptyState
        v-else-if="filtered.length === 0"
        :title="documents.length === 0 ? '还没有文档' : '没有匹配的文档'"
        :description="
          documents.length === 0
            ? '上传第一份文档，Eon 会自动完成解析、分块与索引。'
            : '试试调整状态筛选、知识库范围或搜索关键词。'
        "
      >
        <template #icon>
          <FilePlus2 class="h-6 w-6 text-brand-2" />
        </template>
        <template #action>
          <button
            v-if="documents.length === 0"
            type="button"
            class="btn btn-primary"
            @click="uploadOpen = true"
          >
            <FilePlus2 class="h-4 w-4" />
            上传文档
          </button>
          <button
            v-else
            type="button"
            class="btn btn-secondary"
            @click="status = 'ALL'; keyword = ''; kbFilter = 'ALL'"
          >
            清除筛选
          </button>
        </template>
      </EmptyState>

      <div v-else class="hidden md:block">
        <table class="w-full table-fixed text-left">
          <thead>
            <tr class="border-b border-line/80 text-[10.5px] uppercase tracking-wider text-ink-3">
              <th class="w-[36%] px-4 py-2.5 font-medium">文档</th>
              <th class="w-[11%] px-3 py-2.5 font-medium">状态</th>
              <th class="w-[16%] px-3 py-2.5 font-medium">知识库</th>
              <th class="w-[10%] px-3 py-2.5 font-medium">父 / 子</th>
              <th class="w-[8%] px-3 py-2.5 font-medium">比例</th>
              <th class="w-[11%] px-3 py-2.5 font-medium">时间</th>
              <th class="w-[8%] px-3 py-2.5 text-right font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="doc in filtered"
              :key="doc.id"
              class="group cursor-pointer border-b border-line/60 transition-colors last:border-0 hover:bg-surface-2/60"
              @click="openDocument(doc)"
            >
              <td class="px-4 py-3">
                <div class="flex min-w-0 items-center gap-3">
                  <FileTypeIcon :type="doc.fileType" :size="34" />
                  <div class="min-w-0">
                    <p class="truncate text-[12.5px] font-medium text-ink">{{ doc.fileName }}</p>
                    <p class="mt-0.5 text-[10.5px] text-ink-3">
                      {{ doc.fileType.toUpperCase() }} · {{ formatBytes(doc.fileSize) }} ·
                      {{ doc.parseMode }}
                    </p>
                  </div>
                </div>
              </td>
              <td class="px-3 py-3" :title="doc.errorMessage ?? undefined">
                <StatusPill :status="doc.status" />
              </td>
              <td class="px-3 py-3">
                <KbTags :kb-ids="doc.kbIds" :kbs="kbs" />
              </td>
              <td class="px-3 py-3 font-mono text-[11px] text-ink-2">
                {{ doc.parentCount }} / {{ doc.childCount }}
              </td>
              <td class="px-3 py-3 font-mono text-[11px]" :class="ratioTone(doc)">
                {{ ratioText(doc.parentCount, doc.childCount) }}
              </td>
              <td class="px-3 py-3 text-[11px] text-ink-3">{{ formatRelative(doc.createdAt) }}</td>
              <td class="px-3 py-3">
                <div class="flex items-center justify-end gap-1 opacity-0 transition group-hover:opacity-100">
                  <button
                    type="button"
                    class="icon-btn h-7 w-7"
                    aria-label="查看详情"
                    @click.stop="openDocument(doc)"
                  >
                    <ArrowUpRight class="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    class="icon-btn h-7 w-7 hover:!text-danger"
                    aria-label="删除文档"
                    @click.stop="askDelete(doc)"
                  >
                    <Trash2 class="h-3.5 w-3.5" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="filtered.length > 0" class="divide-y divide-line/60 md:hidden">
        <div
          v-for="doc in filtered"
          :key="doc.id"
          class="p-4 transition active:bg-surface-2"
          @click="openDocument(doc)"
        >
          <div class="flex items-start gap-3">
            <FileTypeIcon :type="doc.fileType" :size="36" />
            <div class="min-w-0 flex-1">
              <p class="truncate text-[13px] font-medium text-ink">{{ doc.fileName }}</p>
              <div class="mt-1.5 flex flex-wrap items-center gap-2">
                <StatusPill :status="doc.status" />
                <span class="text-[10.5px] text-ink-3">{{ formatBytes(doc.fileSize) }}</span>
                <span class="text-[10.5px] text-ink-3">{{ formatRelative(doc.createdAt) }}</span>
              </div>
              <div class="mt-2">
                <KbTags :kb-ids="doc.kbIds" :kbs="kbs" />
              </div>
              <div class="mt-2 flex items-center gap-3 font-mono text-[10.5px] text-ink-3">
                <span>父 {{ doc.parentCount }}</span>
                <span>子 {{ doc.childCount }}</span>
                <span :class="ratioTone(doc)">比例 {{ ratioText(doc.parentCount, doc.childCount) }}</span>
              </div>
            </div>
            <button
              type="button"
              class="icon-btn h-8 w-8 shrink-0 hover:!text-danger"
              aria-label="删除文档"
              @click.stop="askDelete(doc)"
            >
              <Trash2 class="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <UploadDialog
      v-model:open="uploadOpen"
      :kbs="kbs"
      @uploaded="onUploaded"
    />

    <ConfirmDialog
      :open="Boolean(pendingDelete)"
      title="删除文档"
      :message="`确定删除「${pendingDelete?.fileName ?? ''}」吗？\n关联的父块、子块与知识库绑定会一并清理。`"
      confirm-text="删除"
      danger
      :loading="deleting"
      @update:open="pendingDelete = null"
      @confirm="confirmDelete"
    />
  </div>
</template>

