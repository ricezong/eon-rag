<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '@/app/AppShell.vue'
import ToastViewport from '@/components/ui/ToastViewport.vue'
import { useToastStore } from '@/stores/toast'

const route = useRoute()
const toast = useToastStore()

function onUnauthorized() {
  toast.error('鉴权失败', '请检查后端鉴权配置。')
}

onMounted(() => {
  window.addEventListener('eon:unauthorized', onUnauthorized)
})

onBeforeUnmount(() => {
  window.removeEventListener('eon:unauthorized', onUnauthorized)
})
</script>

<template>
  <AppShell>
    <RouterView v-slot="{ Component }">
      <Transition name="page" mode="out-in">
        <component :is="Component" :key="route.path" />
      </Transition>
    </RouterView>
  </AppShell>

  <ToastViewport />
</template>
