import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/chat' },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/pages/ChatPage.vue'),
    meta: { title: '问答' },
  },
  {
    path: '/documents',
    name: 'documents',
    component: () => import('@/pages/DocumentsPage.vue'),
    meta: { title: '文档' },
  },
  {
    path: '/documents/:id',
    name: 'document-detail',
    component: () => import('@/pages/DocumentDetailPage.vue'),
    meta: { title: '文档详情' },
  },
  {
    path: '/kbs',
    name: 'kbs',
    component: () => import('@/pages/KbsPage.vue'),
    meta: { title: '知识库' },
  },
  {
    path: '/kbs/:id',
    name: 'kb-detail',
    component: () => import('@/pages/KbDetailPage.vue'),
    meta: { title: '知识库详情' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/pages/NotFoundPage.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · Eon` : 'Eon · 知识库问答'
})

export default router
