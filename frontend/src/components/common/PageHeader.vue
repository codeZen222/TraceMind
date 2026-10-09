<script setup lang="ts">
defineProps<{
  title: string
  subtitle?: string
}>()
</script>

<template>
  <div class="mb-4 flex flex-wrap items-center gap-3">
    <div class="min-w-0">
      <h2 class="truncate text-[17px] font-semibold">{{ title }}</h2>
      <p v-if="subtitle" class="mt-0.5 text-[13px] text-text-3">{{ subtitle }}</p>
    </div>
    <div class="ml-auto flex items-center gap-2">
      <slot name="actions" />
    </div>
  </div>
</template>
