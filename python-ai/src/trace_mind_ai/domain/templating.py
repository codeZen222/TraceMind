"""Drain3 模板提取。

Drain3 是**有状态**的：它一边处理日志一边增量聚类，模板 ID 会随着训练变化。
所以不能每次请求 new 一个 —— 那样永远学不到东西，每条日志都是「新模板」。

★ 一个契约上的坑（值得单独立项去改）：
  Java 侧的 PythonClient.parse() 只发了 {"logs": [...]}，**没有 projectId**。
  但不同项目的日志格式完全不同（订单服务 vs 网关服务），
  共用一套 Drain3 会让模板相互污染 —— 网关的日志可能被匹配到订单的模板上。

  本实现的选择：
    · 请求里带了 projectId → 按项目隔离（推荐）
    · 没带 → 退回全局池，并在日志里 warn 一次
  建议 Java 侧补上这一行（改动很小）：
      Map.of("logs", logs, "projectId", projectId)---------完成
"""

from __future__ import annotations

import asyncio
import threading
from collections import OrderedDict
from pathlib import Path

from drain3 import TemplateMiner
from drain3.file_persistence import FilePersistence
from drain3.template_miner_config import TemplateMinerConfig

from trace_mind_ai.core.config import get_settings
from trace_mind_ai.core.logging import get_logger

log = get_logger(__name__)

#: 最多同时缓存多少个项目的模板模型。超了按 LRU 淘汰（模型可从磁盘重新加载）。
_MAX_POOL_SIZE = 32

_GLOBAL_KEY = "__global__"


def _build_config() -> TemplateMinerConfig:
    """Drain3 参数。

    这几个参数直接决定模板质量，值得解释：

      drain_sim_th = 0.4
        相似度阈值。越大越难合并成同一个模板（模板更多、更细）。
        0.4 是 Drain 原论文的推荐起点，日志场景下比较稳。
        调到 0.6+ 会出现「本质相同的日志被拆成几十个模板」。
        调到 0.2 会把不同错误合并成一个模板，规则引擎就失效了。

      drain_depth = 4
        解析树的深度，即"开头多少个 token 参与匹配"。
        日志开头的 token 信息量最大（时间戳剥掉后，一般是级别/组件名）。
        4 层足够覆盖 时间戳 + 级别 + 线程 + 类名。

      drain_max_children = 100
        每个节点的最大分支数。日志的变量部分（数字、ID）如果无限制，
        分支会爆炸。设个上限，超出的归入 <*> 通配。
    """
    config = TemplateMinerConfig()
    config.drain_sim_th = 0.4
    config.drain_depth = 4
    config.drain_max_children = 100
    config.drain_max_clusters = 4000
    config.profiling_enabled = False
    return config


class _MinerEntry:
    __slots__ = ("miner", "lock")

    def __init__(self, miner: TemplateMiner, lock: threading.Lock) -> None:
        self.miner = miner
        self.lock = lock


class TemplateMinerPool:
    """按项目隔离的 Drain3 实例池。

    ★ 为什么需要锁：
      Drain3 的 add_log_message() 不是线程安全的（内部改状态树）。
      我们用 asyncio.to_thread 把它丢到线程池跑，多个请求会并发进入。
      加一把 threading.Lock 保证同一项目的训练是串行的 ——
      这不会成为瓶颈（Drain3 单条日志是微秒级），但能防状态树被写坏。
    """

    def __init__(self) -> None:
        self._pool: OrderedDict[str, _MinerEntry] = OrderedDict()
        self._pool_lock = threading.Lock()
        self._warned_no_project = False

    def _persistence_path(self, key: str) -> Path:
        settings = get_settings()
        base = Path(settings.chroma.persist_dir).parent / "drain3"
        base.mkdir(parents=True, exist_ok=True)
        # 项目 id 里有不合适做文件名的字符时兜底
        safe = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in key)
        return base / f"{safe}.bin"

    # 获取或创建一个 Drain3 实例
    def _get_or_create(self, key: str) -> _MinerEntry:
        with self._pool_lock:
            entry = self._pool.get(key)
            if entry is not None:
                self._pool.move_to_end(key)   # LRU 触摸
                return entry

            # 从磁盘恢复：服务重启后不用重新训练，模板 ID 保持稳定
            persistence = FilePersistence(str(self._persistence_path(key)))
            miner = TemplateMiner(persistence, _build_config())
            entry = _MinerEntry(miner, threading.Lock())
            self._pool[key] = entry
            self._pool.move_to_end(key)

            while len(self._pool) > _MAX_POOL_SIZE:
                evicted, _ = self._pool.popitem(last=False)
                log.info("templating.evict", project_key=evicted)

            log.info("templating.loaded", project_key=key, clusters=len(miner.drain.clusters))
            return entry

    def _extract_sync(self, key: str, lines: list[str]) -> list[tuple[str | None, str | None]]:
        """同步提取。会被丢到线程池里跑。"""
        entry = self._get_or_create(key)
        results: list[tuple[str | None, str | None]] = []

        with entry.lock:
            for line in lines:
                text = (line or "").strip()
                if not text:
                    # ★ 空行也占位。Java 侧按下标取结果，少一条会张冠李戴
                    results.append((None, None))
                    continue
                try:
                    cluster = entry.miner.add_log_message(text)
                    template = cluster.get("template_mined") if cluster else None
                    cluster_id = f"cluster_{cluster['cluster_id']}" if cluster else None
                    results.append((template, cluster_id))
                except Exception as exc:
                    # 单行失败不影响整批 —— 占位返回，保持下标对齐
                    log.warning("templating.line_failed", error=str(exc)[:120])
                    results.append((None, None))

        return results

    async def extract(
        self, lines: list[str], *, project_id: str | None = None
    ) -> list[tuple[str | None, str | None]]:
        """提取模板。返回与输入**等长同序**的列表。

        ★ 为什么用 to_thread：
          Drain3 是纯 CPU 的同步代码。直接在 async 函数里调会**阻塞事件循环** ——
          一次 500 行的提取大约几十毫秒，期间所有其他请求全部卡住。
        """
        if not lines:
            return []

        key = project_id or _GLOBAL_KEY
        if project_id is None and not self._warned_no_project:
            log.warning(
                "templating.no_project_id",
                hint="Java 侧 /v1/parse 没传 projectId，模板池退化为全局共享，"
                     "不同项目的模板会相互污染。建议在 PythonClient.parse() 里补上该字段",
            )
            self._warned_no_project = True

        return await asyncio.to_thread(self._extract_sync, key, lines)

    def flush(self, project_id: str | None = None) -> None:
        """落盘。进程优雅退出时调用，避免最后一批训练结果丢失。"""
        with self._pool_lock:
            keys = [project_id] if project_id else list(self._pool.keys())
            for key in keys:
                entry = self._pool.get(key)
                if entry is None:
                    continue
                try:
                    entry.miner.save_state(reason="shutdown")
                except Exception as exc:
                    log.warning("templating.flush_failed", project_key=key, error=str(exc)[:120])


#: 进程级单例。模板状态必须跨请求共享，所以不能用依赖注入的 request 级对象。
_miner_pool = TemplateMinerPool()


def get_miner_pool() -> TemplateMinerPool:
    return _miner_pool