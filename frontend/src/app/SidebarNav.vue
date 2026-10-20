<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import {
  Files,
  LibraryBig,
  MessageSquare,
  MessageSquareText,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Trash2,
  X,
} from '@lucide/vue'
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BrandMark from '@/components/brand/BrandMark.vue'
import { apiGet } from '@/lib/api'
import { formatRelative } from '@/lib/format'
import { useChatStore } from '@/stores/chat'
import { useUiStore } from '@/stores/ui'
import type { KnowledgeBase } from '@/types/api'

const props = withDefaults(defineProps<{ mobile?: boolean }>(), { mobile: false })

const ui = useUiStore()
const chat = useChatStore()
const router = useRouter()
const route = useRoute()

const collapsed = computed(() => !props.mobile && ui.sidebarCollapsed)
const activePath = computed(() => route.path)
const sessions = computed(() => chat.sortedSessions)

const navItems = [
  { to: '/chat', label: '问答', icon: MessageSquareText },
  { to: '/documents', label: '文档', icon: Files },
  { to: '/kbs', label: '知识库', icon: LibraryBig },
]

const { data: kbs } = useQuery({
  queryKey: ['kbs'],
  queryFn: () => apiGet<KnowledgeBase[]>('/api/kbs'),
  staleTime: 30_000,
})

const recentKbs = computed(() => (kbs.value ?? []).slice(0, 6))

onMounted(() => {
  void chat.hydrate()
})

function newChat() {
  chat.newSession()
  ui.citationRailOpen = false
  ui.closeMobileNav()
  if (route.path !== '/chat') router.push('/chat')
}

function navigate(to: string) {
  ui.closeMobileNav()
  router.push(to)
}

function selectSession(id: string) {
  chat.loadSession(id)
  ui.closeMobileNav()
  if (route.path !== '/chat') router.push('/chat')
}

function deleteSession(id: string) {
  if (id === chat.currentId) ui.citationRailOpen = false
  chat.deleteSession(id)
}

function sessionTime(updatedAt: number): string {
  return formatRelative(new Date(updatedAt).toISOString())
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col bg-[#f9fafb]">
    <div
      class="flex items-center justify-between px-4 pb-3 pt-4"
      :class="collapsed ? 'justify-center px-2' : ''"
    >
      <BrandMark :size="30" :with-wordmark="!collapsed" />
      <button
        v-if="mobile"
        type="button"
        class="icon-btn"
        aria-label="关闭导航"
        @click="ui.closeMobileNav()"
      >
        <X class="h-4 w-4" />
      </button>
    </div>

    <div class="px-3">
      <button
        type="button"
        class="btn btn-primary w-full"
        :class="collapsed ? '!px-0' : ''"
        :title="collapsed ? '新对话' : undefined"
        @click="newChat"
      >
        <Plus class="h-4 w-4 shrink-0" />
        <span v-if="!collapsed">新对话</span>
      </button>
    </div>

    <nav class="mt-4 space-y-0.5 px-3">
      <button
        v-for="item in navItems"
        :key="item.to"
        type="button"
        class="nav-item w-full"
        :class="[
          activePath.startsWith(item.to) ? 'router-link-active' : '',
          collapsed ? 'justify-center px-0' : '',
        ]"
        :title="collapsed ? item.label : undefined"
        @click="navigate(item.to)"
      >
        <component :is="item.icon" class="h-[16px] w-[16px] shrink-0" :stroke-width="1.8" />
        <span v-if="!collapsed">{{ item.label }}</span>
      </button>
    </nav>

    <div v-if="!collapsed" class="mt-5 min-h-0 flex-1 overflow-y-auto px-3 pb-4">
      <section>
        <div class="mb-1.5 flex items-center justify-between px-2">
          <span class="section-title">最近对话</span>
          <span class="text-[10px] text-ink-3">{{ sessions.length }}</span>
        </div>

        <div class="space-y-0.5">
          <div
            v-for="session in sessions"
            :key="session.id"
            class="group flex items-center gap-0.5 rounded-lg px-1.5 py-1.5 transition"
            :class="
              session.id === chat.currentId
                ? 'bg-surface-3'
                : 'hover:bg-surface-2'
            "
          >
            <button
              type="button"
              class="flex min-w-0 flex-1 items-center gap-2 text-left"
              @click="selectSession(session.id)"
            >
              <MessageSquare
                class="h-3.5 w-3.5 shrink-0"
                :class="session.id === chat.currentId ? 'text-brand' : 'text-ink-3'"
                :stroke-width="1.8"
              />
              <span class="min-w-0 flex-1">
                <span
                  class="block truncate text-[12px]"
                  :class="
                    session.id === chat.currentId ? 'font-medium text-ink' : 'text-ink-2'
                  "
                >
                  {{ session.title }}
                </span>
                <span class="mt-0.5 block text-[10px] text-ink-3">
                  {{ sessionTime(session.updatedAt) }}
                </span>
              </span>
            </button>
            <button
              type="button"
              class="icon-btn h-6 w-6 shrink-0 opacity-100 transition hover:!text-danger sm:opacity-60 sm:group-hover:opacity-100"
              aria-label="删除会话"
              @click.stop="deleteSession(session.id)"
            >
              <Trash2 class="h-3 w-3" />
            </button>
          </div>

          <p v-if="sessions.length === 0" class="px-2 py-2 text-[11px] text-ink-3">
            还没有历史对话
          </p>
        </div>
      </section>

      <section class="mt-6">
        <div class="mb-1.5 flex items-center justify-between px-2">
          <span class="section-title">知识库</span>
          <button
            type="button"
            class="text-[10.5px] text-ink-3 transition hover:text-ink"
            @click="navigate('/kbs')"
          >
            管理
          </button>
        </div>

        <div class="space-y-0.5">
          <button
            v-for="kb in recentKbs"
            :key="kb.id"
            type="button"
            class="flex h-8 w-full items-center gap-2 rounded-lg px-2 text-left text-[12px] text-ink-2 transition hover:bg-surface-2 hover:text-ink"
            @click="navigate(`/kbs/${kb.id}`)"
          >
            <span class="h-1.5 w-1.5 shrink-0 rounded-full" :style="{ background: kb.color }" />
            <span class="truncate">{{ kb.name }}</span>
            <span class="ml-auto text-[10px] text-ink-3">{{ kb.documentCount ?? 0 }}</span>
          </button>

          <p v-if="recentKbs.length === 0" class="px-2 py-2 text-[11px] text-ink-3">
            还没有知识库
          </p>
        </div>
      </section>
    </div>

    <div class="mt-auto border-t border-line px-3 py-2.5">
      <button
        v-if="!mobile"
        type="button"
        class="flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-[11px] text-ink-3 transition hover:bg-surface-2 hover:text-ink-2"
        :class="collapsed ? 'justify-center' : ''"
        :aria-label="collapsed ? '展开侧栏' : '收起侧栏'"
        :title="collapsed ? '展开侧栏' : '收起侧栏'"
        @click="ui.toggleSidebar()"
      >
        <PanelLeftOpen v-if="collapsed" class="h-3.5 w-3.5" />
        <PanelLeftClose v-else class="h-3.5 w-3.5" />
        <span v-if="!collapsed">收起侧栏</span>
      </button>
    </div>
  </div>
</template>
