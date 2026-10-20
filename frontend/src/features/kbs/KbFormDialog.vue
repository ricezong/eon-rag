<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { Loader2, Palette } from '@lucide/vue'
import { reactive, ref, watch } from 'vue'
import AppDialog from '@/components/ui/AppDialog.vue'
import { apiPost, apiPut } from '@/lib/api'
import { useToastStore } from '@/stores/toast'
import type { KnowledgeBase } from '@/types/api'

const props = defineProps<{
  open: boolean
  kb?: KnowledgeBase | null
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'saved'): void
}>()

const queryClient = useQueryClient()
const toast = useToastStore()

const colors = ['#4176e6', '#2563eb', '#0e7490', '#15803d', '#d97706', '#dc2626']

const form = reactive({
  name: '',
  description: '',
  color: colors[0],
})
const submitting = ref(false)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    form.name = props.kb?.name ?? ''
    form.description = props.kb?.description ?? ''
    form.color = props.kb?.color ?? colors[0]
  },
)

function close() {
  if (submitting.value) return
  emit('update:open', false)
}

async function submit() {
  const name = form.name.trim()
  if (!name) {
    toast.error('名称不能为空')
    return
  }
  submitting.value = true
  try {
    if (props.kb) {
      await apiPut<KnowledgeBase>(`/api/kbs/${props.kb.id}`, {
        name,
        description: form.description.trim(),
        color: form.color,
      })
    } else {
      await apiPost<KnowledgeBase>('/api/kbs', {
        name,
        description: form.description.trim(),
        color: form.color,
      })
    }
    toast.success(props.kb ? '知识库已更新' : '知识库已创建')
    await queryClient.invalidateQueries({ queryKey: ['kbs'] })
    if (props.kb) await queryClient.invalidateQueries({ queryKey: ['kb', props.kb.id] })
    emit('saved')
    emit('update:open', false)
  } catch (error) {
    toast.error('保存失败', error instanceof Error ? error.message : '请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AppDialog
    :open="open"
    :title="kb ? '编辑知识库' : '新建知识库'"
    width="md"
    :closeable="!submitting"
    @update:open="emit('update:open', $event)"
  >
    <template #subtitle>知识库用于给文档分组，并在问答时限定检索范围</template>

    <form class="space-y-4" @submit.prevent="submit">
      <div>
        <label class="label" for="kb-name">名称</label>
        <input
          id="kb-name"
          v-model="form.name"
          class="input"
          maxlength="200"
          autofocus
          placeholder="例如：产品文档"
        />
      </div>

      <div>
        <label class="label" for="kb-description">描述</label>
        <textarea
          id="kb-description"
          v-model="form.description"
          class="textarea min-h-[86px]"
          placeholder="这个知识库收录什么内容？"
        />
      </div>

      <div>
        <span class="label">标识色</span>
        <div class="flex flex-wrap items-center gap-2">
          <button
            v-for="color in colors"
            :key="color"
            type="button"
            class="h-8 w-8 rounded-lg border transition"
            :class="form.color === color ? 'border-ink/60 ring-2 ring-ink/10' : 'border-transparent'"
            :style="{ background: color }"
            :aria-label="`选择颜色 ${color}`"
            @click="form.color = color"
          />
          <label
            class="flex h-8 w-8 cursor-pointer items-center justify-center rounded-lg border border-line bg-surface-2 text-ink-2"
            title="自定义颜色"
          >
            <Palette class="h-3.5 w-3.5" />
            <input v-model="form.color" type="color" class="sr-only" />
          </label>
          <span class="font-mono text-[10.5px] text-ink-3">{{ form.color }}</span>
        </div>
      </div>

      <div
        class="rounded-xl border border-line bg-surface-2/60 p-3"
        :style="{ background: `linear-gradient(135deg, ${form.color}12, transparent 65%)` }"
      >
        <div class="flex items-center gap-2.5">
          <span class="h-2 w-2 rounded-full" :style="{ background: form.color }" />
          <span class="text-[12.5px] font-medium text-ink">{{ form.name || '知识库预览' }}</span>
        </div>
        <p class="mt-1 line-clamp-2 text-[11px] text-ink-3">
          {{ form.description || '描述会显示在知识库卡片上' }}
        </p>
      </div>
    </form>

    <template #footer>
      <button type="button" class="btn btn-secondary" :disabled="submitting" @click="close">
        取消
      </button>
      <button type="button" class="btn btn-primary" :disabled="submitting" @click="submit">
        <Loader2 v-if="submitting" class="h-3.5 w-3.5 animate-spin" />
        {{ kb ? '保存修改' : '创建知识库' }}
      </button>
    </template>
  </AppDialog>
</template>
