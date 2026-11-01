"""工具调用循环：模型用原生 tool_calls 决策，agent 通过 MCP 执行。

设计要点：
1. 按路由只挂相关工具（实测工具越多模型越慢：1 个 23s、5 个 83s）；
2. 拿到工具结果后，最后一轮禁止再调工具，只要文字，省一半时间；
3. 模型慢/挂掉时走"确定性降级"：直接按路由执行固定工具，仍然把真实数据说给用户。
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any

import httpx

from ..config import LLMSettings
from .mcp_client import MCPError, MCPClient, get_client

logger = logging.getLogger(__name__)

MAX_ROUNDS = 3

TOOL_SYSTEM_PROMPT = """你是 CampusDash 校园跑腿平台的助手，可以通过工具查询和操作用户数据。

规则：
1. 需要事实（余额、任务、信用分、优惠券、广场任务）时先调用工具，不要凭空猜测；
2. 一次可以调用多个工具，但不要重复调用同一工具同一参数；
3. 拿到结果后用简洁口语化的中文回答，金额带"元"，不要输出 JSON 或工具名；
4. 工具返回 ok=false 时如实说明失败原因，不要编造成功；
5. 与校园跑腿无关的问题，直接说明你只处理跑腿相关事务。"""

# 按路由只暴露相关工具：schema 越少模型越快
ROUTE_TOOLS: dict[str, tuple[str, ...]] = {
    "query": ("get_wallet", "get_credit", "get_ranking", "list_my_vouchers",
              "list_my_errands", "list_square_errands"),
    "grab": ("list_square_errands", "grab_best"),
    "coupon": ("list_claimable_vouchers", "claim_all_vouchers"),
    "task": ("get_wallet",),
}


def parse_grab_count(message: str) -> int:
    """从"接两单/接3单/接五单"里取数量，未给默认 1，上限 5。"""
    text = message or ""
    digits = re.search(r"(\d+)\s*单", text)
    if digits:
        return max(1, min(int(digits.group(1)), 5))
    chinese = {"一": 1, "两": 2, "二": 2, "三": 3, "四": 4, "五": 5}
    match = re.search(r"([一两二三四五])\s*单", text)
    if match:
        return chinese[match.group(1)]
    return 1


def fallback_plan(route: str, message: str) -> list[tuple[str, dict[str, Any]]]:
    """模型不可用时的确定性计划：按路由和关键词直接选工具。"""
    text = message or ""
    if route == "coupon":
        return [("claim_all_vouchers", {})]
    if route == "grab":
        return [("grab_best", {"count": parse_grab_count(text)})]

    plan: list[tuple[str, dict[str, Any]]] = []
    if "余额" in text or "钱包" in text or "多少钱" in text:
        plan.append(("get_wallet", {}))
    if "信用" in text:
        plan.append(("get_credit", {}))
    if "排名" in text or "排行" in text:
        plan.append(("get_ranking", {}))
    if "券" in text:
        plan.append(("list_my_vouchers", {}))
    if "任务" in text or "发的单" in text or "我发" in text:
        plan.append(("list_my_errands", {"limit": 10}))
    if "广场" in text or "可接" in text:
        plan.append(("list_square_errands", {"limit": 20}))
    return plan or [("get_wallet", {})]


class ToolLoop:
    def __init__(self, settings: LLMSettings | None = None, client: MCPClient | None = None) -> None:
        self.settings = settings or LLMSettings.from_env()
        self.client = client

    # ------------------------------------------------------------ 对外入口

    def run(
        self,
        message: str,
        token: str,
        history: list[dict] | None = None,
        route: str = "query",
    ) -> dict[str, Any]:
        if not token:
            return {"ok": False, "reply": "需要登录后才能查询这些数据。", "calls": []}

        client = self.client or get_client()
        try:
            all_tools = client.list_tools()
        except Exception as exc:  # noqa: BLE001
            logger.warning("MCP 工具列表不可用: %s", exc)
            all_tools = []

        tools = self._filter(all_tools, route)
        if not tools:
            # 模型/工具任一不可用，直接走确定性计划
            return self._degraded(client, message, token, route, reason="工具列表不可用")

        messages: list[dict[str, Any]] = [{"role": "system", "content": TOOL_SYSTEM_PROMPT}]
        for item in (history or [])[-6:]:
            content = str(item.get("content") or "").strip()
            if content:
                role = "assistant" if item.get("role") == "assistant" else "user"
                messages.append({"role": role, "content": content[:200]})
        messages.append({"role": "user", "content": message})

        calls: list[dict[str, Any]] = []
        executed: set[str] = set()

        for _ in range(MAX_ROUNDS):
            try:
                data = self._chat(messages, tools, force_answer=bool(calls))
            except Exception as exc:  # noqa: BLE001
                logger.warning("模型调用失败: %s", exc)
                if calls:
                    return {"ok": True, "reply": self._summarize(calls), "calls": calls}
                return self._degraded(client, message, token, route, reason=str(exc))

            reply_message = ((data.get("choices") or [{}])[0].get("message") or {})
            tool_calls = reply_message.get("tool_calls") or []

            if not tool_calls:
                content = (reply_message.get("content") or "").strip()
                if content:
                    return {"ok": True, "reply": content, "calls": calls}
                if calls:
                    return {"ok": True, "reply": self._summarize(calls), "calls": calls}
                return self._degraded(client, message, token, route, reason="模型没有返回内容")

            messages.append({
                "role": "assistant",
                "content": reply_message.get("content") or "",
                "tool_calls": tool_calls,
            })
            for call in tool_calls:
                name = ((call.get("function") or {}).get("name") or "").strip()
                raw_args = (call.get("function") or {}).get("arguments") or "{}"
                try:
                    args = json.loads(raw_args) if isinstance(raw_args, str) else dict(raw_args)
                except ValueError:
                    args = {}

                key = f"{name}:{json.dumps(args, sort_keys=True, ensure_ascii=False)}"
                if key in executed:
                    result = {"ok": False, "error": "本轮已执行过相同调用，已跳过"}
                else:
                    executed.add(key)
                    result = self._exec(client, name, args, token)
                calls.append({"name": name, "arguments": args, "result": result})
                messages.append({
                    "role": "tool",
                    "tool_call_id": call.get("id"),
                    "content": json.dumps(result, ensure_ascii=False),
                })

        return {"ok": True, "reply": self._summarize(calls), "calls": calls}

    # ------------------------------------------------------------ 内部

    def _degraded(self, client: MCPClient, message: str, token: str, route: str, reason: str) -> dict:
        """模型不可用：按路由直接执行固定工具，把真实数据说给用户。"""
        logger.warning("工具链路降级(%s): %s", route, reason)
        plan = fallback_plan(route, message)
        calls = [{"name": name, "arguments": args, "result": self._exec(client, name, args, token)}
                 for name, args in plan]
        return {"ok": True, "reply": self._summarize(calls), "calls": calls, "degraded": True}

    @staticmethod
    def _filter(tools: list[dict], route: str) -> list[dict]:
        allowed = ROUTE_TOOLS.get(route, ())
        if not allowed:
            return tools
        picked = [tool for tool in tools if tool["function"]["name"] in allowed]
        return picked or tools

    def _exec(self, client: MCPClient, name: str, args: dict, token: str) -> dict:
        try:
            return client.call_tool(name, args, token)
        except MCPError as exc:
            return {"ok": False, "error": str(exc)}

    def _chat(self, messages: list[dict], tools: list[dict], force_answer: bool = False) -> dict:
        payload: dict[str, Any] = {
            "model": self.settings.model,
            "temperature": self.settings.temperature,
            "max_tokens": max(self.settings.max_tokens, 600),
            "messages": messages,
        }
        if not force_answer:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"

        timeout = max(self.settings.timeout, 60.0)
        with httpx.Client(timeout=timeout) as client:
            resp = client.post(
                f"{self.settings.base_url}/chat/completions",
                headers={"Authorization": f"Bearer {self.settings.api_key}"},
                json=payload,
            )
            if resp.status_code == 400 and "tools" in payload:
                payload.pop("tools", None)
                payload.pop("tool_choice", None)
                resp = client.post(
                    f"{self.settings.base_url}/chat/completions",
                    headers={"Authorization": f"Bearer {self.settings.api_key}"},
                    json=payload,
                )
            if resp.status_code >= 400:
                raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:150]}")
        return resp.json()

    @staticmethod
    def _summarize(calls: list[dict]) -> str:
        """把工具返回的事实拼成人话，模型不可用时的兜底。"""
        lines: list[str] = []
        for call in calls:
            result = call.get("result") or {}
            name = call.get("name")
            if result.get("ok") is False:
                lines.append(f"· {name} 失败：{result.get('error')}")
                continue
            if name == "get_wallet":
                lines.append(f"· 钱包可用 {result.get('available_yuan')} 元，冻结 {result.get('frozen_yuan')} 元")
            elif name == "get_credit":
                lines.append(f"· 信用分 {result.get('score')}")
            elif name == "get_ranking":
                if result.get("my_rank"):
                    lines.append(f"· 信用排名第 {result.get('my_rank')} 位（榜单共 {result.get('ranked_players')} 人）")
                else:
                    lines.append(
                        f"· 信用分 {result.get('my_score')}，暂未进入前 {result.get('ranked_players')} 名"
                    )
            elif name == "list_my_vouchers":
                lines.append(f"· 可用优惠券 {result.get('count')} 张")
            elif name == "list_my_errands":
                lines.append(f"· 我发布的任务 {result.get('count')} 单，合计 {result.get('total_reward_yuan')} 元")
            elif name == "claim_all_vouchers":
                text = f"· 本次领到 {result.get('claimed_count')} 张券"
                if result.get("skipped_count"):
                    text += f"，{result['skipped_count']} 张之前已经领过"
                if result.get("failed"):
                    text += f"，{len(result['failed'])} 张失败"
                lines.append(text)
            elif name == "list_claimable_vouchers":
                lines.append(f"· 当前可领 {result.get('count')} 种券")
            elif name == "grab_best":
                if result.get("grabbed_count"):
                    lines.append(f"· 接了 {result.get('grabbed_count')} 单")
                else:
                    lines.append("· 广场上没有可接的单（自己的单不能接）")
                if result.get("skipped_own"):
                    lines.append(f"· 跳过了 {result['skipped_own']} 单自己发布的")
                if result.get("failed"):
                    lines.append(f"· {len(result['failed'])} 单没抢到")
            elif name == "list_square_errands":
                lines.append(f"· 广场可接 {result.get('count')} 单（不含自己的）")
        if not lines:
            return "我没有拿到可用结果，稍后再试试。"
        return "我查到这些：\n" + "\n".join(lines)
