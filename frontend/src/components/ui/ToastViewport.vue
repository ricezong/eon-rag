<script setup lang="ts">
import { AlertCircle, CheckCircle2, Info, X } from '@lucide/vue'
import { useToastStore } from '@/stores/toast'

const toast = useToastStore()
</script>

<template>
  <Teleport to="body">
    <TransitionGroup
      name="list"
      tag="div"
      class="pointer-events-none fixed bottom-5 right-5 z-[100] flex w-[min(92vw,370px)] flex-col gap-2"
    >
      <div
        v-for="item in toast.items"
        :key="item.id"
        class="pointer-events-auto flex items-start gap-3 rounded-xl border border-[#43454a] bg-[#353638] p-3.5 shadow-[0_12px_32px_rgba(15,17,21,.2)]"
      >
        <CheckCircle2 v-if="item.type === 'success'" class="mt-0.5 h-4 w-4 shrink-0 text-[#4ade80]" />
        <AlertCircle v-else-if="item.type === 'error'" class="mt-0.5 h-4 w-4 shrink-0 text-[#f87171]" />
        <Info v-else class="mt-0.5 h-4 w-4 shrink-0 text-[#93c5fd]" />

        <div class="min-w-0 flex-1">
          <p class="text-[13px] font-medium text-white">{{ item.title }}</p>
          <p v-if="item.message" class="mt-1 break-words text-[12px] leading-relaxed text-white/70">
            {{ item.message }}
          </p>
        </div>

        <button
          type="button"
          class="icon-btn h-6 w-6 shrink-0 text-white/60 hover:!bg-white/10 hover:!text-white"
          aria-label="关闭提示"
          @click="toast.remove(item.id)"
        >
          <X class="h-3.5 w-3.5" />
        </button>
      </div>
    </TransitionGroup>
  </Teleport>
</template>
