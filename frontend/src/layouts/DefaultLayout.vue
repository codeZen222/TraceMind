<script setup lang="ts">
import AppSidebar from './components/AppSidebar.vue'
import AppHeader from './components/AppHeader.vue'
</script>

<template>
  <div class="flex h-full min-h-screen bg-bg">
    <AppSidebar />
    <div class="flex min-w-0 flex-1 flex-col">
      <AppHeader />
      <main class="flex-1 overflow-y-auto">
        <div
          class="mx-auto w-full px-6 py-5"
          :class="$route.meta.full ? 'max-w-none' : 'max-w-[1600px]'"
        >
          <router-view />
        </div>
      </main>
    </div>
  </div>
</template>
