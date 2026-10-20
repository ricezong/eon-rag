import { defineStore } from 'pinia'

export const useUiStore = defineStore('ui', {
  state: () => ({
    sidebarCollapsed: false,
    mobileNavOpen: false,
    mobileCitationsOpen: false,
    citationRailOpen: false,
  }),

  actions: {
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
    },
    openMobileNav() {
      this.mobileNavOpen = true
    },
    closeMobileNav() {
      this.mobileNavOpen = false
    },
    setMobileCitations(open: boolean) {
      this.mobileCitationsOpen = open
    },
    toggleCitationRail() {
      this.citationRailOpen = !this.citationRailOpen
    },
  },
})
