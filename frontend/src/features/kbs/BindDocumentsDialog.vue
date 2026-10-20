<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { Check, Link2, Loader2, Search } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import AppDialog from '@/components/ui/AppDialog.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import FileTypeIcon from '@/components/ui/FileTypeIcon.vue'
import { apiPost } from '@/lib/api'
import { formatBytes } from '@/lib/format'
import { useToastStore } from '@/stores/toast'
import type { RagDocument } from '@/types/api'

const props = defineProps<{
  open: boolean
  kbId: string
  documents: RagDocument[]
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'saved'): void
}>()

const queryClient = useQueryClient()
const toast = useToastStore()

const selected = ref<string[]>([])
const keyword = ref('')
const submitting = ref(false)

const candidates = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return props.documents.filter((doc) => {
    if ((doc.kbIds ?? []).includes(props.kbId)) return false
    if (query && !doc.fileName.toLowerCase().includes(query)) return false
    return true
  })
})

watch(
  () => props.open,
  (open) => {
    if (!open) return
    selected.value = []
    keyword.value = ''
  },
)

function toggle(id: string) {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((item) => item !== id)
    : [...selected.value, id]
}

function selectAll() {
  const visibleIds = candidates.value.map((doc) => doc.id)
  const allSelected = visibleIds.every((id) => selected.value.includes(id))
  selected.value = allSelected
    ? selected.value.filter((id) => !visibleIds.includes(id))
    : Array.from(new Set([...selected.value, ...visibleIds]))
}

async function submit() {
  if (selected.value.length === 0) return
  submitting.value = true
  let success = 0
  let failed = 0
  for (const documentId of selected.value) {
    try {
      await apiPost(`/api/kbs/${props.kbId}/documents/${documentId}`)
      success += 1
    } catch {
      failed += 1
    }
  }
  submitting.value = false

  if (success > 0) toast.success(`已绑定 ${success} 个文档`)
  if (failed > 0) toast.error(`${failed} 个文档绑定失败`)

  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['kb-documents', props.kbId] }),
    queryClient.invalidateQueries({ queryKey: ['kb', props.kbId] }),
    queryClient.invalidateQueries({ queryKey: ['documents'] }),
    queryClient.invalidateQueries({ queryKey: ['kbs'] }),
  ])
  emit('saved')
  emit('update:open', false)
}
</script>

<template>
  <AppDialog
    :open="open"
    title="绑定文档"
    width="lg"
    :closeable="!submitting"
    @update:open="emit('update:open', $event)"
  >
    <template #subtitle>选择要加入当前知识库的文档</template>

    <div class="space-y-3">
      <div class="flex items-center gap-2">
        <div class="relative flex-1">
          <Search class="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-3" />
          <input v-model="keyword" class="input h-8 pl-8 text-[12px]" placeholder="搜索文档" />
        </div>
        <button type="button" class="btn btn-secondary btn-sm" @click="selectAll">
          全选当前
        </button>
      </div>

      <div class="max-h-[360px] overflow-y-auto rounded-xl border border-line">
        <div v-if="candidates.length === 0" class="px-4 py-10">
          <EmptyState
            title="没有可绑定的文档"
            :description="
              documents.length === 0
                ? '文档库为空，请先在文档页上传。'
                : '所有文档都已绑定，或没有匹配搜索关键词的文档。'
            "
          >
            <template #icon>
              <Link2 class="h-6 w-6 text-ink-3" />
            </template>
          </EmptyState>
        </div>

        <label
          v-for="doc in candidates"
          :key="doc.id"
          class="flex cursor-pointer items-center gap-3 border-b border-line/60 px-3 py-2.5 transition last:border-0 hover:bg-surface-2"
        >
          <input
            type="checkbox"
            class="accent-[#4176e6]"
            :checked="selected.includes(doc.id)"
            @change="toggle(doc.id)"
          />
          <FileTypeIcon :type="doc.fileType" :size="30" />
          <span class="min-w-0 flex-1">
            <span class="block truncate text-[12px] text-ink">{{ doc.fileName }}</span>
            <span class="mt-0.5 block text-[10px] text-ink-3">
              {{ doc.fileType.toUpperCase() }} · {{ formatBytes(doc.fileSize) }} ·
              {{ doc.parentCount }} 父块
            </span>
          </span>
          <Check v-if="selected.includes(doc.id)" class="h-4 w-4 shrink-0 text-brand-2" />
        </label>
      </div>
    </div>

    <template #footer>
      <span class="mr-auto text-[11px] text-ink-3">已选择 {{ selected.length }} 个</span>
      <button
        type="button"
        class="btn btn-secondary"
        :disabled="submitting"
        @click="emit('update:open', false)"
      >
        取消
      </button>
      <button
        type="button"
        class="btn btn-primary"
        :disabled="submitting || selected.length === 0"
        @click="submit"
      >
        <Loader2 v-if="submitting" class="h-3.5 w-3.5 animate-spin" />
        <Link2 v-else class="h-3.5 w-3.5" />
        绑定 {{ selected.length || '' }}
      </button>
    </template>
  </AppDialog>
</template>
