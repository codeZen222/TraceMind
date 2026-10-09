# ══════════════════════════════════════════════════════════
# trace-mind-ai —— 开发命令统一入口
#

# ══════════════════════════════════════════════════════════

VENV   := .venv
ifeq ($(OS),Windows_NT)
    PY := $(VENV)/Scripts/python.exe
    PIP := $(VENV)/Scripts/pip.exe
else
    PY := $(VENV)/bin/python
    PIP := $(VENV)/bin/pip
endif

.DEFAULT_GOAL := help
.PHONY: help venv install lock sync update check lint format type test test-all cov run docker clean

help:  ## 显示所有可用命令
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

venv:  ## 创建虚拟环境
	python -m venv $(VENV)

lock:  ## 从 .in 重新生成锁定文件（改过依赖后必须跑）
	$(PIP) install -q pip-tools
	$(PIP) compile requirements/base.in -o requirements/base.txt --generate-hashes
	$(PIP) compile requirements/dev.in  -o requirements/dev.txt  --generate-hashes
	$(PIP) compile requirements/base.in -o requirements/prod.txt --generate-hashes

sync:  ## 按锁定文件精确同步环境（多装的会被删掉）
	$(PIP) install -q pip-tools
	$(PIP) sync requirements/dev.txt
	$(PIP) install -e . --no-deps

install: venv sync  ## 全新环境一键装好

update:  ## 升级依赖到允许范围内最新（升完必须跑 make check）
	$(PIP) compile --upgrade requirements/base.in -o requirements/base.txt --generate-hashes
	$(PIP) compile --upgrade requirements/dev.in  -o requirements/dev.txt  --generate-hashes

lint:  ## 静态检查（不修改文件）
	$(PY) -m ruff check .
	$(PY) -m ruff format --check .

format:  ## 自动修复风格问题
	$(PY) -m ruff check --fix .
	$(PY) -m ruff format .

type:  ## 类型检查
	$(PY) -m mypy --config-file=pyproject.toml

arch:  ## 架构依赖检查（分层是否被破坏）
	$(PY) -m importlinter.cli lint-imports

test:  ## 单元 + 契约测试（不需要外部服务）
	$(PY) -m pytest -m "not integration"

test-all:  ## 全部测试（需要 ES / Redis / MySQL 在跑）
	$(PY) -m pytest

cov:  ## 带覆盖率
	$(PY) -m pytest -m "not integration" --cov=trace_mind_ai --cov-report=term-missing --cov-report=html

check: lint type arch test  ## ★ CI 跑的就是这个 —— 提交前必须绿
	@echo ""
	@echo "  ✓ 全部检查通过"

run:  ## 本地启动（热重载）
	$(PY) -m trace_mind_ai

smoke:  ## 服务启动后跑冒烟测试
	$(PY) scripts/smoke_test.py

docker:  ## 构建镜像
	docker build -t trace-mind-ai:dev .

clean:  ## 清理缓存与临时文件
	rm -rf .pytest_cache .ruff_cache .mypy_cache htmlcov .coverage coverage.xml
	find . -type d -name __pycache__ -not -path "./.venv/*" -exec rm -rf {} + 2>/dev/null || true