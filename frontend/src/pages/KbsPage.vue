<script setup lang="ts">
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ArrowUpRight, LibraryBig, Pencil, Plus, Trash2 } from '@lucide/vue'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import KbFormDialog from '@/features/kbs/KbFormDialog.vue'
import { apiDelete, apiGet } from '@/lib/api'
import { formatRelative } from '@/lib/format'
import { useToastStore } from '@/stores/toast'
import type { KnowledgeBase } from '@/types/api'

const router = useRouter()
const queryClient = useQueryClient()
const toast = useToastStore()

const formOpen = ref(false)
const editing = ref<KnowledgeBase | null>(null)
const deleteTarget = ref<KnowledgeBase | null>(null)
const deleting = ref(false)

const kbsQuery = useQuery({
  queryKey: ['kbs'],
  queryFn: () => apiGet<KnowledgeBase[]>('/api/kbs'),
})

function openCreate() {
  editing.value = null
  formOpen.value = true
}

function openEdit(kb: KnowledgeBase) {
  editing.value = kb
  formOpen.value = true
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await apiDelete(`/api/kbs/${deleteTarget.value.id}`)
    toast.success('知识库已删除', '文档本身不会被删除')
    deleteTarget.value = null
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['kbs'] }),
      queryClient.invalidateQueries({ queryKey: ['documents'] }),
    ])
  } catch (error) {
    toast.error('删除失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div class="px-5 py-5 lg:px-8">
    <header class="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 class="text-[22px] font-semibold tracking-tight text-ink">知识库</h1>
        <p class="mt-1 text-[12px] text-ink-2">用分类组织文档，并在问答时限定检索范围</p>
      </div>

      <button type="button" class="btn btn-primary" @click="openCreate">
        <Plus class="h-4 w-4" />
        新建知识库
      </button>
    </header>

    <div v-if="kbsQuery.isLoading.value" class="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
      <div v-for="index in 3" :key="index" class="skeleton h-[150px] rounded-[14px]" />
    </div>

    <div
      v-else-if="kbsQuery.isError.value"
      class="panel mt-5 px-6 py-14 text-center"
    >
      <p class="text-[13px] text-danger">加载知识库失败</p>
      <p class="mt-1 text-[11.5px] text-ink-3">
        {{ kbsQuery.error.value instanceof Error ? kbsQuery.error.value.message : '请检查后端连接' }}
      </p>
      <button type="button" class="btn btn-secondary btn-sm mt-4" @click="kbsQuery.refetch()">
        重试
      </button>
    </div>

    <EmptyState
      v-else-if="(kbsQuery.data.value ?? []).length === 0"
      class="panel mt-5"
      title="还没有知识库"
      description="创建第一个知识库，把文档按主题归类，问答时就能按范围检索。"
    >
      <template #icon>
        <LibraryBig class="h-6 w-6 text-brand-2" />
      </template>
      <template #action>
        <button type="button" class="btn btn-primary" @click="openCreate">
          <Plus class="h-4 w-4" />
          新建知识库
        </button>
      </template>
    </EmptyState>

    <div v-else class="mt-5 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
      <article
        v-for="kb in kbsQuery.data.value"
        :key="kb.id"
        class="card-hover group relative cursor-pointer overflow-hidden rounded-[14px] border border-line bg-white p-4"
        @click="router.push(`/kbs/${kb.id}`)"
      >
        <div class="absolute inset-0" :style="{ background: `linear-gradient(135deg, ${kb.color}12, transparent 58%)` }" />
        <div class="absolute inset-x-0 top-0 h-[2px]" :style="{ background: kb.color }" />

        <div class="relative">
          <div class="flex items-start justify-between">
            <div
              class="flex h-9 w-9 items-center justify-center rounded-xl border"
              :style="{ borderColor: `${kb.color}55`, background: `${kb.color}1a`, color: kb.color }"
            >
              <LibraryBig class="h-4 w-4" />
            </div>

            <div class="flex items-center gap-1 opacity-0 transition group-hover:opacity-100">
              <button
                type="button"
                class="icon-btn h-7 w-7"
                aria-label="编辑知识库"
                @click.stop="openEdit(kb)"
              >
                <Pencil class="h-3.5 w-3.5" />
              </button>
              <button
                type="button"
                class="icon-btn h-7 w-7 hover:!text-danger"
                aria-label="删除知识库"
                @click.stop="deleteTarget = kb"
              >
                <Trash2 class="h-3.5 w-3.5" />
              </button>
            </div>
          </div>

          <h2 class="mt-3 truncate text-[14px] font-semibold text-ink">{{ kb.name }}</h2>
          <p class="mt-1 line-clamp-2 min-h-[34px] text-[11.5px] leading-relaxed text-ink-2">
            {{ kb.description || '暂无描述' }}
          </p>

          <div class="mt-4 flex items-center justify-between text-[10.5px] text-ink-3">
            <span class="inline-flex items-center gap-1.5">
              <span class="font-mono text-[11px] text-ink-2">{{ kb.documentCount ?? 0 }}</span>
              个文档
            </span>
            <span class="inline-flex items-center gap-1">
              {{ formatRelative(kb.updatedAt) }}
              <ArrowUpRight class="h-3 w-3 opacity-0 transition group-hover:opacity-100" />
            </span>
          </div>
        </div>
      </article>
    </div>

    <KbFormDialog v-model:open="formOpen" :kb="editing" @saved="editing = null" />

    <ConfirmDialog
      :open="Boolean(deleteTarget)"
      title="删除知识库"
      :message="`确定删除「${deleteTarget?.name ?? ''}」吗？\n只删除分类与绑定关系，不会删除文档本身。`"
      confirm-text="删除"
      danger
      :loading="deleting"
      @update:open="deleteTarget = null"
      @confirm="confirmDelete"
    />
  </div>
</template>
