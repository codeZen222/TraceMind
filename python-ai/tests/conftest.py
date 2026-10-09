"""全局测试夹具。

★ 一条重要纪律：**单元测试和契约测试不许碰网络**。
  所以这里用了 autouse 的 fixture 把外部依赖全部替换成桩 ——
  任何一个测试意外发起真实请求，都会被 mock 拦下来。
"""

from __future__ import annotations

import os
from collections.abc import Iterator

import pytest

# ★ 必须在 import 业务代码之前设好环境变量：
#   core.config 的 Settings 在模块导入时就会读一次配置
os.environ.setdefault("LLM_API_KEY", "sk-test-key-not-real-000000")
os.environ.setdefault("MYSQL_DSN", "mysql+asyncmy://root:pwd@127.0.0.1:3306/testdb")
os.environ.setdefault("ENVIRONMENT", "dev")
os.environ.setdefault("LOG_FORMAT", "console")
os.environ.setdefault("LOG_LEVEL", "WARNING")   # 测试时别刷屏


@pytest.fixture(autouse=True)
def _reset_settings_cache() -> Iterator[None]:
    """每个测试前后清掉配置缓存。

    否则第一个测试改了环境变量，后面的测试拿到的还是旧配置 ——
    这类"测试之间互相污染"的问题极难查。
    """
    from trace_mind_ai.core.config import get_settings

    get_settings.cache_clear()
    yield
    get_settings.cache_clear()

