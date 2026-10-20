<script setup lang="ts">
import { Compass, Home } from '@lucide/vue'
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="flex h-full flex-col items-center justify-center px-6 text-center">
    <div
      class="mb-6 flex h-16 w-16 items-center justify-center rounded-2xl border border-[#d6e4ff] bg-brand-soft"
    >
      <Compass class="h-7 w-7 text-brand" />
    </div>
    <p class="font-display text-[48px] font-semibold leading-none text-ink/80">404</p>
    <h1 class="mt-3 text-[16px] font-semibold text-ink">页面走丢了</h1>
    <p class="mt-2 max-w-sm text-[12.5px] leading-relaxed text-ink-2">
      你访问的页面不存在，或地址已经变更。
    </p>
    <button type="button" class="btn btn-primary mt-6" @click="router.push('/chat')">
      <Home class="h-4 w-4" />
      回到问答
    </button>
  </div>
</template>
