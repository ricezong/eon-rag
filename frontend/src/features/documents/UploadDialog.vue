<script setup lang="ts">
import {
  AlertCircle,
  CheckCircle2,
  FileUp,
  Loader2,
  UploadCloud,
  X,
} from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import AppDialog from '@/components/ui/AppDialog.vue'
import FileTypeIcon from '@/components/ui/FileTypeIcon.vue'
import { uploadDocument } from '@/lib/api'
import { fileExtension, formatBytes } from '@/lib/format'
import { useToastStore } from '@/stores/toast'
import type { KnowledgeBase } from '@/types/api'

const props = defineProps<{
  open: boolean
  kbs: KnowledgeBase[]
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'uploaded'): void
}>()

const toast = useToastStore()

const MAX_SIZE = 50 * 1024 * 1024

interface UploadItem {
  id: string
  file: File
  progress: number
  status: 'queued' | 'uploading' | 'done' | 'error'
  error?: string
  docId?: string
}

const items = ref<UploadItem[]>([])
const dragging = ref(false)
const uploading = ref(false)
const parseMode = ref<'AUTO' | 'LOCAL' | 'CLOUD'>('AUTO')
const selectedKbIds = ref<string[]>([])
const fileInput = ref<HTMLInputElement | null>(null)

const queuedCount = computed(() => items.value.filter((item) => item.status === 'queued').length)
const doneCount = computed(() => items.value.filter((item) => item.status === 'done').length)
const errorCount = computed(() => items.value.filter((item) => item.status === 'error').length)

watch(
  () => props.open,
  (open) => {
    if (!open) return
    items.value = []
    parseMode.value = 'AUTO'
    selectedKbIds.value = []
    dragging.value = false
    if (fileInput.value) fileInput.value.value = ''
  },
)

function newId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function addFiles(files: FileList | File[]) {
  for (const file of Array.from(files)) {
    const extension = fileExtension(file.name)
    if (!extension) {
      toast.error('格式不支持', `${file.name} 缺少扩展名`)
      continue
    }
    if (file.size > MAX_SIZE) {
      toast.error('文件过大', `${file.name} 超过 50MB`)
      continue
    }
    const duplicated = items.value.some(
      (item) =>
        item.file.name === file.name &&
        item.file.size === file.size &&
        item.file.lastModified === file.lastModified,
    )
    if (duplicated) continue
    items.value.push({
      id: newId(),
      file,
      progress: 0,
      status: 'queued',
    })
  }
}

function onInputChange(event: Event) {
  const target = event.target as HTMLInputElement
  if (target.files) addFiles(target.files)
  target.value = ''
}

function onDrop(event: DragEvent) {
  dragging.value = false
  if (event.dataTransfer?.files) addFiles(event.dataTransfer.files)
}

function removeItem(id: string) {
  if (uploading.value) return
  items.value = items.value.filter((item) => item.id !== id)
}

function toggleKb(id: string) {
  selectedKbIds.value = selectedKbIds.value.includes(id)
    ? selectedKbIds.value.filter((item) => item !== id)
    : [...selectedKbIds.value, id]
}

async function startUpload() {
  if (uploading.value) return
  const queue = items.value.filter((item) => item.status === 'queued')
  if (queue.length === 0) return

  uploading.value = true
  let success = 0
  let failed = 0

  const worker = async () => {
    while (queue.length > 0) {
      const item = queue.shift()
      if (!item) return
      item.status = 'uploading'
      item.progress = 0
      try {
        const docId = await uploadDocument(item.file, {
          kbIds: selectedKbIds.value,
          parseMode: parseMode.value,
          onProgress: (percent) => {
            item.progress = percent
          },
        })
        item.status = 'done'
        item.docId = docId
        success += 1
        emit('uploaded')
      } catch (error) {
        item.status = 'error'
        item.error = error instanceof Error ? error.message : '上传失败'
        failed += 1
      }
    }
  }

  await Promise.all([worker(), worker(), worker()])
  uploading.value = false

  if (success > 0) {
    toast.success(`已提交 ${success} 个文件`, '正在后台解析与分块，完成后需手动向量化')
  }
  if (failed > 0) {
    toast.error(`${failed} 个文件上传失败`, '请查看队列中的失败原因')
  }
}
</script>

<template>
  <AppDialog
    :open="open"
    title="上传文档"
    width="lg"
    :closeable="!uploading"
    @update:open="emit('update:open', $event)"
  >
    <template #subtitle>
      单个文件不超过 50MB
      <span class="mt-0.5 block text-ink-3">
        PDF / Word / Excel / PPT / HTML / Markdown / TXT 本地解析；其他格式请启用云端解析并选择 CLOUD，或把扩展名加入 cloud-types。
        上传后只解析与分块，不会自动向量化；需在文档详情检查/编辑切片后手动触发。
      </span>
    </template>

    <div class="space-y-4">
      <div
        class="relative flex flex-col items-center justify-center rounded-2xl border border-dashed px-6 py-8 text-center transition"
        :class="
          dragging
            ? 'border-brand-2 bg-cyan-soft'
            : 'border-line-strong bg-surface-2 hover:border-brand/50 hover:bg-brand/[0.04]'
        "
        @dragover.prevent="dragging = true"
        @dragleave.prevent="dragging = false"
        @drop.prevent="onDrop"
      >
        <div
          class="mb-3 flex h-12 w-12 items-center justify-center rounded-2xl border border-brand/25 bg-brand/10"
        >
          <UploadCloud class="h-5 w-5 text-brand-2" />
        </div>
        <p class="text-[13px] font-medium text-ink">拖拽文件到这里，或</p>
        <button type="button" class="btn btn-secondary btn-sm mt-2" @click="fileInput?.click()">
          <FileUp class="h-3.5 w-3.5" />
          选择文件
        </button>
        <p class="mt-2 text-[10.5px] text-ink-3">
          可多选，上传队列最多同时处理 3 个文件；解析分块完成后需手动向量化。
        </p>
        <input
          ref="fileInput"
          type="file"
          multiple
          class="hidden"
          @change="onInputChange"
        />
      </div>

      <div v-if="items.length > 0" class="max-h-56 space-y-1.5 overflow-y-auto pr-1">
        <div
          v-for="item in items"
          :key="item.id"
          class="rounded-xl border border-line bg-surface-2 p-2.5"
        >
          <div class="flex items-center gap-2.5">
            <FileTypeIcon :type="fileExtension(item.file.name)" :size="30" />
            <div class="min-w-0 flex-1">
              <p class="truncate text-[12px] text-ink">{{ item.file.name }}</p>
              <p class="mt-0.5 text-[10px] text-ink-3">
                {{ formatBytes(item.file.size) }}
                <template v-if="item.status === 'error'">
                  · <span class="text-danger">{{ item.error }}</span>
                </template>
                <template v-else-if="item.status === 'done'">
                  · <span class="text-success">已提交，等待解析与分块</span>
                </template>
              </p>
            </div>

            <span v-if="item.status === 'uploading'" class="font-mono text-[10px] text-brand-2">
              {{ item.progress }}%
            </span>
            <CheckCircle2
              v-else-if="item.status === 'done'"
              class="h-4 w-4 shrink-0 text-success"
            />
            <AlertCircle v-else-if="item.status === 'error'" class="h-4 w-4 shrink-0 text-danger" />
            <button
              v-else
              type="button"
              class="icon-btn h-6 w-6 shrink-0"
              aria-label="移除文件"
              @click="removeItem(item.id)"
            >
              <X class="h-3 w-3" />
            </button>
          </div>

          <div v-if="item.status === 'uploading'" class="mt-2 h-1 overflow-hidden rounded-full bg-surface-3">
            <div
              class="h-full rounded-full bg-brand transition-[width]"
              :style="{ width: `${Math.max(3, item.progress)}%` }"
            />
          </div>
        </div>
      </div>

      <div class="grid gap-3 sm:grid-cols-2">
        <div>
          <label class="label" for="parse-mode">解析模式</label>
          <select id="parse-mode" v-model="parseMode" class="select">
            <option value="AUTO">AUTO · 白名单优先云端</option>
            <option value="LOCAL">LOCAL · 仅本地解析</option>
            <option value="CLOUD">CLOUD · 强制云端 LlamaParse</option>
          </select>
        </div>

        <div>
          <span class="label">归属知识库</span>
          <div class="max-h-[112px] overflow-y-auto rounded-lg border border-line bg-surface-2 p-1.5">
            <label
              v-for="kb in kbs"
              :key="kb.id"
              class="flex cursor-pointer items-center gap-2 rounded-md px-1.5 py-1 text-[11.5px] text-ink-2 transition hover:bg-surface-2"
            >
              <input
                type="checkbox"
                class="accent-[#4176e6]"
                :checked="selectedKbIds.includes(kb.id)"
                @change="toggleKb(kb.id)"
              />
              <span class="h-1.5 w-1.5 rounded-full" :style="{ background: kb.color }" />
              <span class="truncate">{{ kb.name }}</span>
            </label>
            <p v-if="kbs.length === 0" class="px-1.5 py-1.5 text-[10.5px] text-ink-3">
              暂无知识库，文档将上传为未分类
            </p>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="mr-auto flex items-center gap-2 text-[11px] text-ink-3">
        <template v-if="items.length > 0">
          <span>{{ items.length }} 个文件</span>
          <span v-if="doneCount > 0" class="text-success">{{ doneCount }} 成功</span>
          <span v-if="errorCount > 0" class="text-danger">{{ errorCount }} 失败</span>
        </template>
        <span v-else>尚未选择文件</span>
      </div>

      <button
        type="button"
        class="btn btn-secondary"
        :disabled="uploading"
        @click="emit('update:open', false)"
      >
        {{ doneCount > 0 && queuedCount === 0 ? '完成' : '取消' }}
      </button>
      <button
        type="button"
        class="btn btn-primary"
        :disabled="uploading || queuedCount === 0"
        @click="startUpload"
      >
        <Loader2 v-if="uploading" class="h-3.5 w-3.5 animate-spin" />
        <UploadCloud v-else class="h-3.5 w-3.5" />
        {{ uploading ? '上传中…' : `开始上传${queuedCount ? ` (${queuedCount})` : ''}` }}
      </button>
    </template>
  </AppDialog>
</template>

