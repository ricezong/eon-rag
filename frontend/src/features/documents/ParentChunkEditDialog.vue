<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { Info, Loader2, RefreshCw } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import AppDialog from '@/components/ui/AppDialog.vue'
import { apiPut } from '@/lib/api'
import { PARENT_MAX_TOKENS, tokenTone } from '@/lib/tokens'
import { useTokenCounts } from '@/lib/tokenCount'
import { useToastStore } from '@/stores/toast'
import type { ParentChunk } from '@/types/api'

const props = defineProps<{
  open: boolean
  documentId: string
  parent: ParentChunk | null
  indexed: boolean
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'saved'): void
}>()

const queryClient = useQueryClient()
const toast = useToastStore()

const title = ref('')
const content = ref('')
const rebuildChildren = ref(true)
const saving = ref(false)

const { counts, exact, pending } = useTokenCounts(
  () => [content.value.trim()],
  () =>
    props.parent
      ? [{ text: props.parent.content.trim(), count: props.parent.tokenCount }]
      : [],
  () => props.open,
)
const tokens = computed(() => counts.value[0] ?? 0)
const contentChanged = computed(() => {
  if (!props.parent) return false
  return content.value.trim() !== props.parent.content.trim()
})
const canSave = computed(
  () =>
    content.value.trim().length > 0 &&
    !saving.value &&
    !pending.value &&
    (!exact.value[0] || tokens.value <= PARENT_MAX_TOKENS),
)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    title.value = props.parent?.title ?? ''
    content.value = props.parent?.content ?? ''
    rebuildChildren.value = true
    saving.value = false
  },
)

function close() {
  if (saving.value) return
  emit('update:open', false)
}

async function save() {
  if (!props.parent || !canSave.value) return
  saving.value = true
  try {
    await apiPut<ParentChunk>(
      `/api/documents/${props.documentId}/parent-chunks/${props.parent.id}`,
      {
        title: title.value.trim() || null,
        content: content.value.trim(),
        rebuildChildren: rebuildChildren.value,
      },
    )
    const rebuilt = rebuildChildren.value
    toast.success(
      '父块已更新',
      rebuilt
        ? props.indexed
          ? '子块已自动重嵌，旧子块引用可能失效'
          : '子块已重建，需在文档详情手动向量化'
        : '子块保持不变',
    )
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['parent-chunks'] }),
      queryClient.invalidateQueries({ queryKey: ['child-chunks'] }),
      queryClient.invalidateQueries({ queryKey: ['document', props.documentId] }),
      queryClient.invalidateQueries({ queryKey: ['documents'] }),
    ])
    emit('saved')
    emit('update:open', false)
  } catch (error) {
    toast.error('父块保存失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <AppDialog
    :open="open"
    title="编辑父块"
    width="lg"
    :closeable="!saving"
    @update:open="emit('update:open', $event)"
  >
    <template #subtitle>父块是送给大模型的生成上下文；子块只用于检索</template>

    <div class="space-y-4">
      <div>
        <label class="label" for="parent-title">标题</label>
        <input
          id="parent-title"
          v-model="title"
          class="input"
          maxlength="500"
          placeholder="章节标题，可为空"
        />
      </div>

      <div>
        <div class="mb-1.5 flex items-center justify-between">
          <label class="label !mb-0" for="parent-content">正文</label>
          <span class="font-mono text-[10.5px]" :class="tokenTone(tokens, PARENT_MAX_TOKENS)">
            <template v-if="pending && !exact[0]">计算中…</template>
            <template v-else>
              {{ exact[0] ? '' : '≈' }}{{ tokens }} / {{ PARENT_MAX_TOKENS }} tok
            </template>
          </span>
        </div>
        <textarea
          id="parent-content"
          v-model="content"
          class="textarea min-h-[260px] font-mono text-[12.5px] leading-relaxed"
          placeholder="父块正文（Markdown / 纯文本）"
        />
      </div>

      <label class="flex cursor-pointer items-start gap-2.5 rounded-xl border border-line bg-surface-2 p-3">
        <input v-model="rebuildChildren" type="checkbox" class="mt-0.5 accent-[#4176e6]" />
        <span class="text-[12px] leading-relaxed text-ink-2">
          按新正文重建子块
          <span class="block text-[11px] text-ink-3">
            默认开启：重新切分并替换原有子块；子块 ID 会变化，旧引用可能失效。
            {{ indexed ? '保存后会自动重嵌，并刷新向量索引。' : '当前文档尚未向量化，保存后需手动触发。' }}
          </span>
        </span>
      </label>

      <div
        v-if="contentChanged && !rebuildChildren"
        class="flex items-start gap-2 rounded-xl border border-warning/30 bg-warning/[0.07] px-3 py-2.5"
      >
        <Info class="mt-0.5 h-3.5 w-3.5 shrink-0 text-warning" />
        <p class="text-[11px] leading-relaxed text-warning">
          父块已修改但未重建子块：回答会使用新父块，检索仍命中旧子块。
        </p>
      </div>

      <div class="flex items-start gap-2 rounded-xl border border-line/70 bg-surface-2 px-3 py-2.5">
        <RefreshCw class="mt-0.5 h-3.5 w-3.5 shrink-0 text-brand-2" />
        <p class="text-[11px] leading-relaxed text-ink-3">
          <template v-if="!rebuildChildren">
            保留原子块：本次只更新父块正文，检索命中的子块与向量保持不变。
          </template>
          <template v-else-if="indexed">
            重建使用当前切分参数（父块 1200 / 子块 320 Token），并立即刷新全文、Trigram 与向量索引。
          </template>
          <template v-else>
            重建使用当前切分参数（父块 1200 / 子块 320 Token），本次只更新文本与索引结构，需随后手动向量化。
          </template>
        </p>
      </div>
    </div>

    <template #footer>
      <button type="button" class="btn btn-secondary" :disabled="saving" @click="close">
        取消
      </button>
      <button type="button" class="btn btn-primary" :disabled="!canSave" @click="save">
        <Loader2 v-if="saving" class="h-3.5 w-3.5 animate-spin" />
        保存父块
      </button>
    </template>
  </AppDialog>
</template>

