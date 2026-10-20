<script setup lang="ts">
import { Menu } from '@lucide/vue'
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const ui = useUiStore()

const title = computed(() => (route.meta.title as string | undefined) ?? '工作台')
</script>

<template>
  <header
    class="flex h-12 shrink-0 items-center border-b border-line bg-white px-4 sm:px-6"
  >
    <div class="flex min-w-0 items-center gap-2.5">
      <button
        type="button"
        class="icon-btn -ml-1 lg:hidden"
        aria-label="打开导航"
        @click="ui.openMobileNav()"
      >
        <Menu class="h-4 w-4" />
      </button>

      <div class="flex min-w-0 items-center gap-2 text-[12.5px]">
        <span class="hidden text-ink-3 sm:inline">Eon</span>
        <span class="hidden text-ink-3/60 sm:inline">/</span>
        <span class="truncate font-medium text-ink">{{ title }}</span>
      </div>
    </div>
  </header>
</template>
