"""MCP 客户端：后台线程持有一个 stdio 长连接，对外提供同步的 list/call。

为什么要这么绕：MCP 的 Python SDK 是 asyncio 的，而 FastAPI 的 chat 接口是同步的。
直接每次 asyncio.run() 会重新拉起子进程（每次几百毫秒），所以这里用一个常驻线程：
    thread → asyncio loop → stdio_client → ClientSession（一直开着）
调用方用 run_coroutine_threadsafe 投递请求、同步等结果。
"""

from __future__ import annotations

import asyncio
import json
import logging
import threading
from typing import Any

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

logger = logging.getLogger(__name__)

DEFAULT_TIMEOUT = 20.0


class MCPError(RuntimeError):
    """MCP 链路不可用（启动失败、超时、子进程退出）。"""


class MCPClient:
    def __init__(
        self,
        command: str = "python",
        args: list[str] | None = None,
        cwd: str | None = None,
        timeout: float = DEFAULT_TIMEOUT,
    ) -> None:
        self.command = command
        self.args = args or ["-m", "mcp_server.server"]
        self.cwd = cwd
        self.timeout = timeout
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        self._session: ClientSession | None = None
        self._ready = threading.Event()
        self._failed: str | None = None
        self._lock = threading.Lock()

    # ------------------------------------------------------------ 生命周期

    def start(self) -> None:
        """启动后台线程并等待会话就绪（幂等）。"""
        with self._lock:
            if self._thread and self._thread.is_alive() and self._session:
                return
            self._ready.clear()
            self._failed = None
            self._thread = threading.Thread(target=self._run, name="mcp-client", daemon=True)
            self._thread.start()
        if not self._ready.wait(self.timeout):
            raise MCPError(self._failed or "MCP 会话启动超时")

    def _run(self) -> None:
        loop = asyncio.new_event_loop()
        self._loop = loop
        asyncio.set_event_loop(loop)
        try:
            loop.run_until_complete(self._serve())
        except Exception as exc:  # noqa: BLE001
            self._failed = f"{type(exc).__name__}: {exc}"
            logger.warning("MCP 线程退出: %s", self._failed)
            self._ready.set()
        finally:
            loop.close()

    async def _serve(self) -> None:
        params = StdioServerParameters(command=self.command, args=self.args, cwd=self.cwd)
        async with stdio_client(params) as (read, write):
            async with ClientSession(read, write) as session:
                await session.initialize()
                self._session = session
                self._ready.set()
                # 常驻，直到进程退出
                await asyncio.Event().wait()

    # ------------------------------------------------------------ 对外接口

    def list_tools(self) -> list[dict[str, Any]]:
        """返回工具的 OpenAI function schema（已剔除 user_token 参数）。"""
        self.start()
        result = self._call(self._session.list_tools())
        return [self._to_schema(tool) for tool in result.tools]

    @staticmethod
    def build_payload(arguments: dict[str, Any] | None, token: str) -> dict[str, Any]:
        """构造工具入参：**真实 token 永远覆盖**模型给的任何同名参数。

        这是身份边界的最后一道闸：即使模型被诱导输出
        {"user_token": "别人的token"}，也会在这里被替换掉。
        """
        payload = dict(arguments or {})
        payload["user_token"] = token
        return payload

    def call_tool(self, name: str, arguments: dict[str, Any], token: str) -> dict[str, Any]:
        """执行工具，并把 user_token 注入进去（模型看不到这个参数）。"""
        self.start()
        payload = self.build_payload(arguments, token)
        result = self._call(self._session.call_tool(name, payload))
        return self._parse_result(result)

    # ------------------------------------------------------------ 内部

    def _call(self, coro):
        if not self._loop or not self._session:
            raise MCPError("MCP 会话未就绪")
        future = asyncio.run_coroutine_threadsafe(coro, self._loop)
        try:
            return future.result(self.timeout)
        except Exception as exc:  # noqa: BLE001
            raise MCPError(f"{type(exc).__name__}: {exc}") from exc

    @staticmethod
    def _to_schema(tool) -> dict[str, Any]:
        schema = tool.inputSchema or {"type": "object", "properties": {}}
        properties = dict(schema.get("properties") or {})
        # user_token 由服务端注入，不能让模型看到/填写
        properties.pop("user_token", None)
        required = [name for name in (schema.get("required") or []) if name != "user_token"]
        return {
            "type": "function",
            "function": {
                "name": tool.name,
                "description": tool.description or "",
                "parameters": {
                    "type": "object",
                    "properties": properties,
                    "required": required,
                },
            },
        }

    @staticmethod
    def _parse_result(result) -> dict[str, Any]:
        texts = []
        for item in getattr(result, "content", []) or []:
            text = getattr(item, "text", None)
            if text:
                texts.append(text)
        raw = "\n".join(texts).strip()
        if not raw:
            return {"ok": False, "error": "工具没有返回内容"}
        try:
            return json.loads(raw)
        except ValueError:
            return {"ok": True, "text": raw}


_client: MCPClient | None = None
_client_lock = threading.Lock()


def get_client() -> MCPClient:
    """进程级单例，避免每次请求都拉起子进程。"""
    global _client
    with _client_lock:
        if _client is None:
            import os
            import sys

        # 子进程用当前解释器，工作目录指向 agent 根目录（能 import mcp_server）
            agent_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
            _client = MCPClient(command=sys.executable, cwd=agent_root)
        return _client
