"""重建知识库索引。

用法：
  python scripts/build_kb.py           # 增量（只处理变化的文件）
  python scripts/build_kb.py --force   # 全量重建
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# 直接用 "python scripts/build_kb.py" 运行时，sys.path[0] 是 scripts/ 而不是项目根，
# 会报 ModuleNotFoundError: No module named 'app'
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.knowledge.indexer import rebuild  # noqa: E402


def main() -> int:
    stats = rebuild(force="--force" in sys.argv)
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
