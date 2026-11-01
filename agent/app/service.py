"""会话服务：把状态机包装成一次对话调用。

前端只需要持有 conversation_id 和上一轮 draft，服务端负责：
1. 合并本轮抽取结果；
2. 计算缺失槽位；
3. 返回追问或完整草稿。

会话存储两种实现：
- SessionStore     进程内存，零依赖，适合本地随手跑
- MySQLSessionStore 落库，容器重启后历史对话还在（生产用这个）

设置 AGENT_MYSQL_DSN 后自动切换成 MySQL。
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

from .agent.extractor import Extractor, default_extractor
from .agent.graph import AgentReply, build_graph, draft_to_reply
from .agent.intent import Intent
from .models import TYPE_LABELS, TaskDraft, next_question, required_slots


@dataclass
class Session:
    id: str
    # 一次聊天（面板）下可以有多个会话：一单发布后换个会话继续
    panel_id: str | None = None
    draft: TaskDraft = field(default_factory=TaskDraft)
    turns: int = 0
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    history: list[dict[str, object]] = field(default_factory=list)
    # 归属用户：历史消息只给本人看
    user_id: str | None = None
    # 待确认的"新任务"：{"message": 原话}，等用户回"是/不"再决定新建还是修改
    pending: dict | None = None
    # ACTIVE / CLOSED（已发布）/ CANCELED（已取消）。发布或取消后这一单就结束了
    status: str = "ACTIVE"


class SessionStore:
    """Demo 用内存会话表；替换成 Redis 时保持同样接口即可。"""

    def __init__(self) -> None:
        self._sessions: dict[str, Session] = {}

    def create(self, user_id: str | None = None, panel_id: str | None = None) -> Session:
        session = Session(
            id=str(uuid.uuid4()), panel_id=panel_id or str(uuid.uuid4()), user_id=user_id
        )
        self._sessions[session.id] = session
        return session

    def get_active_session(self, panel_id: str) -> Session | None:
        for session in self._sessions.values():
            if session.panel_id == panel_id:
                return session
        return None

    def ensure_session(self, panel_id: str, user_id: str | None = None) -> Session:
        session = self.get_active_session(panel_id)
        if session is None:
            return self.create(user_id, panel_id)
        # 面板 id 不是凭证：别人拿到它也不能往这段对话里写
        owner = getattr(session, "user_id", None)
        if user_id and owner and owner != user_id:
            raise PermissionError("panel belongs to another user")
        return session

    def append_message(
        self,
        session_id: str,
        role: str,
        content: str,
        meta: dict | None = None,
        user_id: str | None = None,
    ) -> None:
        # 内存版不存消息表，历史只在进程内有意义
        return None

    def messages(self, session_id: str) -> list[dict[str, object]]:
        return []

    def panel_messages(
        self,
        panel_id: str,
        limit: int = 200,
        before_id: int | None = None,
        user_id: str | None = None,
    ) -> list[dict[str, object]]:
        return []

    def list_panels(self, user_id: str, limit: int = 20) -> list[dict[str, object]]:
        return []

    def delete_panel(self, panel_id: str) -> bool:
        ids = [s.id for s in self._sessions.values() if s.panel_id == panel_id]
        for sid in ids:
            self._sessions.pop(sid, None)
        return bool(ids)

    def get(self, session_id: str) -> Session | None:
        return self._sessions.get(session_id)

    def save(self, session: Session, status: str | None = None, last_message: str | None = None) -> None:
        if status:
            session.status = status
        self._sessions[session.id] = session


def default_store() -> "SessionStore | MySQLSessionStore":
    """配了 DSN 就用 MySQL，否则退回内存。

    MySQL 连不上时不让整个服务起不来：打日志并降级到内存，
    用户仍能对话，只是重启后会话不保留。
    """
    import logging
    import os

    if not os.getenv("AGENT_MYSQL_DSN"):
        return SessionStore()
    try:
        from .store_mysql import MySQLSessionStore

        return MySQLSessionStore()
    except Exception as exc:  # noqa: BLE001
        logging.getLogger(__name__).warning("MySQL 会话存储不可用，降级为内存: %s", exc)
        return SessionStore()


# 确认"是不是新的一单"时的回话
# 规则只兜最直白的说法；"还是原来那单""这算新的一单吧"这种交给模型判断，
# 否则用户得记住必须回"是/否"，体验很差。
_YES_WORDS = ("是", "对", "嗯", "好", "可以", "新建", "新的", "新单", "另一单", "是的", "对对", "另起一单")
_NO_WORDS = ("不", "不是", "改", "别", "不用", "算了", "还是", "原来那单", "原来这一单", "就这一单")

CONFIRM_SYSTEM_PROMPT = """你在判断用户对一句提问的回答。

助手刚才问："这看起来是新的一单，不是要改当前这一单。是新建，还是改当前这一单？"
用户回答说了一句中文。请判断用户的意思属于哪一类，只输出一个英文标签：

- NEW   这是新的一单，要单独新建（如"是新的一单""对，另外建一个""重新开一单""这单另算"）
- SAME  还是当前这一单，只是修改（如"还是原来那单""就改这个""不用新建""我说的是这单的金额"）
- OTHER 没有在回答这个问题（如"等会儿再说""先看看广场""帮我接个单"）

判断原则：
1. 抓"新建 / 另外 / 新的一单"这类语义 → NEW；
2. 抓"还是原来 / 就这个 / 改这一单 / 不用新建"这类语义 → SAME；
3. 完全不相关 → OTHER；
4. 拿不准就 OTHER，不要硬猜。

只输出标签本身，不要解释。"""


def _confirmation_by_rules(text: str) -> bool | None:
    if any(word in text for word in ("不是", "不新", "不用", "不要", "不用建")):
        return False
    if any(word in text for word in ("原来那单", "原来这一单", "就这一单", "还是这单")):
        return False
    if text in _NO_WORDS or text.startswith(("不", "别")):
        return False
    if text in _YES_WORDS or text.startswith(("是", "对", "嗯", "好", "可以")):
        return True
    return None


def parse_confirmation(
    message: str, pending_message: str | None = None, extractor=None
) -> bool | None:
    """判断用户对"是新的一单吗"的回答：True=新建，False=改当前，None=没回答。

    先走关键词（快且确定），再让模型理解"还是原来那单"这类自然说法。
    """
    text = (message or "").strip().strip("。.!！~ ")
    if not text:
        return None

    by_rules = _confirmation_by_rules(text)
    if by_rules is not None:
        return by_rules

    # 太长的句子多半是在说别的事，不值得为它多调一次模型
    if len(text) > 30 or extractor is None:
        return None

    context = (
        f"助手刚才把你说的「{pending_message}」理解成新的一单，问你是新建还是改当前这单。\n"
        f"用户回答：{text}"
        if pending_message
        else f"用户回答：{text}"
    )
    try:
        label = extractor.classify_text(CONFIRM_SYSTEM_PROMPT, context)
    except Exception:  # noqa: BLE001
        return None

    if not label:
        return None
    value = label.strip().upper()
    if "NEW" in value and "SAME" not in value:
        return True
    if "SAME" in value:
        return False
    return None


NEW_TASK_QUESTION = (
    "这看起来是新的一单，不是要改当前这一单。\n"
    "· 要单独建，就说「是新的一单」\n"
    "· 要改当前这单，就说「还是原来那单」"
)



class ChatService:
    def __init__(self, extractor: Extractor | None = None, store=None) -> None:
        self.extractor = extractor or default_extractor()
        self.store = store if store is not None else default_store()
        self.graph = build_graph(self.extractor)
        # 优惠券查询有网络开销，按（会话, 金额）缓存，避免每轮都查
        self._voucher_cache: dict[tuple[str, int], dict | None] = {}

    def create_session(self, user_id: str | None = None) -> Session:
        try:
            return self.store.create(user_id)
        except TypeError:
            # 内存版 create 不接收 user_id
            return self.store.create()

    def mark_published(self, session_id: str | None) -> None:
        """发布成功后把会话标记成 CLOSED。

        不标记的话，用户从历史里点开这一单继续说，会被当成"给已发布的草稿补内容"，
        新任务就带着上一单的字段开始了。
        """
        if not session_id:
            return
        session = self.store.get(session_id)
        if session is None:
            return
        try:
            self.store.save(session, status="CLOSED")
        except TypeError:
            # 内存版 save 只收 session，没有状态列可写
            self.store.save(session)

    def record_cancelled(self, panel_id: str | None = None,
                         session_id: str | None = None,
                         announce: bool = True) -> None:
        """取消这一单：会话置 CANCELED、卡片标记已取消、按需补提示消息。

        和发布走同一套收尾，只是一单"没发出去"。会话结束后面板仍可用，
        下一句话会自动开新会话，用户不用刷新页面。
        """
        target = session_id
        if target is None and panel_id:
            session = self.store.get_active_session(panel_id)
            target = session.id if session is not None else None
        if target is None:
            return

        session = self.store.get(target)
        if session is not None:
            # 取消 = 这一单作废：草稿一并清空，下一句话从空白开始
            # （卡片仍留在历史里，带"已取消"标记）
            try:
                session.draft = TaskDraft()
            except Exception:  # noqa: BLE001
                pass
            try:
                self.store.save(session, status="CANCELED")
            except TypeError:
                self.store.save(session)
        mark_card = getattr(self.store, "mark_card_cancelled", None)
        if mark_card is not None:
            mark_card(target)
        if announce and hasattr(self.store, "append_message"):
            owner = getattr(session, "user_id", None) if session else None
            self.store.append_message(
                target, "assistant",
                "任务已取消，这一单不会发布。",
                user_id=owner,
            )
            self.store.append_message(
                target, "assistant",
                "接下来想发什么任务？直接告诉我就行。",
                user_id=owner,
            )

    def record_published(self, errand_id: str, panel_id: str | None = None,
                         session_id: str | None = None) -> None:
        """发布成功后的收尾：结束会话、把卡片标记成已发布、补两条提示消息。

        这三件事都要落库，否则刷新页面后：会话还开着（下一单写进旧草稿）、
        卡片变回可编辑、也看不到"已发布"的提示。
        """
        target = session_id
        if target is None and panel_id:
            session = self.store.get_active_session(panel_id)
            target = session.id if session is not None else None
        if target is None:
            return

        session = self.store.get(target)
        self.mark_published(target)
        mark_card = getattr(self.store, "mark_card_published", None)
        if mark_card is not None:
            mark_card(target, errand_id)
        if hasattr(self.store, "append_message"):
            owner = getattr(session, "user_id", None) if session else None
            self.store.append_message(
                target, "assistant",
                f"任务已发布，编号 {errand_id}。可以在「我的任务」里查看进度。",
                user_id=owner,
            )
            self.store.append_message(
                target, "assistant",
                "这一单已经结束，接下来想发什么任务？直接告诉我就行。",
                user_id=owner,
            )

    def mark_published_panel(self, panel_id: str | None) -> None:
        # 前端只记 panel：把该面板下进行中的会话标记为已结束。
        if not panel_id:
            return
        session = self.store.get_active_session(panel_id)
        if session is not None:
            self.mark_published(session.id)

    def delete_session(self, session_id: str) -> bool:
        """删除会话。内存版直接丢字典，MySQL 版连消息一起删。"""
        if hasattr(self.store, "delete"):
            return bool(self.store.delete(session_id))
        existed = self.store.get(session_id) is not None
        if existed:
            self.store._sessions.pop(session_id, None)  # noqa: SLF001
        return existed

    def list_sessions(self, user_id: str, limit: int = 20) -> list[dict[str, object]]:
        if hasattr(self.store, "list_sessions"):
            return self.store.list_sessions(user_id, limit)
        return []

    def messages(self, session_id: str) -> list[dict[str, object]]:
        if hasattr(self.store, "messages"):
            return self.store.messages(session_id)
        return []

    def chat(
        self, session_id: str, message: str, user_token: str | None = None,
        user_id: str | None = None,
    ) -> AgentReply:
        session = self.store.get(session_id)
        if session is None:
            raise KeyError(session_id)
        owner = getattr(session, "user_id", None)
        if user_id and owner and owner != user_id:
            raise PermissionError("session belongs to another user")
        return self._chat(session, message, user_token)

    def chat_panel(
        self, panel_id: str, message: str, user_id: str | None = None,
        user_token: str | None = None,
    ) -> AgentReply:
        # 面板入口：自动挑进行中的会话，没有就开一个。
        # 前端只记 panel_id，就不用再操心"该用哪个 session"，
        # 也不会出现发布后还往旧草稿里写的问题。
        session = self.store.ensure_session(panel_id, user_id)
        return self._chat(session, message, user_token)

    def create_panel(self, user_id: str | None = None) -> dict[str, str]:
        # 新建一段对话：此刻只发一个 panel_id，真正开 session 等第一条消息。
        import uuid as _uuid

        panel_id = str(_uuid.uuid4())
        self.store.ensure_session(panel_id, user_id)
        return {"panel_id": panel_id}

    def panel_messages(
        self,
        panel_id: str,
        limit: int = 200,
        before_id: int | None = None,
        user_id: str | None = None,
    ):
        return self.store.panel_messages(
            panel_id, limit=limit, before_id=before_id, user_id=user_id
        )

    def panel_owner(self, panel_id: str) -> str | None:
        owner = getattr(self.store, "panel_owner", None)
        return owner(panel_id) if owner else None

    def list_panels(self, user_id: str, limit: int = 20):
        return self.store.list_panels(user_id, limit)

    def delete_panel(self, panel_id: str) -> bool:
        return bool(self.store.delete_panel(panel_id))

    def _voucher_for(self, session, draft: TaskDraft, token: str | None) -> dict | None:
        """当前金额下最优的那张券；查不到或没登录态就返回 None。"""
        if not token or not draft.reward_cents:
            return None
        key = (session.id, int(draft.reward_cents))
        if key in self._voucher_cache:
            return self._voucher_cache[key]
        from .tools.actions import best_voucher

        try:
            value = best_voucher(token, int(draft.reward_cents))
        except Exception:  # noqa: BLE001
            value = None
        self._voucher_cache[key] = value
        return value

    def _chat(self, session, message: str, user_token: str | None = None) -> AgentReply:
        # 上一轮问了"这是新的一单吗"，这一轮先把"是/不"翻译成实际动作
        prefix = ""
        force_merge = False
        pending = getattr(session, "pending", None)
        if pending and pending.get("message"):
            answer = parse_confirmation(
                message,
                pending_message=pending.get("message"),
                extractor=self.extractor,
            )
            if answer is not None:
                # 两个分支都要重放"当时那条新任务描述"
                message = pending["message"]
                if answer:
                    # 新的一单：清空草稿，用原话重新抽取
                    session.draft = TaskDraft()
                    prefix = "好的，按新的一单处理。\n"
                else:
                    # 改当前这一单：原草稿保留，并且**禁止**再判新单，
                    # 否则重放这句话又会被识别成 NEW_TASK，陷入反复追问
                    force_merge = True
                    prefix = "好的，按对当前这一单的修改处理。\n"
            # 没在回答这个问题（或回答不清）：把待确认丢掉，当作普通指令继续
            session.pending = None
            self.store.save(session)

        missing = required_slots(session.draft)
        # 短期记忆：把本次会话最近几轮喂给抽取器，便于理解"就那个"这类省略；
        # 从库里取（而不是只靠内存），刷新/重连后也能接上上下文。
        history: list[dict] = []
        try:
            if session.panel_id and hasattr(self.store, "panel_messages"):
                # 整个面板的最近 10 组问答：跨会话也能接上（发布/取消后仍记得上一单）
                history = self.store.panel_messages(session.panel_id, limit=20)
            elif hasattr(self.store, "messages"):
                history = self.store.messages(session.id)[-20:]
        except Exception:  # noqa: BLE001
            history = []
        state = self.graph.invoke(
            {
                "message": message,
                "draft": session.draft,
                "missing": missing,
                "history": history,
                "user_token": user_token or "",
                "force_merge": force_merge,
            }
        )
        draft: TaskDraft = state["draft"]
        missing = state["missing"]
        status = state.get("status", "ASKING")
        # 图里可能给追问加过说明（例如"这句不太像地点"），优先用它
        question = (state.get("question") or next_question(missing, draft=draft)) if status != "READY" else None
        # 只有本轮真的追加了内容才传 True；首次完整和"没有了"都要区分开
        appended = state.get("appended") if status == "READY" else None
        intent = state.get("intent") if status == "READY" else None

        session.draft = draft
        session.turns += 1

        # 先把回复算出来，再落库。
        # 之前这里用的是 state["message"]——那是**用户输入**，
        # 结果 assistant 消息存成了用户原话的复读，刷新后整段历史都是废的。
        if status == "ANSWER":
            # 工具链路的回答也要落库：否则刷新面板，问过的查询会消失
            if hasattr(self.store, "append_message"):
                self.store.append_message(
                    session.id, "user", message, user_id=session.user_id
                )
                self.store.append_message(
                    session.id, "assistant", state.get("answer") or "", user_id=session.user_id
                )
            # 手上有草稿时顺手提醒一句：查完还能接着做这一单，
            # 免得用户以为刚才那一单没了
            answer = state.get("answer") or "我没有得到可用的结果。"
            if any(
                value not in (None, "", [], {})
                for key, value in draft.model_dump().items()
                if key not in ("title", "title_base")
            ):
                answer = f"{answer}\n\n（当前这一单的草稿还在，继续说需求或回复「发布」即可）"

            return {
                "status": "ANSWER",
                "message": answer,
                "draft": draft.model_dump(mode="json"),
                "missing": [],
                "question": None,
                "intent": None,
                "can_publish": False,
                "cleared": False,
                "show_draft": False,
                "unclear": False,
                "updated_fields": [],
                "tool_calls": state.get("tool_calls") or [],
                "session_id": session.id,
                "panel_id": session.panel_id,
            }

        if intent == Intent.NEW_TASK and not force_merge:
            # 不动草稿，先把这句话记下来，等用户确认。
            # （用户已经确认过"改当前这单"时 force_merge=True，这里不再问）
            session.pending = {"message": message}
            self.store.save(session)
            if hasattr(self.store, "append_message"):
                self.store.append_message(
                    session.id, "user", message, user_id=session.user_id
                )
                self.store.append_message(
                    session.id, "assistant", NEW_TASK_QUESTION, user_id=session.user_id
                )
            return {
                "status": status,
                "message": (prefix or "") + NEW_TASK_QUESTION,
                "draft": draft.model_dump(mode="json"),
                "missing": [slot.value for slot in missing],
                "question": None,
                "intent": Intent.NEW_TASK.value,
                "can_publish": False,
                "cleared": False,
                "show_draft": True,
                "unclear": False,
                "updated_fields": [],
                "session_id": session.id,
                "panel_id": session.panel_id,
                "pending_new_task": True,
            }

        reply = draft_to_reply(status, draft, missing, question, appended, intent,
                               list(state.get("updated_fields") or []),
                               bool(state.get("rejected_location")),
                               state.get("published"))
        # 截止时间被纠正过：先说清楚，再给原来的回话
        note = state.get("deadline_note")
        if note:
            reply["deadline_note"] = note
            reply["message"] = f"{note}\n{reply.get('message') or question or ''}"
        if prefix:
            reply["message"] = f"{prefix}{reply.get('message') or ''}"
        reply_text = reply.get("message") or question or ""

        session.history.append({"role": "user", "content": message})
        session.history.append({"role": "assistant", "content": reply_text})

        try:
            self.store.save(session, status=status, last_message=message)
        except TypeError:
            self.store.save(session)

        # 卡片状态跟着消息一起存：刷新页面才能把草稿卡片画回来
        published = state.get("published") or {}
        card_meta = None
        # 已经通过工具发出去了：这一条只是状态播报，不再挂草稿卡，
        # 否则前端会多出一张"可再次发布"的卡片（一单发两次的根源）
        if status == "READY" and not published.get("ok"):
            card_meta = {
                "draft": draft.model_dump(mode="json"),
                "type_label": TYPE_LABELS.get(str(draft.type), None) if draft.type else None,
            }
            voucher = self._voucher_for(session, draft, user_token)
            if voucher:
                card_meta["voucher"] = voucher
                # 同步给前端：卡片上要显示"可用券 -X 元，实付 Y 元"
                reply["voucher"] = voucher
        if published.get("ok"):
            reply["published_id"] = str(published.get("errand_id") or "")
        if hasattr(self.store, "append_message"):
            self.store.append_message(session.id, "user", message, user_id=session.user_id)
            self.store.append_message(
                session.id, "assistant", reply_text, card_meta, user_id=session.user_id
            )

        # 工具已经把单发出去了：复用发布收尾（置 CLOSED、卡片标记、补两条消息）
        if published.get("ok"):
            self.record_published(str(published.get("errand_id") or ""), session_id=session.id)

        # 用户在对话里说"取消"：和点卡片取消一样收尾，但不再重复播报
        if status == "READY" and intent == Intent.CANCEL:
            self.record_cancelled(session_id=session.id, announce=False)
        # 让前端知道这轮落在哪个会话上（同一个面板下，发布后会换成新会话）
        reply["session_id"] = session.id
        reply["panel_id"] = session.panel_id
        return reply
