"""MySQL 会话存储。

表结构：
  agent_session  一行 = 一个会话 = 一份草稿（其中可能只包含一单）
  agent_message  一行 = 一条消息，主键是雪花 id（插入前就能确定、天然有序）

panel_id 把多个会话串成"一次聊天"：一单发布后会话置 CLOSED，
下一次发单在同一个 panel 下新开 session。
"""

from __future__ import annotations

import json
import os
import uuid
from datetime import datetime

from .models import TaskDraft
from .snowflake import next_id

SCHEMA = """
CREATE TABLE IF NOT EXISTS agent_session (
  session_id   VARCHAR(64)  NOT NULL,
  panel_id     VARCHAR(36)  NULL,
  user_id      VARCHAR(36)  NULL,
  draft_json   JSON         NOT NULL,
  turns        INT          NOT NULL DEFAULT 0,
  status       VARCHAR(16)  NULL,
  active_key   VARCHAR(36)  GENERATED ALWAYS AS (IF(status = 'CLOSED', NULL, panel_id)) STORED,
  last_message VARCHAR(500) NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (session_id),
  KEY idx_user_updated (user_id, updated_at),
  KEY idx_session_panel (panel_id, created_at),
  UNIQUE KEY uk_active_panel (active_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
"""

MESSAGE_SCHEMA = """
CREATE TABLE IF NOT EXISTS agent_message (
  message_id BIGINT      NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  role       VARCHAR(16) NOT NULL,
  content    TEXT        NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (message_id),
  KEY idx_session (session_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
"""


def _iso(value: object) -> str:
    return value.isoformat() if isinstance(value, datetime) else str(value)


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

    # ------------------------------------------------------------ 建表/升级

    def _ensure_schema(self) -> None:
        self._run(SCHEMA)
        self._run(MESSAGE_SCHEMA)
        self._migrate()

    def _columns(self, table: str) -> set[str]:
        rows = self._run(
            "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = %s",
            (table,),
            fetch="all",
        ) or []
        return {row[0] for row in rows}

    def _migrate(self) -> None:
        """老库平滑升级：补 panel_id / active_key，把自增 id 改成雪花 message_id。"""
        cols = self._columns("agent_session")
        if "panel_id" not in cols:
            self._run("ALTER TABLE agent_session ADD COLUMN panel_id VARCHAR(36) NULL AFTER session_id")
        if "active_key" not in cols:
            self._run(
                "ALTER TABLE agent_session ADD COLUMN active_key VARCHAR(36) "
                "GENERATED ALWAYS AS (IF(status = 'CLOSED', NULL, panel_id)) STORED"
            )
            self._run("ALTER TABLE agent_session ADD UNIQUE KEY uk_active_panel (active_key)")
        # 老会话没有面板概念：各自独立成一个面板，历史仍然能按条看
        self._run("UPDATE agent_session SET panel_id = UUID() WHERE panel_id IS NULL")

        message_cols = self._columns("agent_message")
        if "id" in message_cols and "message_id" not in message_cols:
            # 只改名 + 去掉自增，老数据原样保留（老 id 都比雪花小，顺序不会乱）
            self._run("ALTER TABLE agent_message CHANGE COLUMN id message_id BIGINT NOT NULL")

    # ------------------------------------------------------------ 会话读写

    def create(self, user_id: str | None = None, panel_id: str | None = None) -> "Session":
        from .service import Session

        session = Session(id=str(uuid.uuid4()), panel_id=panel_id or str(uuid.uuid4()))
        self._run(
            "INSERT INTO agent_session (session_id, panel_id, user_id, draft_json) VALUES (%s, %s, %s, %s)",
            (
                session.id,
                session.panel_id,
                user_id,
                json.dumps(session.draft.model_dump(mode="json"), ensure_ascii=False),
            ),
        )
        return session

    def _row_to_session(self, row) -> "Session":
        from .service import Session

        draft_data = row[1]
        if isinstance(draft_data, (bytes, bytearray)):
            draft_data = draft_data.decode("utf-8")
        return Session(
            id=row[0],
            panel_id=row[2],
            draft=TaskDraft.model_validate(json.loads(draft_data)),
            turns=int(row[3] or 0),
        )

    def get(self, session_id: str) -> "Session | None":
        row = self._run(
            "SELECT session_id, draft_json, panel_id, turns FROM agent_session WHERE session_id = %s",
            (session_id,),
            fetch="one",
        )
        return self._row_to_session(row) if row else None

    def get_active_session(self, panel_id: str) -> "Session | None":
        """面板下还没结束的那个会话；没有说明该开新会话了。"""
        row = self._run(
            "SELECT session_id, draft_json, panel_id, turns FROM agent_session "
            "WHERE panel_id = %s AND (status IS NULL OR status <> 'CLOSED') "
            "ORDER BY created_at DESC LIMIT 1",
            (panel_id,),
            fetch="one",
        )
        return self._row_to_session(row) if row else None

    def ensure_session(self, panel_id: str, user_id: str | None = None) -> "Session":
        """取面板下进行中的会话，没有就新建。

        并发下两个请求可能同时发现"没有进行中的会话"，
        active_key 唯一索引会让第二个插入失败，这里捕获后重查即可。
        """
        session = self.get_active_session(panel_id)
        if session is not None:
            return session
        try:
            return self.create(user_id, panel_id)
        except Exception:
            again = self.get_active_session(panel_id)
            if again is None:
                raise
            return again

    def save(self, session: "Session", status: str | None = None, last_message: str | None = None) -> None:
        self._run(
            "UPDATE agent_session"
            "   SET draft_json = %s, turns = %s, status = COALESCE(%s, status),"
            "       last_message = COALESCE(%s, last_message)"
            " WHERE session_id = %s",
            (
                json.dumps(session.draft.model_dump(mode="json"), ensure_ascii=False),
                session.turns,
                status,
                (last_message or "")[:500] or None,
                session.id,
            ),
        )

    # ------------------------------------------------------------ 消息读写

    def append_message(self, session_id: str, role: str, content: str) -> int:
        """插入一条消息，id 用雪花（插入前就确定，重试也幂等）。"""
        message_id = next_id()
        self._run(
            "INSERT INTO agent_message (message_id, session_id, role, content) VALUES (%s, %s, %s, %s)",
            (message_id, session_id, role, content),
        )
        return message_id

    def messages(self, session_id: str) -> list[dict[str, object]]:
        rows = self._run(
            "SELECT message_id, role, content, created_at FROM agent_message "
            "WHERE session_id = %s ORDER BY message_id",
            (session_id,),
            fetch="all",
        ) or []
        return [
            {
                # 雪花 id 超过 JS 安全整数范围，统一转字符串，避免前端精度丢失
                "message_id": str(row[0]),
                "role": row[1],
                "content": row[2],
                "time": _iso(row[3]),
            }
            for row in rows
        ]

    def panel_messages(
        self, panel_id: str, limit: int = 200, before_id: int | None = None
    ) -> list[dict[str, object]]:
        """整个面板的消息，按雪花 id 排序即真实时间顺序。

        返回里带 session_id：前端要靠它画出"这一单已发布"的分隔。
        """
        sql = (
            "SELECT m.message_id, m.session_id, m.role, m.content, m.created_at "
            "  FROM agent_message m "
            "  JOIN agent_session s ON s.session_id = m.session_id "
            " WHERE s.panel_id = %s"
        )
        params: list[object] = [panel_id]
        if before_id is not None:
            sql += " AND m.message_id < %s"
            params.append(before_id)
        sql += " ORDER BY m.message_id DESC LIMIT %s"
        params.append(limit)
        rows = self._run(sql, tuple(params), fetch="all") or []
        return [
            {
                "message_id": str(row[0]),
                "session_id": row[1],
                "role": row[2],
                "content": row[3],
                "time": _iso(row[4]),
            }
            for row in reversed(rows)
        ]

    # ------------------------------------------------------------ 面板

    def list_panels(self, user_id: str, limit: int = 20) -> list[dict[str, object]]:
        """一次聊天 = 一个 panel；标题和预览取该面板最后一条消息。"""
        rows = self._run(
            """SELECT panel_id, last_content, last_role, updated_at
                 FROM (
                       SELECT s.panel_id AS panel_id,
                              m.content   AS last_content,
                              m.role      AS last_role,
                              MAX(s.updated_at) OVER (PARTITION BY s.panel_id) AS updated_at,
                              ROW_NUMBER() OVER (
                                  PARTITION BY s.panel_id ORDER BY m.message_id DESC
                              ) AS rn
                         FROM agent_session s
                         LEFT JOIN agent_message m ON m.session_id = s.session_id
                        WHERE s.user_id = %s
                      ) t
                WHERE rn = 1
                ORDER BY updated_at DESC
                LIMIT %s""",
            (user_id, limit),
            fetch="all",
        ) or []
        return [
            {
                "panel_id": row[0],
                "last_message": row[1] or "",
                "last_role": row[2] or "",
                "updated_at": _iso(row[3]),
            }
            for row in rows
        ]

    def list_sessions(self, user_id: str, limit: int = 20) -> list[dict[str, object]]:
        rows = self._run(
            "SELECT session_id, panel_id, last_message, status, updated_at"
            "  FROM agent_session"
            " WHERE user_id = %s"
            " ORDER BY updated_at DESC"
            " LIMIT %s",
            (user_id, limit),
            fetch="all",
        ) or []
        return [
            {
                "session_id": row[0],
                "panel_id": row[1],
                "last_message": row[2] or "",
                "status": row[3] or "",
                "updated_at": _iso(row[4]),
            }
            for row in rows
        ]

    # ------------------------------------------------------------ 删除/统计

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

    def delete_panel(self, panel_id: str) -> bool:
        """删除整段对话：面板下所有会话和消息一起删。"""
        try:
            self._conn.ping(reconnect=True)
        except Exception:
            self._conn = self._connect()

        self._conn.begin()
        try:
            with self._conn.cursor() as cur:
                cur.execute(
                    "DELETE m FROM agent_message m "
                    "  JOIN agent_session s ON s.session_id = m.session_id "
                    " WHERE s.panel_id = %s",
                    (panel_id,),
                )
                cur.execute("DELETE FROM agent_session WHERE panel_id = %s", (panel_id,))
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
