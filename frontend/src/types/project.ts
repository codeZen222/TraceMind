export type ProjectStatus = 'DEVELOPING' | 'DEPLOYED'; // 开发中 | 已上线
export type ProjectSource = 'UPLOAD' | 'PATH';         // 上传日志文件夹 | 监听服务器路径

export interface Project {
  id: string;                 // uuid
  name: string;
  description: string;
  status: ProjectStatus;
  language: string;           // 例 'Java' / 'Spring Boot'
  source?: ProjectSource;     // 接入方式（旧数据缺省视为 PATH）
  logPath: string;            // PATH: 服务器日志文件绝对路径；UPLOAD: 文件夹名
  fileCount?: number | null;   // UPLOAD: 上传的日志文件数
  healthUrl: string | null;   // 健康检查端点，对应 check_project_health 工具
  sourceRoot: string | null;  // 源码根目录，对应 read_source_code 工具
  createdAt: string;
  updatedAt: string;
  // 派生统计（列表页展示用）
  errorCount24h: number;
  diagnosisCount: number;
}

export type ProjectForm = Partial<Project> & {
  name?: string;
  logPath?: string;
  status?: ProjectStatus;
  /** 上传模式：文件夹名与文件列表 */
  folderName?: string;
  files?: string[];
};
