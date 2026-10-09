export interface Report {
  id: string;
  projectId: string;
  title: string;
  sessionIds: string[];
  markdown: string;          // 后端聚合生成的 Markdown 全文
  createdAt: string;
  logCount: number;          // 纳入报告的日志数
  diagnosisCount: number;
}

export interface ModelConfig {
  provider: string;
  model: string;
  baseUrl: string;
}
