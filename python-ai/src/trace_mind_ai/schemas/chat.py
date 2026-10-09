"""追问相关契约 —— 与 ChatMessageVO / ChatRequestDTO 对齐。

对应文件：pojo/vo/ChatMessageVO.java、pojo/dto/ChatRequestDTO.java

★ 一个容易被忽略的契约约束：
  PythonClient.chat() 只发了 {sessionId, question, history} 三个字段，
  **没有 projectId，也没有触发日志**。
  所以 /v1/chat 必须自己从 MySQL 按 sessionId 反查会话，
  才能知道这条追问是针对哪个项目、哪次诊断的。
  实现见 api/v1/chat.py。
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class ChatMessage(BaseModel):
    """一条对话消息。与 ChatMessageVO 一致。"""

    model_config = ConfigDict(extra="ignore")

    id: str | None = None
    role: Literal["user", "assistant"]
    content: str
    ts: int | None = None


class ChatRequest(BaseModel):
    """POST /v1/chat 的请求体。

    字段名严格对齐 Java 侧 Map.of("sessionId",..., "question",..., "history",...)
    """

    model_config = ConfigDict(extra="ignore")

    sessionId: str  # noqa: N815
    question: str = Field(min_length=1, max_length=4000)
    history: list[ChatMessage] = Field(default_factory=list)