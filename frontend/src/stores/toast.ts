import { defineStore } from 'pinia'

export type ToastType = 'success' | 'error' | 'info'

export interface ToastItem {
  id: string
  type: ToastType
  title: string
  message?: string
}

function newId(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export const useToastStore = defineStore('toast', {
  state: () => ({
    items: [] as ToastItem[],
  }),

  actions: {
    push(type: ToastType, title: string, message?: string, duration = 4200) {
      const id = newId()
      this.items.push({ id, type, title, message })
      if (duration > 0) {
        window.setTimeout(() => this.remove(id), duration)
      }
      return id
    },
    success(title: string, message?: string) {
      return this.push('success', title, message)
    },
    error(title: string, message?: string) {
      return this.push('error', title, message, 6000)
    },
    info(title: string, message?: string) {
      return this.push('info', title, message)
    },
    remove(id: string) {
      this.items = this.items.filter((item) => item.id !== id)
    },
  },
})
