"""下载向量模型（bge-small-zh-v1.5 的 ONNX 版）。

国内直连 huggingface.co 基本不通，默认走 hf-mirror.com。
下载失败不抛异常（返回 1），构建脚本用 || true 兜住：
没有模型时检索自动降级为纯关键词，功能可用只是召回差一点。

用法：
  python scripts/fetch_embedding_model.py            # 缺什么下什么
  python scripts/fetch_embedding_model.py --force    # 强制重下
"""

from __future__ import annotations

import sys
import urllib.request
from pathlib import Path

TARGET = Path(__file__).resolve().parent.parent / "models" / "bge-small-zh-v1.5"

# 每个文件单独列来源：onnx 在 Xenova 的转换仓库里，
# 而 tokenizer.json 用 BAAI 原始仓库更稳（Xenova 那边偶尔缺文件）
SOURCES: dict[str, list[str]] = {
    "model.onnx": [
        "https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model.onnx",
        "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model.onnx",
    ],
    "tokenizer.json": [
        "https://hf-mirror.com/BAAI/bge-small-zh-v1.5/resolve/main/tokenizer.json",
        "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/tokenizer.json",
        "https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/tokenizer.json",
    ],
}


def _download(url: str, dest: Path) -> bool:
    # hf-mirror 对没有 UA 的请求返回 403（模型文件恰好放行，分词器不放行），
    # 必须带一个正常的 User-Agent，否则表现为"模型下好了、缺个分词器"。
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read()
    except Exception as error:  # noqa: BLE001
        print(f"  x {url} -> {error}")
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    print(f"  ok {dest.name} ({len(data) / 1024 / 1024:.1f} MB)")
    return True


def main() -> int:
    force = "--force" in sys.argv
    missing = [name for name in SOURCES if force or not (TARGET / name).exists()]
    if not missing:
        print(f"模型已存在：{TARGET}")
        return 0

    print(f"下载 {len(missing)} 个文件到 {TARGET}")
    failed = []
    for name in missing:
        for url in SOURCES[name]:
            if _download(url, TARGET / name):
                break
        else:
            failed.append(name)

    if failed:
        # 缺一个文件整个向量化就用不了，明确报出来，别让构建"看起来成功"
        print(f"下载失败：{failed}（检索将退回纯关键词）")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
