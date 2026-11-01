"""MCP server：以 stdio 方式把工具暴露给 agent。

启动：python -m mcp_server.server
agent 进程作为 MCP client 通过 stdio 连接，tools/list 拿 schema、tools/call 执行。

user_token 是每个工具的第一个参数：由 agent 在调用时注入，
不出现在给模型看的 schema 里（见 app/tools/mcp_client.py 的过滤逻辑）。
"""

from __future__ import annotations

import json

from mcp.server.fastmcp import FastMCP

from . import tools

mcp = FastMCP("campus-dash")


# ------------------------------------------------------------------ 查询

@mcp.tool()
def get_wallet(user_token: str) -> str:
    """查询当前用户的钱包余额（可用金额、冻结金额，单位元）。"""
    return json.dumps(tools.get_wallet(user_token), ensure_ascii=False)


@mcp.tool()
def list_my_errands(user_token: str, limit: int = 10) -> str:
    """查询我发布的任务列表：数量、每单标题与悬赏金额。"""
    return json.dumps(tools.list_my_errands(user_token, limit), ensure_ascii=False)


@mcp.tool()
def get_credit(user_token: str) -> str:
    """查询我的信用分。"""
    return json.dumps(tools.get_credit(user_token), ensure_ascii=False)


@mcp.tool()
def get_ranking(user_token: str) -> str:
    """查询我的信用分排名和平台前三名。"""
    return json.dumps(tools.get_ranking(user_token), ensure_ascii=False)


@mcp.tool()
def list_my_vouchers(user_token: str) -> str:
    """查询我持有的、当前可用的优惠券。"""
    return json.dumps(tools.list_my_vouchers(user_token), ensure_ascii=False)


# ------------------------------------------------------------------ 领券

@mcp.tool()
def claim_all_vouchers(user_token: str) -> str:
    """一次性领取当前所有可领取的优惠券。"""
    return json.dumps(tools.claim_all_vouchers(user_token), ensure_ascii=False)


# ------------------------------------------------------------------ 接单

@mcp.tool()
def list_square_errands(user_token: str, limit: int = 20) -> str:
    """列出广场上可接的任务，已按实付金额从大到小排序。"""
    return json.dumps(tools.list_square_errands(user_token, limit), ensure_ascii=False)


@mcp.tool()
def grab_best(user_token: str, count: int = 1) -> str:
    """按实付金额从大到小自动接单，单次最多 5 单，不传数量则接 1 单。"""
    return json.dumps(tools.grab_best(user_token, count), ensure_ascii=False)


# ------------------------------------------------------------------ 发单

@mcp.tool()
def best_voucher(user_token: str, reward_cents: int) -> str:
    """按悬赏金额找出抵扣最多的自有优惠券（发布时使用）。"""
    return json.dumps(tools.best_voucher(user_token, reward_cents), ensure_ascii=False)


@mcp.tool()
def publish_errand(
    user_token: str,
    type: str,
    title: str,
    reward_cents: int,
    location_from: str | None = None,
    location_to: str | None = None,
    expired_at: str | None = None,
    description: str | None = None,
    voucher_id: str | None = None,
) -> str:
    """把任务草稿发布成真实任务。"""
    return json.dumps(
        tools.publish_errand(
            user_token,
            type=type,
            title=title,
            reward_cents=reward_cents,
            location_from=location_from,
            location_to=location_to,
            expired_at=expired_at,
            description=description,
            voucher_id=voucher_id,
        ),
        ensure_ascii=False,
    )


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
