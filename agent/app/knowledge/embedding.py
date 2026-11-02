"""文本向量化：BGE 中文模型 + ONNX Runtime。

为什么不用 API：
  DeepSeek 没有 /embeddings 接口（实测 404），而本地 BGE-small 只要 90MB、
  单条 2ms，建一万个块 20 秒就能跑完，没有理由为此多接一个供应商。

模型缺失时返回 None，检索自动降级为纯关键词——RAG 不能因为模型没下载就整个挂掉。
"""

from __future__ import annotations

import os
from pathlib import Path

MODEL_DIR = Path(os.getenv("AGENT_EMBED_DIR", Path(__file__).resolve().parents[2] / "models" / "bge-small-zh-v1.5"))
MODEL_NAME = "bge-small-zh-v1.5"
DIM = 512

# bge 系列对小查询做了非对称训练：查询侧要加指令，文档侧不加
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："


class Embedder:
    """懒加载的 ONNX 编码器；拿不到模型时 available=False。"""

    def __init__(self, model_dir: Path | str | None = None) -> None:
        self.model_dir = Path(model_dir) if model_dir else MODEL_DIR
        self._session = None
        self._tokenizer = None
        self._tried = False
        self._np = None

    @property
    def available(self) -> bool:
        return self._ensure() is not None

    def _ensure(self):
        if self._tried:
            return self._session
        self._tried = True

        model_path = self.model_dir / "model.onnx"
        tokenizer_path = self.model_dir / "tokenizer.json"
        if not model_path.exists() or not tokenizer_path.exists():
            return None
        try:
            import numpy as np
            import onnxruntime as ort
            from tokenizers import Tokenizer

            tokenizer = Tokenizer.from_file(str(tokenizer_path))
            tokenizer.enable_truncation(max_length=512)
            tokenizer.enable_padding(pad_id=0, pad_token="[PAD]")

            self._np = np
            self._tokenizer = tokenizer
            self._session = ort.InferenceSession(
                str(model_path), providers=["CPUExecutionProvider"]
            )
        except Exception:  # noqa: BLE001
            self._session = None
        return self._session

    def encode(self, texts: list[str], *, query: bool = False) -> list[list[float]] | None:
        """返回归一化后的向量；模型不可用返回 None。"""
        if not self._ensure():
            return None
        assert self._session is not None and self._tokenizer is not None and self._np is not None
        np = self._np

        prepared = [QUERY_PREFIX + text for text in texts] if query else list(texts)
        encodings = self._tokenizer.encode_batch(prepared)
        ids = np.array([enc.ids for enc in encodings], dtype=np.int64)
        mask = np.array([enc.attention_mask for enc in encodings], dtype=np.int64)

        feeds = {"input_ids": ids, "attention_mask": mask}
        names = {item.name for item in self._session.get_inputs()}
        if "token_type_ids" in names:
            feeds["token_type_ids"] = np.zeros_like(ids)

        hidden = self._session.run(None, feeds)[0]
        vectors = hidden[:, 0, :]  # CLS pooling
        norms = np.linalg.norm(vectors, axis=1, keepdims=True)
        vectors = vectors / np.clip(norms, 1e-8, None)
        return vectors.astype("float32").tolist()


_EMBEDDER: Embedder | None = None


def get_embedder() -> Embedder:
    global _EMBEDDER
    if _EMBEDDER is None:
        _EMBEDDER = Embedder()
    return _EMBEDDER
