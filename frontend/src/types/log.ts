export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

export interface LogEntry {
  id: string;
  projectId: string;
  timestamp: string;
  level: LogLevel;
  rawText: string;          // 原始日志行
  template: string;         // Drain3 模板，如 "User * login failed from *"
  clusterId: string;        // 如 "cluster_42"
  filePath: string;
  lineNumber: number;
  stackTrace: string | null; // 异常堆栈（多行，用 \n 连接）
}

export interface LogStats {
  levelDistribution: { level: LogLevel; count: number }[];
  frequencyTrend: { time: string; count: number }[];       // 24h 趋势，按小时
  topTemplates: { template: string; clusterId: string; count: number; level: LogLevel }[];
}

export interface LogQuery {
  keyword?: string;
  level?: LogLevel[];
  from?: string;
  to?: string;
  clusterId?: string;
  page?: number;
  size?: number;
}

export interface ImportProgress {
  total: number;
  processed: number;
  status: 'RUNNING' | 'DONE' | 'FAILED';
}

/** 新错误雷达的一行：历史上从没见过、且未读的错误模板 */
export interface NewError {
  clusterId: string;      // 错误指纹（Drain3 聚类 id）
  template: string;       // Drain3 模板
  firstSeenAt: string;    // 首次出现时间，ISO-8601
  count: number;          // 该错误模板的条数
}
