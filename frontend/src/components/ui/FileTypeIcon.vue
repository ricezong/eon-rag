<script setup lang="ts">
import { File, FileCode2, FileSpreadsheet, FileText, Presentation } from '@lucide/vue'
import { computed, type Component } from 'vue'

const props = withDefaults(
  defineProps<{
    type: string
    size?: number
  }>(),
  { size: 34 },
)

interface TypeStyle {
  icon: Component
  gradient: string
  text: string
}

const styles: Record<string, TypeStyle> = {
  pdf: {
    icon: FileText,
    gradient: 'from-rose-500/10 to-orange-400/5',
    text: 'text-rose-600',
  },
  doc: {
    icon: FileText,
    gradient: 'from-sky-500/10 to-blue-400/5',
    text: 'text-sky-600',
  },
  docx: {
    icon: FileText,
    gradient: 'from-sky-500/10 to-blue-400/5',
    text: 'text-sky-600',
  },
  xls: {
    icon: FileSpreadsheet,
    gradient: 'from-emerald-500/10 to-teal-400/5',
    text: 'text-emerald-600',
  },
  xlsx: {
    icon: FileSpreadsheet,
    gradient: 'from-emerald-500/10 to-teal-400/5',
    text: 'text-emerald-600',
  },
  csv: {
    icon: FileSpreadsheet,
    gradient: 'from-emerald-500/10 to-teal-400/5',
    text: 'text-emerald-600',
  },
  ppt: {
    icon: Presentation,
    gradient: 'from-amber-500/10 to-orange-400/5',
    text: 'text-amber-600',
  },
  pptx: {
    icon: Presentation,
    gradient: 'from-amber-500/10 to-orange-400/5',
    text: 'text-amber-600',
  },
  md: {
    icon: FileCode2,
    gradient: 'from-violet-500/10 to-fuchsia-400/5',
    text: 'text-violet-600',
  },
  markdown: {
    icon: FileCode2,
    gradient: 'from-violet-500/10 to-fuchsia-400/5',
    text: 'text-violet-600',
  },
  html: {
    icon: FileCode2,
    gradient: 'from-orange-500/10 to-amber-400/5',
    text: 'text-orange-600',
  },
  htm: {
    icon: FileCode2,
    gradient: 'from-orange-500/10 to-amber-400/5',
    text: 'text-orange-600',
  },
  txt: {
    icon: FileText,
    gradient: 'from-slate-400/10 to-slate-300/5',
    text: 'text-slate-500',
  },
}

const fallback: TypeStyle = {
  icon: File,
  gradient: 'from-slate-400/10 to-slate-300/5',
  text: 'text-slate-500',
}

const style = computed(() => styles[props.type?.toLowerCase()] ?? fallback)
</script>

<template>
  <div
    class="flex shrink-0 items-center justify-center rounded-[10px] border border-line/80 bg-gradient-to-br"
    :class="[style.gradient, style.text]"
    :style="{ width: `${size}px`, height: `${size}px` }"
  >
    <component :is="style.icon" :size="Math.round(size * 0.46)" :stroke-width="1.7" />
  </div>
</template>

