<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { CopyDocument, Download, Printer } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/common/PageHeader.vue'
import DataState from '@/components/common/DataState.vue'
import MarkdownView from '@/components/report/MarkdownView.vue'
import { getReport, downloadReportMarkdown } from '@/api/report'
import type { Report } from '@/types/report'
import { useLogFormat } from '@/composables/useLogFormat'

const route = useRoute()
const { rel } = useLogFormat()

const loading = ref(true)
const error = ref<string | null>(null)
const report = ref<Report | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    report.value = await getReport(String(route.params.id))
  } catch (e) {
    error.value = e instanceof Error ? e.message : '报告不存在'
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function copyMd() {
  if (!report.value) return
  await navigator.clipboard.writeText(report.value.markdown)
  ElMessage.success('Markdown 已复制')
}

function print() {
  window.print()
}
</script>

<template>
  <div>
    <DataState :loading="loading" :error="error" :retry="load">
      <template v-if="report">
        <PageHeader :title="report.title" :subtitle="`生成于 ${rel(report.createdAt)} · 纳入日志 ${report.logCount} 条`">
          <template #actions>
            <el-button size="small" :icon="CopyDocument" @click="copyMd">复制 Markdown</el-button>
            <el-button size="small" :icon="Download" @click="downloadReportMarkdown(report)">下载 .md</el-button>
            <el-button size="small" :icon="Printer" @click="print">打印 / 导出 PDF</el-button>
          </template>
        </PageHeader>
        <MarkdownView :markdown="report.markdown" />
      </template>
    </DataState>
  </div>
</template>
