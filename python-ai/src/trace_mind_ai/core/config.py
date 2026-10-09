"""分层配置。

优先级（后者覆盖前者）：
    代码默认值  <  .env 文件  <  真实环境变量

设计取舍：
  · 所有配置集中在这里校验，业务代码只 `from ... import get_settings`，
    绝不直接读 os.environ —— 否则配置来源就散了，没人知道有哪些配置项
  · 必须项不给默认值（比如 LLM_API_KEY）—— 缺了就在启动时炸，
    而不是等第一次诊断时才发现
  · 生产环境加硬校验：配错比缺配置更危险
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from trace_mind_ai import __version__

_ENV_FILE = ".env"
_ENV_FILE_ENCODING = "utf-8"


# ══════════════════════════════════════════════════════════
# 各分区配置
# 每一区独立读环境变量：前缀决定变量名，例如 LLM_ 前缀 + model 字段
# ══════════════════════════════════════════════════════════


class LLMSettings(BaseSettings):
    """大模型接入。走外部 API，不本地部署。"""

    model_config = SettingsConfigDict(
        env_prefix="LLM_",
        env_file=_ENV_FILE,
        env_file_encoding=_ENV_FILE_ENCODING,
        extra="ignore",
    )

    base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    api_key: SecretStr = Field(description="★ 必填。用 SecretStr 让它打印时自动变星号")
    model: str = "qwen-plus"
    timeout_s: float = 60.0
    max_retries: int = 2
    temperature: float = 0.1
    #: 单次诊断允许的最大模型调用次数 —— 成本闸门。
    #: Agent 循环是乘法级消耗（每轮重发全部历史），
    #: 没有这个上限，一个死循环能烧掉一天的额度。
    max_calls_per_diagnosis: int = 12

    @field_validator("base_url")
    @classmethod
    def _check_base_url(cls, value: str) -> str:
        value = value.strip().rstrip("/")
        if not value.startswith(("http://", "https://")):
            raise ValueError(f"LLM_BASE_URL 必须是 http(s) 地址，当前 = {value!r}")
        return value

    @field_validator("api_key")
    @classmethod
    def _check_api_key(cls, value: SecretStr) -> SecretStr:
        """拦住「填了但填的是占位符」这种情况。

        比"没填"更危险的是"填了占位符"：服务能起来，
        但每次调用都 401，而且报错出现在运行期而非启动期。
        """
        raw = value.get_secret_value().strip()
        placeholders = ("sk-replace-me", "sk-xxx", "your-", "change-me", "todo")
        if not raw or raw.lower().startswith(placeholders):
            raise ValueError(
                "LLM_API_KEY 还是占位符。请去服务商控制台申请真实 Key 后写入 .env"
            )
        return SecretStr(raw)

    @field_validator("timeout_s")
    @classmethod
    def _check_timeout(cls, value: float) -> float:
        if not 1.0 <= value <= 600.0:
            raise ValueError(f"LLM_TIMEOUT_S 应在 1~600 之间，当前 = {value}")
        return value


class ESSettings(BaseSettings):
    """Elasticsearch —— 日志正文的唯一存储。"""

    model_config = SettingsConfigDict(
        env_prefix="ES_",
        env_file=_ENV_FILE,
        env_file_encoding=_ENV_FILE_ENCODING,
        extra="ignore",
    )

    #: 逗号分隔的多地址。声明成 str 而不是 list[str]：
    #: pydantic-settings 对复杂类型会尝试 JSON 解析，"a,b" 会直接报格式错。
    #: 用 str 接住，再用 host_list 属性切开。
    hosts: str = "http://192.168.100.128:9200"
    index_logs: str = "log_entry"
    request_timeout_s: float = 5.0

    @property
    def host_list(self) -> list[str]:
        return [item.strip() for item in self.hosts.split(",") if item.strip()]


class RedisSettings(BaseSettings):
    """Redis —— 会话级序号、模板缓存。"""

    model_config = SettingsConfigDict(
        env_prefix="REDIS_",
        env_file=_ENV_FILE,
        env_file_encoding=_ENV_FILE_ENCODING,
        extra="ignore",
    )

    url: str = "redis://localhost:6379/0"
    socket_timeout_s: float = 3.0


class MySQLSettings(BaseSettings):
    """MySQL —— 只读项目元数据与会话信息（日志正文在 ES）。"""

    model_config = SettingsConfigDict(
        env_prefix="MYSQL_",
        env_file=_ENV_FILE,
        env_file_encoding=_ENV_FILE_ENCODING,
        extra="ignore",
    )

    dsn: str = Field(description="★ 必填。SQLAlchemy async DSN，形如 mysql+asyncmy://...")
    echo: bool = False
    pool_size: int = 5
    max_overflow: int = 5

    @field_validator("dsn")
    @classmethod
    def _check_dsn(cls, value: str) -> str:
        if not value.startswith("mysql+asyncmy://"):
            raise ValueError(
                "MYSQL_DSN 必须以 mysql+asyncmy:// 开头（异步驱动），"
                f"当前 = {value.split('://')[0]}://..."
            )
        return value


class ChromaSettings(BaseSettings):
    """向量库 —— 历史诊断案例召回。本地持久化，不额外部署服务。"""

    model_config = SettingsConfigDict(
        env_prefix="CHROMA_",
        env_file=_ENV_FILE,
        env_file_encoding=_ENV_FILE_ENCODING,
        extra="ignore",
    )

    persist_dir: str = "./.data/chroma"
    collection: str = "diagnosis_cases"


class ServerSettings(BaseSettings):
    """HTTP 服务。"""

    model_config = SettingsConfigDict(
        env_prefix="SERVER_",
        env_file=_ENV_FILE,
        env_file_encoding=_ENV_FILE_ENCODING,
        extra="ignore",
    )

    #: ★ 默认 127.0.0.1 是安全设计：本服务持有大模型 API Key，
    #: 绝不能默认监听 0.0.0.0。要对外暴露必须显式改，改的人得知道自己在干什么。
    host: str = "127.0.0.1"
    port: int = 8100
    debug: bool = False


class LogSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="LOG_",
        env_file=_ENV_FILE,
        env_file_encoding=_ENV_FILE_ENCODING,
        extra="ignore",
    )

    level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    format: Literal["console", "json"] = "console"


class BudgetSettings(BaseSettings):
    """单轮处理预算 —— 与 Java 侧采集模块的预算参数保持同一套语义。"""

    model_config = SettingsConfigDict(
        env_prefix="BUDGET_",
        env_file=_ENV_FILE,
        env_file_encoding=_ENV_FILE_ENCODING,
        extra="ignore",
    )

    max_log_rows: int = 5000
    max_duration_s: float = 10.0


# ══════════════════════════════════════════════════════════
# 聚合入口
# ══════════════════════════════════════════════════════════


class Settings(BaseSettings):
    """全局配置聚合。业务代码只认这一个对象。"""

    model_config = SettingsConfigDict(
        env_file=_ENV_FILE,
        env_file_encoding=_ENV_FILE_ENCODING,
        extra="ignore",
    )

    environment: Literal["dev", "staging", "prod"] = "dev"
    version: str = __version__

    llm: LLMSettings = Field(default_factory=LLMSettings)
    es: ESSettings = Field(default_factory=ESSettings)
    redis: RedisSettings = Field(default_factory=RedisSettings)
    mysql: MySQLSettings = Field(default_factory=MySQLSettings)
    chroma: ChromaSettings = Field(default_factory=ChromaSettings)
    server: ServerSettings = Field(default_factory=ServerSettings)
    log: LogSettings = Field(default_factory=LogSettings)
    budget: BudgetSettings = Field(default_factory=BudgetSettings)

    @model_validator(mode="after")
    def _guard_production(self) -> Settings:
        """生产环境的硬校验。

        这些配置在 dev 下无所谓，在 prod 下是事故：
          · LOG_FORMAT=console → 采集器解析不了，等于没有日志
          · SERVER_DEBUG=true  → 异常堆栈和 SQL 会返回给调用方
        """
        if self.environment != "prod":
            return self

        problems: list[str] = []
        if self.log.format != "json":
            problems.append("LOG_FORMAT 必须是 json（生产日志要给采集器解析）")
        if self.server.debug:
            problems.append("SERVER_DEBUG 必须为 false（否则堆栈会外泄）")
        if problems:
            raise ValueError("生产环境配置不合规：\n  - " + "\n  - ".join(problems))
        return self

    @property
    def is_production(self) -> bool:
        return self.environment == "prod"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """进程内单例。

    用 lru_cache 而不是模块级变量，是为了测试里能 `get_settings.cache_clear()`
    干净地重新加载（否则测配置校验时会被第一次的缓存污染）。
    """
    return Settings()