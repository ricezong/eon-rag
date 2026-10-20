<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  ArrowLeft,
  CalendarClock,
  FileText,
  LibraryBig,
  Link2,
  Pencil,
  Trash2,
  Unlink,
} from '@lucide/vue'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import FileTypeIcon from '@/components/ui/FileTypeIcon.vue'
import StatusPill from '@/components/ui/StatusPill.vue'
import BindDocumentsDialog from '@/features/kbs/BindDocumentsDialog.vue'
import KbFormDialog from '@/features/kbs/KbFormDialog.vue'
import { apiDelete, apiGet } from '@/lib/api'
import { formatBytes, formatDateTime, ratioText } from '@/lib/format'
import { useToastStore } from '@/stores/toast'
import type { KnowledgeBase, RagDocument } from '@/types/api'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const toast = useToastStore()

const id = route.params.id as string
const editOpen = ref(false)
const bindOpen = ref(false)
const deleteOpen = ref(false)
const deleting = ref(false)
const unbindTarget = ref<RagDocument | null>(null)
const unbinding = ref(false)

const kbQuery = useQuery({
  queryKey: ['kb', id],
  queryFn: () => apiGet<KnowledgeBase>(`/api/kbs/${id}`),
})

const docsQuery = useQuery({
  queryKey: ['kb-documents', id],
  queryFn: () => apiGet<RagDocument[]>(`/api/kbs/${id}/documents`),
})

const allDocsQuery = useQuery({
  queryKey: ['documents'],
  queryFn: () => apiGet<RagDocument[]>('/api/documents'),
})

const kb = computed(() => kbQuery.data.value)
const docs = computed(() => docsQuery.data.value ?? [])

async function invalidate() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['kb', id] }),
    queryClient.invalidateQueries({ queryKey: ['kb-documents', id] }),
    queryClient.invalidateQueries({ queryKey: ['documents'] }),
    queryClient.invalidateQueries({ queryKey: ['kbs'] }),
  ])
}

async function confirmDelete() {
  deleting.value = true
  try {
    await apiDelete(`/api/kbs/${id}`)
    toast.success('知识库已删除', '文档本身仍保留在文档库中')
    await queryClient.invalidateQueries({ queryKey: ['kbs'] })
    await queryClient.invalidateQueries({ queryKey: ['documents'] })
    router.push('/kbs')
  } catch (error) {
    toast.error('删除失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    deleting.value = false
    deleteOpen.value = false
  }
}

async function confirmUnbind() {
  if (!unbindTarget.value) return
  unbinding.value = true
  try {
    await apiDelete(`/api/kbs/${id}/documents/${unbindTarget.value.id}`)
    toast.success('已从当前知识库移除')
    unbindTarget.value = null
    await invalidate()
  } catch (error) {
    toast.error('解绑失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    unbinding.value = false
  }
}
</script>

<template>
  <div class="px-5 py-5 lg:px-8">
    <button
      type="button"
      class="mb-4 inline-flex items-center gap-1.5 text-[11.5px] text-ink-3 transition hover:text-ink"
      @click="router.push('/kbs')"
    >
      <ArrowLeft class="h-3.5 w-3.5" />
      返回知识库
    </button>

    <div v-if="kbQuery.isLoading.value" class="space-y-4">
      <div class="skeleton h-20 w-full" />
      <div class="skeleton h-24 w-full" />
    </div>

    <div v-else-if="kbQuery.isError.value" class="panel px-6 py-16 text-center">
      <p class="text-[14px] text-danger">知识库不存在或加载失败</p>
      <button type="button" class="btn btn-secondary mt-4" @click="router.push('/kbs')">
        返回列表
      </button>
    </div>

    <template v-else-if="kb">
      <header class="flex flex-wrap items-start justify-between gap-4">
        <div class="flex min-w-0 items-start gap-3.5">
          <div
            class="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl border"
            :style="{ borderColor: `${kb.color}55`, background: `${kb.color}1a`, color: kb.color }"
          >
            <LibraryBig class="h-5 w-5" />
          </div>
          <div class="min-w-0">
            <h1 class="truncate text-[21px] font-semibold tracking-tight text-ink">
              {{ kb.name }}
            </h1>
            <p class="mt-1 max-w-2xl text-[12px] leading-relaxed text-ink-2">
              {{ kb.description || '暂无描述' }}
            </p>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <button type="button" class="btn btn-secondary btn-sm" @click="editOpen = true">
            <Pencil class="h-3.5 w-3.5" />
            编辑
          </button>
          <button type="button" class="btn btn-secondary btn-sm" @click="bindOpen = true">
            <Link2 class="h-3.5 w-3.5" />
            绑定文档
          </button>
          <button type="button" class="btn btn-danger btn-sm" @click="deleteOpen = true">
            <Trash2 class="h-3.5 w-3.5" />
            删除
          </button>
        </div>
      </header>

      <div class="mt-5 grid grid-cols-2 gap-2 lg:grid-cols-3">
        <div class="panel flex items-center gap-3 px-4 py-3">
          <FileText class="h-4 w-4 text-brand-2" />
          <div>
            <p class="font-display text-[20px] font-semibold leading-none text-ink">
              {{ kb.documentCount ?? docs.length }}
            </p>
            <p class="mt-1 text-[10.5px] text-ink-3">绑定文档</p>
          </div>
        </div>
        <div class="panel flex items-center gap-3 px-4 py-3">
          <CalendarClock class="h-4 w-4 text-ink-2" />
          <div>
            <p class="text-[12px] font-medium leading-tight text-ink">
              {{ formatDateTime(kb.createdAt) }}
            </p>
            <p class="mt-1 text-[10.5px] text-ink-3">创建时间</p>
          </div>
        </div>
        <div class="panel flex items-center gap-3 px-4 py-3">
          <CalendarClock class="h-4 w-4 text-ink-2" />
          <div>
            <p class="text-[12px] font-medium leading-tight text-ink">
              {{ formatDateTime(kb.updatedAt) }}
            </p>
            <p class="mt-1 text-[10.5px] text-ink-3">最近更新</p>
          </div>
        </div>
      </div>

      <section class="mt-6">
        <div class="mb-3 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <Link2 class="h-4 w-4 text-brand-2" />
            <h2 class="text-[13px] font-semibold text-ink">已绑定文档</h2>
            <span class="chip h-5 px-1.5 text-[10px]">{{ docs.length }}</span>
          </div>
          <p class="text-[10.5px] text-ink-3">解绑只影响当前知识库</p>
        </div>

        <div v-if="docsQuery.isLoading.value" class="space-y-2">
          <div v-for="index in 3" :key="index" class="skeleton h-14 w-full" />
        </div>

        <EmptyState
          v-else-if="docs.length === 0"
          class="panel"
          title="还没有绑定文档"
          description="把已有文档加入这个知识库，或在文档页上传新文档。"
        >
          <template #icon>
            <Link2 class="h-6 w-6 text-brand-2" />
          </template>
          <template #action>
            <button type="button" class="btn btn-primary" @click="bindOpen = true">
              <Link2 class="h-4 w-4" />
              绑定文档
            </button>
          </template>
        </EmptyState>

        <div v-else class="panel overflow-hidden">
          <div class="hidden md:block">
            <table class="w-full table-fixed text-left">
              <thead>
                <tr class="border-b border-line/80 text-[10.5px] uppercase tracking-wider text-ink-3">
                  <th class="w-[44%] px-4 py-2.5 font-medium">文档</th>
                  <th class="w-[14%] px-3 py-2.5 font-medium">状态</th>
                  <th class="w-[14%] px-3 py-2.5 font-medium">父 / 子</th>
                  <th class="w-[10%] px-3 py-2.5 font-medium">比例</th>
                  <th class="w-[10%] px-3 py-2.5 font-medium">时间</th>
                  <th class="w-[8%] px-3 py-2.5 text-right font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="doc in docs"
                  :key="doc.id"
                  class="group cursor-pointer border-b border-line/60 transition last:border-0 hover:bg-surface-2/60"
                  @click="router.push(`/documents/${doc.id}`)"
                >
                  <td class="px-4 py-3">
                    <div class="flex min-w-0 items-center gap-3">
                      <FileTypeIcon :type="doc.fileType" :size="32" />
                      <div class="min-w-0">
                        <p class="truncate text-[12.5px] font-medium text-ink">{{ doc.fileName }}</p>
                        <p class="mt-0.5 text-[10px] text-ink-3">
                          {{ doc.fileType.toUpperCase() }} · {{ formatBytes(doc.fileSize) }}
                        </p>
                      </div>
                    </div>
                  </td>
                  <td class="px-3 py-3"><StatusPill :status="doc.status" /></td>
                  <td class="px-3 py-3 font-mono text-[11px] text-ink-2">
                    {{ doc.parentCount }} / {{ doc.childCount }}
                  </td>
                  <td class="px-3 py-3 font-mono text-[11px] text-ink-2">
                    {{ ratioText(doc.parentCount, doc.childCount) }}
                  </td>
                  <td class="px-3 py-3 text-[10.5px] text-ink-3">
                    {{ formatDateTime(doc.createdAt) }}
                  </td>
                  <td class="px-3 py-3 text-right">
                    <button
                      type="button"
                      class="icon-btn h-7 w-7 opacity-0 transition group-hover:opacity-100 hover:!text-warning"
                      aria-label="解除绑定"
                      @click.stop="unbindTarget = doc"
                    >
                      <Unlink class="h-3.5 w-3.5" />
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="divide-y divide-line/60 md:hidden">
            <div
              v-for="doc in docs"
              :key="doc.id"
              class="flex items-start gap-3 p-4"
              @click="router.push(`/documents/${doc.id}`)"
            >
              <FileTypeIcon :type="doc.fileType" :size="34" />
              <div class="min-w-0 flex-1">
                <p class="truncate text-[12.5px] font-medium text-ink">{{ doc.fileName }}</p>
                <div class="mt-1.5 flex flex-wrap items-center gap-2">
                  <StatusPill :status="doc.status" />
                  <span class="text-[10px] text-ink-3">
                    {{ doc.parentCount }} 父 / {{ doc.childCount }} 子
                  </span>
                </div>
              </div>
              <button
                type="button"
                class="icon-btn h-7 w-7 shrink-0 hover:!text-warning"
                aria-label="解除绑定"
                @click.stop="unbindTarget = doc"
              >
                <Unlink class="h-3.5 w-3.5" />
              </button>
            </div>
          </div>
        </div>
      </section>

      <KbFormDialog v-model:open="editOpen" :kb="kb" @saved="invalidate" />

      <BindDocumentsDialog
        v-model:open="bindOpen"
        :kb-id="id"
        :documents="allDocsQuery.data.value ?? []"
        @saved="invalidate"
      />

      <ConfirmDialog
        :open="deleteOpen"
        title="删除知识库"
        :message="`确定删除「${kb.name}」吗？\n只删除分类与绑定关系，不会删除文档本身。`"
        confirm-text="删除"
        danger
        :loading="deleting"
        @update:open="deleteOpen = false"
        @confirm="confirmDelete"
      />

      <ConfirmDialog
        :open="Boolean(unbindTarget)"
        title="解除绑定"
        :message="`确定把「${unbindTarget?.fileName ?? ''}」从当前知识库移除吗？\n文档本身与索引不会受影响。`"
        confirm-text="解除绑定"
        :loading="unbinding"
        @update:open="unbindTarget = null"
        @confirm="confirmUnbind"
      />
    </template>
  </div>
</template>
