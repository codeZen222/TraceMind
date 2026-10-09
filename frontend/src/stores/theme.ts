import { defineStore } from 'pinia'
import { ref } from 'vue'

const KEY = 'tm-theme'

export const useThemeStore = defineStore('theme', () => {
  const current = ref<'dark' | 'light'>('dark')

  function init() {
    const saved = localStorage.getItem(KEY)
    if (saved === 'dark' || saved === 'light') {
      current.value = saved
    } else if (window.matchMedia('(prefers-color-scheme: light)').matches) {
      current.value = 'light'
    }
    apply()
  }

  function apply() {
    document.documentElement.classList.toggle('dark', current.value === 'dark')
  }

  function toggle() {
    current.value = current.value === 'dark' ? 'light' : 'dark'
    localStorage.setItem(KEY, current.value)
    apply()
  }

  return { current, init, toggle }
})
