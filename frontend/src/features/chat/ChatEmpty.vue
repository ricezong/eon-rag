<script setup lang="ts">
import { Sparkles } from '@lucide/vue'

const emit = defineEmits<{ (event: 'pick', question: string): void }>()

const suggestions = [
  '概括这份文档的核心结论',
  '支付网关需要哪些配置？',
  '列出关键限制与注意事项',
  '对比文档中的两种方案',
]
</script>

<template>
  <div class="flex flex-col items-center justify-center py-16 text-center">
    <div
      class="mb-6 flex h-14 w-14 items-center justify-center rounded-2xl border border-[#d6e4ff] bg-brand-soft"
    >
      <Sparkles class="h-6 w-6 text-brand" />
    </div>

    <h2 class="text-[22px] font-semibold tracking-tight text-ink">从你的知识库开始</h2>
    <p class="mt-2 max-w-md text-[13px] leading-relaxed text-ink-2">
      提问后，Eon 会先检索相关片段，再用可追溯的引用生成回答。
    </p>

    <div class="mt-7 grid w-full max-w-xl grid-cols-1 gap-2 sm:grid-cols-2">
      <button
        v-for="item in suggestions"
        :key="item"
        type="button"
        class="rounded-xl border border-line bg-white px-3.5 py-3 text-left text-[12px] text-ink-2 transition hover:-translate-y-0.5 hover:border-[#c7d9ff] hover:bg-brand-soft/50 hover:text-ink"
        @click="emit('pick', item)"
      >
        {{ item }}
      </button>
    </div>
  </div>
</template>
