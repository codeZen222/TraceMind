"""read_source_code —— 读取项目源码。

★★★ 安全要点：路径穿越防护 ★★★
  这个工具的参数由**模型**决定。如果不做校验，模型（或被注入的 prompt）
  可以传 "../../../etc/passwd" 或 "C:/Windows/System32/config/SAM" 把
  整台机器的文件读出来，而工具返回的内容会进入模型上下文，
  再通过诊断结论回到前端 —— **这就是一条完整的任意文件读取漏洞**。

  防护三件套：
    ① 路径必须 resolve 后仍在项目根目录内（防 ../ 穿越）
    ② 拒绝符号链接（防软链指向目录外）
    ③ 只允许读白名单后缀（防读二进制 / 密钥文件）
"""

from __future__ import annotations

from pathlib import Path

from langchain_core.tools import tool

from trace_mind_ai.core.logging import get_logger
from trace_mind_ai.infra import mysql
from trace_mind_ai.tools.context import current

log = get_logger(__name__)

#: 允许读的后缀。**不放 .env / .pem / .key 这类** ——
#: 源码诊断不需要它们，放进来纯属扩大攻击面
_ALLOWED_SUFFIXES = frozenset({
    ".java", ".py", ".kt", ".scala", ".go", ".rs",
    ".js", ".ts", ".vue", ".xml", ".yml", ".yaml",
    ".properties", ".sql", ".json", ".md", ".txt", ".log",
})

_MAX_FILE_BYTES = 200_000          # 单文件上限
_MAX_OUTPUT_CHARS = 6000           # 返回给模型的上限


@tool
async def read_source_code(relative_path: str, start_line: int = 1, max_lines: int = 120) -> str:
    """读取项目里的源码文件，用于精确定位问题所在的行。

    什么时候用：
      · 已经从堆栈里拿到了类名和行号，要看那一行的代码
      · 想确认某个异常是否因为缺少判空 / 缺少 try-catch

    什么时候不要用：
      · 还不知道问题在哪 —— 先用 search_logs 把范围缩小
      · 想看日志内容 —— 日志用 search_logs，不要把日志当源码读

    Args:
        relative_path: 相对于项目日志根目录的文件路径，比如 src/main/java/.../OrderService.java
        start_line: 从第几行开始读（堆栈里给的行号直接用这里）。默认 1。
        max_lines: 最多读多少行。默认 120，上限 400。
    """
    ctx = current()
    ctx.consume()
    ctx.used_tools.append("read_source_code")

    project = await mysql.get_project(ctx.project_id)
    if not project or not project.get("logPath"):
        return f"项目 {ctx.project_id} 没有配置 logPath，无法定位源码根目录。"

    root = _resolve_root(str(project["logPath"]))
    if root is None:
        return f"项目配置的 logPath（{project['logPath']}）在服务端不存在，无法读取源码。"

    target = _safe_resolve(root, relative_path)
    if target is None:
        # ★ 不告诉模型"真实路径是什么"，避免它借此探测文件系统结构
        log.warning(
            "tool.read_source_code.rejected",
            project_id=ctx.project_id,
            requested=relative_path,
        )
        return (
            "路径不合法（只能读取项目目录内的源码文件）。"
            "请检查路径是否写错，或是否使用了绝对路径。"
        )

    if target.suffix.lower() not in _ALLOWED_SUFFIXES:
        return f"不支持读取 {target.suffix} 类型的文件。可读的文件类型：源码与配置文件。"

    try:
        size = target.stat().st_size
        if size > _MAX_FILE_BYTES:
            return f"文件过大（{size} 字节，上限 {_MAX_FILE_BYTES}），请指定更精确的行范围。"
        content = target.read_text(encoding="utf-8", errors="replace")
    except Exception as exc:
        log.warning("tool.read_source_code.failed", error=type(exc).__name__)
        return f"读取失败：{type(exc).__name__}"

    lines = content.splitlines()
    start = max(1, start_line)
    end = min(len(lines), start + max(1, min(max_lines, 400)) - 1)

    numbered = [f"{no:>5} | {lines[no - 1]}" for no in range(start, end + 1)]
    body = "\n".join(numbered)
    truncated = end < len(lines)

    header = f"{relative_path} 第 {start}~{end} 行（共 {len(lines)} 行）"
    if truncated:
        header += "  ⚠️ 已截断，后面还有内容"
    text = f"{header}\n\n{body}"
    if len(text) > _MAX_OUTPUT_CHARS:
        text = text[:_MAX_OUTPUT_CHARS] + "\n…（输出已截断）"
    return text


def _resolve_root(log_path: str) -> Path | None:
    """由 logPath 推出项目的源码根目录。

    ⚠️ Windows 上的已知坑：
      库里存的 logPath 是 Linux 风格（/tmp/demo/app.log），
      而 Java 在 Windows 上会把它解析成 C:\\tmp\\demo\\app.log。
      Python 侧同样：Path("/tmp/demo/app.log") 在 Windows 上会变成
      当前盘符下的 \\tmp\\demo\\app.log。**两个语言的行为一致，所以不额外处理**，
      但换机器时要注意 logPath 得跟着改。
    """
    if not log_path:
        return None
    path = Path(log_path)
    # logPath 一般指向日志文件，取它所在目录作为根；
    # 如果它指向目录，直接用
    root = path if path.is_dir() else path.parent
    return root if root.exists() else None


def _safe_resolve(root: Path, relative_path: str) -> Path | None:
    """把相对路径解析成绝对路径，并确保它没跑出 root。"""
    if not relative_path or "\x00" in relative_path:
        return None

    try:
        root_resolved = root.resolve(strict=True)
    except (OSError, RuntimeError):
        return None

    try:
        candidate = (root_resolved / relative_path).resolve(strict=False)
    except (OSError, RuntimeError, ValueError):
        return None

    # ① 必须还在 root 里（防 ../ 穿越）
    if not candidate.is_relative_to(root_resolved):
        return None

    # ② 拒绝符号链接：软链可以指向目录外
    if candidate.is_symlink():
        return None

    # ③ 必须是已存在的普通文件
    if not candidate.is_file():
        return None

    return candidate