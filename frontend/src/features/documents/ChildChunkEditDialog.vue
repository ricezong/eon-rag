<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { Info, Loader2, Scissors } from '@lucide/vue'
import { computed, nextTick, ref, watch } from 'vue'
import AppDialog from '@/components/ui/AppDialog.vue'
import { apiPost, apiPut } from '@/lib/api'
import { CHILD_MAX_TOKENS, tokenTone } from '@/lib/tokens'
import { useTokenCounts } from '@/lib/tokenCount'
import { useToastStore } from '@/stores/toast'
import type { ChildChunk } from '@/types/api'

const props = defineProps<{
  open: boolean
  documentId: string
  parentId: string
  child: ChildChunk | null
  indexed: boolean
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'saved'): void
}>()

const queryClient = useQueryClient()
const toast = useToastStore()

const content = ref('')
const cursor = ref(0)
const saving = ref(false)
const splitting = ref(false)
const textarea = ref<HTMLTextAreaElement | null>(null)

const isTableChild = computed(() => {
  const text = content.value.trimStart()
  return text.startsWith('|') && text.includes('---')
})
const splitOffset = computed(() => {
  const max = content.value.length
  return Math.max(0, Math.min(cursor.value, max))
})
const leftPart = computed(() => content.value.slice(0, splitOffset.value))
const rightPart = computed(() => content.value.slice(splitOffset.value))

const { counts, exact, pending } = useTokenCounts(
  () => [content.value.trim(), leftPart.value.trim(), rightPart.value.trim()],
  () =>
    props.child
      ? [{ text: props.child.content.trim(), count: props.child.tokenCount }]
      : [],
  () => props.open,
)
const tokens = computed(() => counts.value[0] ?? 0)
const leftTokens = computed(() => counts.value[1] ?? 0)
const rightTokens = computed(() => counts.value[2] ?? 0)
const dirty = computed(() => Boolean(props.child) && content.value.trim() !== props.child!.content.trim())
const canSave = computed(
  () =>
    Boolean(props.child) &&
    content.value.trim().length > 0 &&
    !saving.value &&
    !pending.value &&
    (!exact.value[0] || tokens.value <= CHILD_MAX_TOKENS),
)
const canSplit = computed(
  () =>
    Boolean(props.child) &&
    !isTableChild.value &&
    !dirty.value &&
    splitOffset.value > 0 &&
    splitOffset.value < content.value.length &&
    leftPart.value.trim().length > 0 &&
    rightPart.value.trim().length > 0 &&
    exact.value[1] &&
    exact.value[2] &&
    leftTokens.value <= CHILD_MAX_TOKENS &&
    rightTokens.value <= CHILD_MAX_TOKENS &&
    !pending.value &&
    !saving.value &&
    !splitting.value,
)

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    content.value = props.child?.content ?? ''
    cursor.value = Math.floor(content.value.length / 2)
    saving.value = false
    splitting.value = false
    await nextTick()
    textarea.value?.setSelectionRange(cursor.value, cursor.value)
    textarea.value?.focus()
  },
)

function updateCursor() {
  const element = textarea.value
  if (!element) return
  cursor.value = element.selectionStart ?? 0
}

function close() {
  if (saving.value || splitting.value) return
  emit('update:open', false)
}

async function invalidate() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['child-chunks', props.parentId] }),
    queryClient.invalidateQueries({ queryKey: ['parent-chunks'] }),
    queryClient.invalidateQueries({ queryKey: ['document', props.documentId] }),
    queryClient.invalidateQueries({ queryKey: ['documents'] }),
  ])
}

async function save() {
  const child = props.child
  if (!child || !canSave.value) return
  saving.value = true
  try {
    await apiPut<ChildChunk>(
      `/api/documents/${props.documentId}/parent-chunks/${props.parentId}/children/${child.id}`,
      { content: content.value.trim() },
    )
    toast.success(
      '子块已更新',
      props.indexed
        ? '已重建向量与全文索引；父块上下文未改变'
        : '文本已更新，需在文档详情手动向量化',
    )
    await invalidate()
    emit('saved')
    emit('update:open', false)
  } catch (error) {
    toast.error('子块保存失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    saving.value = false
  }
}

async function split() {
  if (!props.child || !canSplit.value) return
  splitting.value = true
  try {
    await apiPost<ChildChunk[]>(
      `/api/documents/${props.documentId}/parent-chunks/${props.parentId}/children/${props.child.id}/split`,
      { offset: splitOffset.value },
    )
    toast.success(
      '子块已拆分',
      props.indexed ? '两段均已重新向量化' : '两段已就绪，需在文档详情手动向量化',
    )
    await invalidate()
    emit('saved')
    emit('update:open', false)
  } catch (error) {
    toast.error('拆分失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    splitting.value = false
  }
}
</script>

<template>
  <AppDialog
    :open="open"
    title="编辑子块"
    width="lg"
    :closeable="!saving && !splitting"
    @update:open="emit('update:open', $event)"
  >
    <template #subtitle>子块只影响检索命中；修改不会改变父块上下文</template>

    <div class="space-y-4">
      <div>
        <div class="mb-1.5 flex items-center justify-between">
          <label class="label !mb-0" for="child-content">正文</label>
          <span class="font-mono text-[10.5px]" :class="tokenTone(tokens, CHILD_MAX_TOKENS)">
            <template v-if="pending && !exact[0]">计算中…</template>
            <template v-else>
              {{ exact[0] ? '' : '≈' }}{{ tokens }} / {{ CHILD_MAX_TOKENS }} tok
            </template>
          </span>
        </div>
        <textarea
          ref="textarea"
          id="child-content"
          v-model="content"
          class="textarea min-h-[260px] font-mono text-[12.5px] leading-relaxed"
          placeholder="子块正文"
          @click="updateCursor"
          @keyup="updateCursor"
          @select="updateCursor"
          @input="updateCursor"
        />
      </div>

      <div
        class="flex items-start gap-2 rounded-xl border border-cyan-soft bg-cyan-soft px-3 py-2.5"
      >
        <Info class="mt-0.5 h-3.5 w-3.5 shrink-0 text-brand-2" />
        <p class="text-[11px] leading-relaxed text-ink-2">
          子块用于检索；回答上下文来自父块。若希望回答内容也变化，请同时编辑父块。
          {{ indexed
            ? '保存后会自动重嵌该子块。'
            : '当前文档尚未向量化，保存后需在文档详情手动向量化。' }}
        </p>
      </div>

      <div class="rounded-xl border border-line bg-surface-2 p-3">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div>
            <p class="text-[12px] font-medium text-ink">在光标处拆分</p>
            <p class="mt-0.5 text-[10.5px] text-ink-3">
              光标位置：{{ splitOffset }} / {{ content.length }}；拆分后两段独立检索。
            </p>
          </div>
          <button
            type="button"
            class="btn btn-secondary btn-sm"
            :disabled="!canSplit"
            @click="split"
          >
            <Loader2 v-if="splitting" class="h-3.5 w-3.5 animate-spin" />
            <Scissors v-else class="h-3.5 w-3.5" />
            拆分
          </button>
        </div>

        <p v-if="isTableChild" class="mt-2 text-[10.5px] text-warning">
          表格子块暂不支持拆分，可直接编辑或删除。
        </p>
        <p v-else-if="dirty" class="mt-2 text-[10.5px] text-warning">
          拆分使用已保存内容；请先保存修改，再按新内容拆分。
        </p>
        <p
          v-else-if="splitOffset <= 0 || splitOffset >= content.length"
          class="mt-2 text-[10.5px] text-ink-3"
        >
          将光标移到正文中间，即可预览拆分结果。
        </p>
        <div
          v-else
          class="mt-2 grid gap-2 text-[10.5px] text-ink-3 sm:grid-cols-2"
        >
          <div class="rounded-lg border border-line bg-surface-2 p-2">
            前段 {{ leftPart.trim().length }} 字 /
            {{ exact[1] ? '' : '≈' }}{{ leftTokens }} tok
          </div>
          <div class="rounded-lg border border-line bg-surface-2 p-2">
            后段 {{ rightPart.trim().length }} 字 /
            {{ exact[2] ? '' : '≈' }}{{ rightTokens }} tok
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <button
        type="button"
        class="btn btn-secondary"
        :disabled="saving || splitting"
        @click="close"
      >
        取消
      </button>
      <button
        type="button"
        class="btn btn-primary"
        :disabled="!canSave"
        @click="save"
      >
        <Loader2 v-if="saving" class="h-3.5 w-3.5 animate-spin" />
        保存子块
      </button>
    </template>
  </AppDialog>
</template>

