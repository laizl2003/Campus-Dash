"""CampusDash 工具实现。

每个函数都是一次"用户视角的操作"，第一个参数固定是 user_token
（由 agent 注入，不会出现在模型看到的 schema 里）。
工具只负责调后端接口，不关心自己被 MCP 还是被单测调用。
"""

from __future__ import annotations

import os
from typing import Any

import httpx

DEFAULT_BACKEND = os.getenv("DASH_BACKEND_URL", "http://app:8080")


def _cents(value: Any) -> int:
    """后端金额是字符串（"99500"），统一转成整数分。"""
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return 0


def _yuan(value: Any) -> float:
    return round(_cents(value) / 100, 2)


def _unwrap(resp: httpx.Response) -> dict:
    try:
        payload = resp.json()
    except ValueError:
        return {"ok": False, "error": f"HTTP {resp.status_code}"}
    if resp.status_code >= 400 or payload.get("code") not in (None, "OK"):
        return {"ok": False, "error": payload.get("message") or f"HTTP {resp.status_code}"}
    return {"ok": True, "data": payload.get("data")}


def _get(path: str, token: str, params: dict | None = None) -> dict:
    with httpx.Client(timeout=15.0) as client:
        resp = client.get(
            f"{DEFAULT_BACKEND}{path}",
            headers={"Authorization": f"Bearer {token}"},
            params=params,
        )
    return _unwrap(resp)


def _post(path: str, token: str, body: dict | None = None) -> dict:
    with httpx.Client(timeout=15.0) as client:
        resp = client.post(
            f"{DEFAULT_BACKEND}{path}",
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            json=body or {},
        )
    return _unwrap(resp)


# ------------------------------------------------------------------ 查询

def get_me(user_token: str) -> dict:
    """查询当前登录用户的基本信息（id、用户名）。"""
    result = _get("/api/auth/me", user_token)
    if not result["ok"]:
        return result
    data = result["data"] or {}
    return {"ok": True, "user_id": str(data.get("userId")), "username": data.get("username")}


def get_wallet(user_token: str) -> dict:
    """查询钱包余额：可用、冻结。"""
    result = _get("/api/wallet", user_token)
    if not result["ok"]:
        return result
    data = result["data"] or {}
    return {
        "ok": True,
        "available_yuan": _yuan(data.get("availableCents")),
        "frozen_yuan": _yuan(data.get("frozenCents")),
    }


def list_my_errands(user_token: str, limit: int = 10) -> dict:
    """查询我发布的任务：数量、逐单标题与金额。"""
    result = _get("/api/errands/mine", user_token, params={"page": 0, "size": max(1, min(limit, 20))})
    if not result["ok"]:
        return result
    data = result["data"]
    items = data if isinstance(data, list) else (data or {}).get("items") or []
    rows = [
        {
            "title": item.get("title"),
            "status": item.get("status"),
            "reward_yuan": _yuan(item.get("rewardCents")),
        }
        for item in items
    ]
    return {
        "ok": True,
        "count": len(rows),
        "total_reward_yuan": round(sum(row["reward_yuan"] for row in rows), 2),
        "items": rows,
    }


def get_credit(user_token: str) -> dict:
    """查询我的信用分。"""
    result = _get("/api/credit/self", user_token)
    if not result["ok"]:
        return result
    data = result["data"] or {}
    return {"ok": True, "score": data.get("score"), "window_days": data.get("windowDays")}


def get_ranking(user_token: str) -> dict:
    """查询信用排名：我的名次与前三名。"""
    me = get_me(user_token)
    if not me.get("ok"):
        return me
    result = _get("/api/credit/ranking", user_token, params={"limit": 1000})
    if not result["ok"]:
        return result
    rows = result["data"] or []
    rank = None
    for index, row in enumerate(rows, start=1):
        if str(row.get("userId")) == me["user_id"]:
            rank = index
            break
    credit = get_credit(user_token)
    return {
        "ok": True,
        "my_rank": rank,
        "in_top": rank is not None,
        "ranked_players": len(rows),
        "my_score": credit.get("score") if credit.get("ok") else None,
        "top3": [
            {"username": row.get("username"), "score": row.get("score")}
            for row in rows[:3]
        ],
    }


def list_my_vouchers(user_token: str) -> dict:
    """查询我持有的优惠券。"""
    result = _get("/api/vouchers/mine", user_token)
    if not result["ok"]:
        return result
    rows = result["data"] or []
    items = [
        {
            "voucher_id": row.get("voucherId"),
            "description": row.get("description"),
            "discount_yuan": _yuan(row.get("discountCents")),
            "threshold_yuan": _yuan(row.get("thresholdCents")),
            "status": row.get("status"),
            "expire_at": row.get("expireTime"),
        }
        for row in rows
    ]
    available = [item for item in items if item["status"] == "available"]
    return {"ok": True, "count": len(available), "items": available}


# ------------------------------------------------------------------ 领券

def list_claimable_vouchers(user_token: str) -> dict:
    """列出当前还有库存、可以领取的券种。"""
    result = _get("/api/vouchers", user_token)
    if not result["ok"]:
        return result
    data = result["data"] or {}
    items = data.get("items") if isinstance(data, dict) else data
    rows = [
        {
            "type": item.get("type"),
            "description": item.get("description"),
            "discount_yuan": _yuan(item.get("discountCents")),
            "threshold_yuan": _yuan(item.get("thresholdCents")),
            "stock": item.get("stock"),
        }
        for item in (items or [])
        if _cents(item.get("stock")) > 0
    ]
    return {"ok": True, "count": len(rows), "items": rows}


def claim_voucher(user_token: str, voucher_type: Any) -> dict:
    """领取指定类型的优惠券。"""
    return _post(f"/api/vouchers/{voucher_type}/claim", user_token)


def claim_all_vouchers(user_token: str) -> dict:
    """把当前能领的券一次领完，返回成功与失败明细。"""
    listed = list_claimable_vouchers(user_token)
    if not listed.get("ok"):
        return listed
    claimed, skipped, failed = [], [], []
    for item in listed["items"]:
        result = claim_voucher(user_token, item["type"])
        if result.get("ok"):
            claimed.append(item["description"])
            continue
        error = str(result.get("error") or "")
        # "已领取"不是失败：用户本来就都有，不该报成错误
        if any(word in error for word in ("已领", "重复", "上限", "already")):
            skipped.append(item["description"])
        else:
            failed.append({"description": item["description"], "error": error})
    return {
        "ok": True,
        "claimed_count": len(claimed),
        "claimed": claimed,
        "skipped_count": len(skipped),
        "skipped": skipped,
        "failed": failed,
    }


# ------------------------------------------------------------------ 接单

def _my_user_id(user_token: str) -> str | None:
    me = get_me(user_token)
    return str(me.get("user_id")) if me.get("ok") and me.get("user_id") else None


def list_square_errands(user_token: str, limit: int = 20) -> dict:
    """列出广场上"别人发的"可接任务，按实付金额从大到小。

    自己的单不能接，这里直接过滤掉，免得模型选中后再被后端拒绝。
    """
    result = _get(
        "/api/errands",
        user_token,
        params={"status": "PUBLISHED", "page": 0, "size": max(1, min(limit, 50))},
    )
    if not result["ok"]:
        return result
    me_id = _my_user_id(user_token)
    data = result["data"]
    items = data if isinstance(data, list) else (data or {}).get("items") or []

    rows, own = [], 0
    for item in items:
        if item.get("status") != "PUBLISHED":
            continue
        publisher = str(item.get("publisherId") or "")
        if me_id and publisher == me_id:
            own += 1
            continue
        rows.append({
            "errand_id": str(item.get("id")),
            "title": item.get("title"),
            "paid_yuan": _yuan(item.get("rewardCents")),
            "net_yuan": _yuan(item.get("netRewardCents")),
        })
    rows.sort(key=lambda row: row["paid_yuan"], reverse=True)
    return {"ok": True, "count": len(rows), "items": rows, "skipped_own": own}


def grab_errand(user_token: str, errand_id: str) -> dict:
    """接一个任务；自己的单直接拒绝。"""
    detail = _get(f"/api/errands/{errand_id}", user_token)
    if detail.get("ok"):
        publisher = str((detail.get("data") or {}).get("publisherId") or "")
        me_id = _my_user_id(user_token)
        if me_id and publisher == me_id:
            return {"ok": False, "error": "这是你自己发布的单，不能接"}
    return _post(f"/api/errands/{errand_id}/grab", user_token)


def grab_best(user_token: str, count: Any = 1) -> dict:
    """按实付金额从大到小批量接单，单轮最多 5 单。"""
    try:
        wanted = int(count)
    except (TypeError, ValueError):
        wanted = 1
    wanted = max(1, min(wanted, 5))

    listed = list_square_errands(user_token)
    if not listed.get("ok"):
        return listed

    grabbed, failed = [], []
    for item in listed["items"]:
        if len(grabbed) >= wanted:
            break
        result = grab_errand(user_token, item["errand_id"])
        if result.get("ok"):
            grabbed.append(item)
        else:
            failed.append({"title": item.get("title"), "error": result.get("error")})

    return {
        "ok": True,
        "requested": wanted,
        "grabbed_count": len(grabbed),
        "grabbed": grabbed,
        "failed": failed,
        "skipped_own": listed.get("skipped_own", 0),
    }


# ------------------------------------------------------------------ 发单

def best_voucher(user_token: str, reward_cents: Any) -> dict:
    """按悬赏金额挑出抵扣最多的那张自有券（发布时要用 voucherId）。"""
    reward = _cents(reward_cents)
    result = _get("/api/vouchers/mine", user_token)
    if not result["ok"]:
        return result

    best = None
    for row in result["data"] or []:
        if row.get("status") != "available":
            continue
        if reward < _cents(row.get("thresholdCents")):
            continue
        if best is None or _cents(row.get("discountCents")) > _cents(best.get("discountCents")):
            best = row

    if best is None:
        return {"ok": True, "has_voucher": False}
    return {
        "ok": True,
        "has_voucher": True,
        "voucher_id": str(best.get("voucherId")),
        "description": best.get("description"),
        "discount_yuan": _yuan(best.get("discountCents")),
        "threshold_yuan": _yuan(best.get("thresholdCents")),
        "payable_yuan": round(max(reward - _cents(best.get("discountCents")), 0) / 100, 2),
    }


def publish_errand(
    user_token: str,
    type: str,
    title: str,
    reward_cents: Any,
    location_from: str | None = None,
    location_to: str | None = None,
    expired_at: str | None = None,
    description: str | None = None,
    voucher_id: str | None = None,
) -> dict:
    """把草稿发布成真实任务（等价于前端「确认发布」）。"""
    body = {
        "campusId": 1,
        "type": type,
        "title": title,
        "rewardCents": _cents(reward_cents),
        "slotTotal": 1,
        "locationFrom": location_from,
        "locationTo": location_to,
        "expiredAt": expired_at,
        "description": description,
    }
    if voucher_id:
        body["voucherId"] = voucher_id
    result = _post("/api/errands", user_token, body)
    if not result["ok"]:
        return result
    data = result["data"] or {}
    return {
        "ok": True,
        "errand_id": str(data.get("errandId")),
        "status": data.get("status"),
        "paid_yuan": _yuan(data.get("paidCents")),
        "discount_yuan": _yuan(data.get("discountCents")),
    }
