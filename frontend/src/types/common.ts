export interface ApiResponse<T> {
  code: number;      // 0 = 成功，非 0 = 业务错误
  message: string;
  data: T;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}

export interface HealthItem {
  name: string;
  ok: boolean;
  latencyMs: number;
}
