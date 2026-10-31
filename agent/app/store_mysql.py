"""MySQL 会话存储：容器重启后会话与历史对话都还在。

为什么选 MySQL 而不是 Redis：
- 会话是"写少读多 + 需要按用户查历史 + 需要人工删除"的数据，正好是关系库的强项；
- 删除操作一条 DELETE 就能完成，不需要再维护 Redis 里的多份 key；
- 会话量级很小（每个用户几条），不需要 Redis 的极致吞吐；
- 主项目已经是 MySQL，不引入新的中间件。

表结构见 campus-dash-backend/docker/migrate-agent-session.sql。
"""

from __future__ import annotations

import json
import os
import uuid
from datetime import datetime, timezone

from .models import TaskDraft

SCHEMA = """
CREATE TABLE IF NOT EXISTS agent_session (
  session_id   VARCHAR(64)  NOT NULL,
  user_id      VARCHAR(36)  NULL,
  draft_json   JSON         NOT NULL,
  turns        INT          NOT NULL DEFAULT 0,
  status       VARCHAR(16)  NULL,
  last_message VARCHAR(500) NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (session_id),
  KEY idx_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
"""

MESSAGE_SCHEMA = """
CREATE TABLE IF NOT EXISTS agent_message (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  session_id VARCHAR(64) NOT NULL,
  role       VARCHAR(16) NOT NULL,
  content    TEXT        NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_session (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
"""


class MySQLSessionStore:
    """把会话读写落到 MySQL。接口与内存版 SessionStore 保持一致。"""

    def __init__(self, dsn: str | None = None) -> None:
        import pymysql  # 延迟导入：未配置时不影响内存版运行

        self.dsn = dsn or os.getenv("AGENT_MYSQL_DSN", "")
        if not self.dsn:
            raise RuntimeError("缺少 AGENT_MYSQL_DSN")
        self._pymysql = pymysql
        self._cfg = self._parse(self.dsn)
        self._conn = self._connect()
        self._ensure_schema()

    def _connect(self):
        """建立连接。

        MySQL 默认 8 小时断开空闲连接，容器网络抖动也会断。
        连上后先探活，断了就换新连接，避免第一条请求必然 500。
        """
        return self._pymysql.connect(**self._cfg, autocommit=True, charset="utf8mb4")

    def _cursor(self):
        """拿游标前先 ping，连接失效就重连。

        这是长连接场景必须做的事：await 期间连接可能已被服务端回收，
        不重连的话报错，而且错误发生后才补救会让用户看到一次失败。
        """
        try:
            self._conn.ping(reconnect=True)
        except Exception:
            self._conn = self._connect()
        return self._conn.cursor()

    def _run(self, sql: str, params: tuple = (), *, fetch: str = "none"):
        """统一的执行入口：自动重连 + 事务管理。"""
        try:
            with self._cursor() as cur:
                cur.execute(sql, params)
                if fetch == "one":
                    return cur.fetchone()
                if fetch == "all":
                    return cur.fetchall()
                return cur.rowcount
        except Exception:
            # 连接级错误重连重试一次
            self._conn = self._connect()
            with self._cursor() as cur:
                cur.execute(sql, params)
                if fetch == "one":
                    return cur.fetchone()
                if fetch == "all":
                    return cur.fetchall()
                return cur.rowcount

    @staticmethod
    def _parse(dsn: str) -> dict[str, object]:
        """解析 host:port/db?user=..&password=.. 形式的连接串。"""
        from urllib.parse import parse_qs, unquote

        head, _, query = dsn.partition("?")
        addr, _, database = head.partition("/")
        host, _, port = addr.partition(":")
        params = {k: v[0] for k, v in parse_qs(query).items()}
        return {
            "host": host,
            "port": int(port or 3306),
            "user": params.get("user", "root"),
            "password": unquote(params.get("password", "")),
            "database": database,
        }

    def _ensure_schema(self) -> None:
        self._run(SCHEMA)
        self._run(MESSAGE_SCHEMA)

    # ---------------------------------------------------------- 会话读写

    def create(self, user_id: str | None = None) -> "Session":
        from .service import Session

        session = Session(id=str(uuid.uuid4()))
        self._run(
            "INSERT INTO agent_session (session_id, user_id, draft_json) VALUES (%s, %s, %s)",
            (session.id, user_id, json.dumps(session.draft.model_dump(mode="json"), ensure_ascii=False)),
        )
        return session

    def get(self, session_id: str) -> "Session | None":
        from .service import Session

        row = self._run(
            "SELECT draft_json, turns FROM agent_session WHERE session_id = %s",
            (session_id,),
            fetch="one",
        )
        if not row:
            return None
        draft_data = row[0]
        if isinstance(draft_data, (bytes, bytearray)):
            draft_data = draft_data.decode("utf-8")
        draft = TaskDraft.model_validate(json.loads(draft_data))
        return Session(id=session_id, draft=draft, turns=int(row[1] or 0))

    def save(self, session: "Session", status: str | None = None, last_message: str | None = None) -> None:
        self._run(
            """UPDATE agent_session
                  SET draft_json = %s, turns = %s, status = COALESCE(%s, status),
                      last_message = COALESCE(%s, last_message)
                WHERE session_id = %s""",
            (
                json.dumps(session.draft.model_dump(mode="json"), ensure_ascii=False),
                session.turns,
                status,
                (last_message or "")[:500] or None,
                session.id,
            ),
        )

    def append_message(self, session_id: str, role: str, content: str) -> None:
        self._run(
            "INSERT INTO agent_message (session_id, role, content) VALUES (%s, %s, %s)",
            (session_id, role, content),
        )

    def messages(self, session_id: str) -> list[dict[str, object]]:
        rows = self._run(
            "SELECT role, content, created_at FROM agent_message WHERE session_id = %s ORDER BY id",
            (session_id,),
            fetch="all",
        ) or []
        return [
            {"role": r[0], "content": r[1], "time": r[2].isoformat() if isinstance(r[2], datetime) else str(r[2])}
            for r in rows
        ]

    def list_sessions(self, user_id: str, limit: int = 20) -> list[dict[str, object]]:
        rows = self._run(
            """SELECT session_id, last_message, status, updated_at
                 FROM agent_session
                WHERE user_id = %s
                ORDER BY updated_at DESC
                LIMIT %s""",
            (user_id, limit),
            fetch="all",
        ) or []
        return [
            {
                "session_id": r[0],
                "last_message": r[1] or "",
                "status": r[2] or "",
                "updated_at": r[3].isoformat() if isinstance(r[3], datetime) else str(r[3]),
            }
            for r in rows
        ]

    def delete(self, session_id: str) -> bool:
        """删除会话：消息和会话必须在同一事务里删掉。

        _run 是 autocommit 的，两条 DELETE 分开执行时中途失败会留下孤儿消息
        （agent_message 有行、agent_session 没行），既查不到也删不掉。
        所以这里显式开事务。
        """
        try:
            self._conn.ping(reconnect=True)
        except Exception:
            self._conn = self._connect()

        self._conn.begin()
        try:
            with self._conn.cursor() as cur:
                cur.execute("DELETE FROM agent_message WHERE session_id = %s", (session_id,))
                cur.execute("DELETE FROM agent_session WHERE session_id = %s", (session_id,))
                removed = cur.rowcount
            self._conn.commit()
        except Exception:
            self._conn.rollback()
            raise
        return bool(removed and removed > 0)

    def message_count(self, session_id: str) -> int:
        """统计某会话的消息条数，测试和排查用。"""
        row = self._run(
            "SELECT COUNT(1) FROM agent_message WHERE session_id = %s",
            (session_id,),
            fetch="one",
        )
        return int(row[0]) if row and row[0] is not None else 0

    def purge_empty(self) -> int:
        """清理没说过话的空会话，避免表被建会话的请求撑大。"""
        return self._run(
            "DELETE FROM agent_session WHERE turns = 0 AND created_at < (NOW() - INTERVAL 1 DAY)"
        ) or 0
