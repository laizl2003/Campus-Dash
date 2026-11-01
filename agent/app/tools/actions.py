"""把 MCP 工具包成便于 agent 直接调用的函数。

只给"发单链路"用：图里不方便走通用 tool_loop 的两处——
READY 后直接发布、以及算最优优惠券。
"""

from __future__ import annotations

import logging
from typing import Any

from ..models import TaskDraft
from .mcp_client import MCPError, get_client

logger = logging.getLogger(__name__)


def _call(name: str, args: dict[str, Any], token: str) -> dict[str, Any]:
    try:
        return get_client().call_tool(name, args, token)
    except MCPError as exc:
        logger.warning("工具 %s 调用失败: %s", name, exc)
        return {"ok": False, "error": str(exc)}


def best_voucher(token: str | None, reward_cents: int | None) -> dict[str, Any] | None:
    """返回当前悬赏下最优的一张券；没有或查不到时返回 None。"""
    if not token or not reward_cents:
        return None
    result = _call("best_voucher", {"reward_cents": int(reward_cents)}, token)
    if not result.get("ok") or not result.get("has_voucher"):
        return None
    return {
        "voucher_id": result.get("voucher_id"),
        "description": result.get("description"),
        "discount_yuan": result.get("discount_yuan"),
        "payable_yuan": result.get("payable_yuan"),
    }


def publish_draft(token: str | None, draft: TaskDraft) -> dict[str, Any]:
    """把当前草稿发布成真实任务。"""
    if not token:
        return {"ok": False, "error": "缺少登录态，无法发布"}
    if not draft.type or not draft.title or not draft.reward_cents:
        return {"ok": False, "error": "草稿还缺类型/标题/悬赏，无法发布"}

    voucher = best_voucher(token, draft.reward_cents)
    args = {
        "type": draft.type,
        "title": draft.title,
        "reward_cents": int(draft.reward_cents),
        "location_from": draft.location_from,
        "location_to": draft.location_to,
        "expired_at": draft.expired_at.isoformat() if draft.expired_at else None,
        "description": draft.description,
    }
    if voucher:
        args["voucher_id"] = voucher["voucher_id"]
    result = _call("publish_errand", args, token)
    if result.get("ok"):
        result["voucher"] = voucher
    return result
