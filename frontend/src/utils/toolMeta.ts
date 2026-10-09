/**
 * 六类工具的中文名。
 *
 * ★ 工具名（`search_logs` 这些）是后端契约，**一个字都不能改** ——
 *   Java 的 DiagnosisEventVO 注释和 Python 的 tools/registry.py 都写死了，
 *   前端 ToolCallCard 也是按这些名字映射图标文案的。
 *   这里只是把"展示用中文名"收成一份，诊断页的工具卡片和追问的气泡共用，
 *   免得两处各写一份慢慢长歪。
 */
export const TOOL_LABELS: Record<string, string> = {
  search_logs: '检索相关日志',
  search_similar_cases: '检索历史案例',
  match_exception_rules: '匹配异常规则',
  count_error_frequency: '统计报错频次',
  read_source_code: '阅读源码',
  check_project_health: '检查运行时健康',
}

export function toolLabel(name: string): string {
  return TOOL_LABELS[name] ?? name
}
