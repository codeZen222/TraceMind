import type { LogEntry } from './log'

export type StageId = 1 | 2 | 3;
export type DiagnosisStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'DEGRADED';
// DEGRADED = Python 服务不可用，降级返回规则结果

export type ToolName =
  | 'search_logs'
  | 'search_similar_cases'
  | 'match_exception_rules'
  | 'count_error_frequency'
  | 'read_source_code'
  | 'check_project_health';

/** 诊断链路事件 —— 后端通过 WebSocket 推送，与 REST 历史回放结构一致 */
export type DiagnosisEvent =
  | { type: 'stage';       stage: StageId; name: string; status: 'running' | 'done'; costMs?: number }
  | { type: 'rule_hit';    analyzer: string; solution: string; costMs: number }
  | { type: 'tool_call';   tool: ToolName; args: Record<string, unknown> }
  | { type: 'tool_result'; tool: ToolName; summary: string; truncated: boolean; costMs: number }
  | { type: 'token';       content: string }                     // LLM 流式 token
  | { type: 'thinking';    content: string }                     // Agent 推理说明（可折叠）
  | { type: 'result';      rootCause: string; evidence: string[]; suggestion: string; confidence: number }
  | { type: 'error';       stage: StageId; message: string };

/** WebSocket 传输层包装（带序号用于去重与断线补齐） */
export interface WsMessage {
  seq: number;          // 自增序号，前端按 seq 排序、去重
  sessionId: string;
  ts: number;           // 毫秒时间戳
  event: DiagnosisEvent;
}

export interface DiagnosisResult {
  rootCause: string;
  evidence: string[];
  suggestion: string;
  confidence: number;               // 0~1
  toolCallCount: number;
  relatedLogIds: string[];
}

export interface DiagnosisSession {
  sessionId: string;
  projectId: string;
  projectName: string;              // 冗余字段，列表展示免二次查询
  status: DiagnosisStatus;
  triggerLog: LogEntry;             // 触发本次诊断的 ERROR 日志
  startedAt: string;
  finishedAt: string | null;
  totalCostMs: number | null;
  llmUsed: boolean;                 // false = 阶段2规则命中，未调 LLM
  matchedAnalyzer: string | null;   // 阶段2命中的规则名
  result: DiagnosisResult | null;
  events: WsMessage[];              // 历史回放（断线重连补齐用）
}

/**
 * 追问过程中 Agent 调工具的一步 —— **纯前端展示字段**。
 * 会跟着 messages 一起当 history 回传，后端 ChatMessageVO 配了忽略未知字段，不会有影响。
 */
export interface ChatStep {
  tool: string;
  args?: string;                    // 入参摘要，如 keyword=SQLTransient
  summary?: string;                 // 工具返回的摘要
  costMs?: number;
  truncated?: boolean;
  state: 'running' | 'done';
}

/** 人机追问对话：诊断结论产出后，用户继续与 AI 就本会话上下文问答 */
export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  streaming?: boolean;              // 助助端流式输出中
  steps?: ChatStep[];               // 本轮 Agent 调了哪些工具（本地字段）
  ts: number;
}

/**
 * 追问 SSE 的一帧。
 *
 * ★ 追问接入 Agent 之后，流里除了回答文本还有"工具过程"，
 *   一帧一个 JSON 才能区分开；纯文本流表达不了这些。
 *   字段与 Python 的 agents/chat.py :: text_frame / tool_call_frame / tool_result_frame 对齐。
 *
 * ⚠️ 解析时**不要对 payload 做 trim** —— 文本帧 content 的首尾空格是有意义的
 *   （英文单词之间的分隔符就是它）。
 */
export type ChatFrame =
  | { type: 'text'; content: string }
  | { type: 'tool_call'; tool: string; args: Record<string, unknown> }
  | { type: 'tool_result'; tool: string; summary: string; truncated: boolean; costMs: number };
