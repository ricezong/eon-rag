<script setup lang="ts">
import { computed } from 'vue'
import { useUiStore } from '@/stores/ui'
import SidebarNav from './SidebarNav.vue'
import TopBar from './TopBar.vue'

const ui = useUiStore()
const asideClass = computed(() => (ui.sidebarCollapsed ? 'w-[76px]' : 'w-[248px]'))
</script>

<template>
  <div class="flex h-dvh overflow-hidden bg-[#f9fafb]">
    <aside
      class="relative z-20 hidden shrink-0 border-r border-line bg-[#f9fafb] transition-[width] duration-200 lg:flex"
      :class="asideClass"
    >
      <SidebarNav class="w-full" />
    </aside>

    <div class="flex min-w-0 flex-1 flex-col bg-white">
      <TopBar />
      <main class="min-h-0 flex-1 overflow-y-auto">
        <slot />
      </main>
    </div>

    <Teleport to="body">
      <Transition name="modal">
        <div v-if="ui.mobileNavOpen" class="fixed inset-0 z-40 lg:hidden">
          <div class="absolute inset-0 bg-[#0f1115]/30" @click="ui.closeMobileNav()" />
          <aside
            class="modal-card relative h-full w-[278px] border-r border-line bg-[#f9fafb] shadow-2xl"
          >
            <SidebarNav mobile class="h-full" />
          </aside>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
