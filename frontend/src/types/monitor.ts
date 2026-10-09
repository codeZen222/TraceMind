export interface MonitorStatus {
  /**
   * 无监控任务时后端返回 null（GET /monitor 不 404，而是回一份 running=false 的空壳），
   * 所以这里必须是可空类型 —— 写成 string 会让 TS 给出"一定存在"的假保证，
   * 掩盖掉"这个项目还没开监听"这个真实分支。
   */
  taskId: string | null;
  projectId: string;
  running: boolean;
  /** 同上：无任务时为 null */
  filePath: string | null;
  offset: number;            // Redis 中记录的读取偏移
  fileSize: number;
  lastReadAt: string | null;
  linesRead: number;         // 累计读取行数
  errorsDetected: number;    // 累计检出 ERROR 数
  ruleMatched: number;       // 阶段2规则命中数
  agentTriggered: number;    // 阶段触发数
}
