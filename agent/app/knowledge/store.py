"""知识库的表结构与读写（MySQL）。

两张表：
  kb_document  一篇文档一行，存 hash，用来判断要不要重建
  kb_chunk     一块一行，存正文 + 向量；正文上有 ngram 全文索引

向量用 BLOB 存 float32：512 维 = 2048 字节，比 JSON 省一半，读出来
np.frombuffer 就能直接算余弦。
"""

from __future__ import annotations

import os
from datetime import datetime

SCHEMA = [
    """
    CREATE TABLE IF NOT EXISTS kb_document (
      doc_id       VARCHAR(64)  NOT NULL,
      title        VARCHAR(200) NOT NULL,
      category     VARCHAR(32)  NOT NULL DEFAULT 'general',
      campus       VARCHAR(16)  NOT NULL DEFAULT 'all',
      source_path  VARCHAR(300) NULL,
      content_hash CHAR(64)     NOT NULL,
      updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      PRIMARY KEY (doc_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """,
    """
    CREATE TABLE IF NOT EXISTS kb_chunk (
      chunk_id   VARCHAR(96)  NOT NULL,
      doc_id     VARCHAR(64)  NOT NULL,
      title      VARCHAR(200) NOT NULL,
      section    VARCHAR(120) NULL,
      category   VARCHAR(32)  NOT NULL DEFAULT 'general',
      campus     VARCHAR(16)  NOT NULL DEFAULT 'all',
      text       TEXT         NOT NULL,
      embedding  BLOB         NULL,
      dim        INT          NOT NULL DEFAULT 0,
      updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
      PRIMARY KEY (chunk_id),
      KEY idx_doc (doc_id),
      KEY idx_category (category),
      FULLTEXT KEY ft_text (text) WITH PARSER ngram
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """,
]


class KnowledgeStore:
    """连接复用 agent 的 MySQL 配置（AGENT_MYSQL_DSN）。"""

    def __init__(self, dsn: str | None = None) -> None:
        import pymysql

        self.dsn = dsn or os.getenv("AGENT_MYSQL_DSN", "")
        if not self.dsn:
            raise RuntimeError("缺少 AGENT_MYSQL_DSN")
        self._pymysql = pymysql
        self._cfg = self._parse(self.dsn)
        self._conn = None
        self._ensure_schema()

    @staticmethod
    def _parse(dsn: str) -> dict:
        """解析 host:port/db?user=..&password=.. 形式（与 store_mysql 保持一致）。

        注意这里**没有** mysql:// 前缀，不能直接交给 urlparse——
        它会把 "mysql" 当成协议、把 "3306/campus_dash" 当成路径，host 变空。
        """
        from urllib.parse import parse_qs, unquote

        head, _, query = dsn.partition("?")
        addr, _, database = head.partition("/")
        host, _, port = addr.partition(":")
        params = {k: v[0] for k, v in parse_qs(query).items()}
        return {
            "host": host or "127.0.0.1",
            "port": int(port or 3306),
            "user": params.get("user", "root"),
            "password": unquote(params.get("password", "")),
            "database": database or "campus_dash",
        }

    def _cursor(self):
        if self._conn is None:
            self._conn = self._pymysql.connect(**self._cfg, autocommit=True, charset="utf8mb4")
        try:
            self._conn.ping(reconnect=True)
        except Exception:  # noqa: BLE001
            self._conn = self._pymysql.connect(**self._cfg, autocommit=True, charset="utf8mb4")
        return self._conn.cursor()

    def _run(self, sql: str, params: tuple = (), *, fetch: str = "none"):
        with self._cursor() as cur:
            cur.execute(sql, params)
            if fetch == "one":
                return cur.fetchone()
            if fetch == "all":
                return cur.fetchall()
            return cur.rowcount

    def _ensure_schema(self) -> None:
        for statement in SCHEMA:
            self._run(statement)

    # ---------------------------------------------------------- 文档

    def documents(self) -> dict[str, str]:
        """doc_id -> content_hash，用来判断哪些文件变了。"""
        rows = self._run("SELECT doc_id, content_hash FROM kb_document", fetch="all") or []
        return {row[0]: row[1] for row in rows}

    def upsert_document(self, doc) -> None:
        self._run(
            "INSERT INTO kb_document (doc_id, title, category, campus, source_path, content_hash, updated_at)"
            " VALUES (%s, %s, %s, %s, %s, %s, %s)"
            " ON DUPLICATE KEY UPDATE title=VALUES(title), category=VALUES(category),"
            " campus=VALUES(campus), source_path=VALUES(source_path),"
            " content_hash=VALUES(content_hash), updated_at=VALUES(updated_at)",
            (doc.doc_id, doc.title, doc.category, doc.campus, doc.source_path,
             doc.content_hash, datetime.now()),
        )

    def delete_document(self, doc_id: str) -> None:
        self._run("DELETE FROM kb_chunk WHERE doc_id = %s", (doc_id,))
        self._run("DELETE FROM kb_document WHERE doc_id = %s", (doc_id,))

    # ---------------------------------------------------------- 块

    def replace_chunks(self, doc_id: str, chunks: list, vectors: list | None) -> None:
        """整篇替换：先删后插，保证幂等。"""
        self._run("DELETE FROM kb_chunk WHERE doc_id = %s", (doc_id,))
        for index, chunk in enumerate(chunks):
            blob = None
            dim = 0
            if vectors is not None and index < len(vectors):
                import numpy as np

                array = np.asarray(vectors[index], dtype="float32")
                blob = array.tobytes()
                dim = int(array.shape[0])
            self._run(
                "INSERT INTO kb_chunk (chunk_id, doc_id, title, section, category, campus, text,"
                " embedding, dim, updated_at) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                (chunk.chunk_id, chunk.doc_id, chunk.title, chunk.section, chunk.category,
                 chunk.campus, chunk.text, blob, dim, datetime.now()),
            )

    def count(self) -> tuple[int, int]:
        docs = self._run("SELECT COUNT(*) FROM kb_document", fetch="one")
        chunks = self._run("SELECT COUNT(*) FROM kb_chunk", fetch="one")
        return int(docs[0]), int(chunks[0])

    def keyword_search(self, query: str, limit: int = 12, category: str | None = None) -> list[tuple]:
        """全文检索：ngram 解析器把中文切成二字组，天然支持中文。

        用 NATURAL LANGUAGE MODE 而不是 BOOLEAN：前者有相关度打分，排序更稳。
        """
        sql = (
            "SELECT chunk_id, doc_id, title, section, text, MATCH(text) AGAINST (%s) AS score"
            " FROM kb_chunk WHERE MATCH(text) AGAINST (%s)"
        )
        params: list[object] = [query, query]
        if category:
            sql += " AND category = %s"
            params.append(category)
        sql += " ORDER BY score DESC LIMIT %s"
        params.append(limit)
        return list(self._run(sql, tuple(params), fetch="all") or [])

    def all_vectors(self) -> list[tuple]:
        """把块与向量一次读进内存：几万块以内，暴力余弦比维护索引更省事。"""
        rows = self._run(
            "SELECT chunk_id, doc_id, title, section, text, embedding, category FROM kb_chunk"
            " WHERE embedding IS NOT NULL", fetch="all"
        ) or []
        return list(rows)
