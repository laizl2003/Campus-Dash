# CampusDash Agent 发单 Demo

> 对话式发单 + 缺字段追问。模型抽取部分留空，接入 LLMExtractor 即可替换。

## 能力

- 多轮对话收集任务字段：类型、标题、取货地、送达地、悬赏、任务内容，
  以及**选填**的截止时间（不填表示任务永久有效）
- 缺必填字段时合并成清单一次问全；截止时间与任务内容作为两行选填提示固定挂在末尾
- 已填字段不会被后续"没提到"清空
- OTHER 类型不追问地点
- 信息完整时返回 READY 与可确认草稿

## 运行

    cd D:\Code\Campus_PaoTui\campus-dash-1\agent
    .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000

## 接口

    GET  /health
    POST /sessions                  创建会话，返回 session_id
    POST /chat  {session_id, message}

响应字段：

    status     ASKING | READY
    message    追问或完成提示
    draft      当前草稿
    missing    还缺哪些槽位
    question   下一个要问的问题
    type_label 类型中文名

## 源码位置

    app/models.py          TaskDraft、槽位定义、必填规则、追问文案、草稿合并
    app/agent/extractor.py 抽取接口、规则兜底、LLMExtractor 空实现
    app/agent/graph.py     LangGraph 状态机：extract -> check -> ask/ready
    app/service.py         会话存储与一次对话的编排
    app/main.py            FastAPI 路由
    tests/test_flow.py     追问、合并、类型差异测试

## 追问原理

1. required_slots(draft) 是唯一判定入口：类型未知只问类型；类型确定后按类型给出必填清单；描述始终选填。
2. merge_draft 只覆盖非空字段，所以用户只回答一个值时，之前的信息保留。
3. 状态机决定分支，不交给模型自由决定下一步，追问顺序固定且可测试：

    START -> extract -> check --有缺失--> ask_user -> END(ASKING)
                             \--无缺失--> ready    -> END(READY)

4. 裸回答回填：上一轮问"送到哪里"，用户回"3号楼502"时，即使模型没抽到 location_to 也会填进去；
   但"跑腿配送"这类纯类型回答不会误填成标题。

## 接入真实模型

实现 app/agent/extractor.py 的 LLMExtractor.extract：

1. 用 TaskDraft.model_json_schema() 约束输出；
2. 提示词里带上当前草稿，避免重复问已填字段；
3. 只返回 JSON，用 TaskDraft.model_validate 二次校验，失败抛 ExtractionError；
4. 在 default_extractor() 里换成 LLMExtractor(...)。

追问逻辑与状态机不需要改。

## 测试

    .\.venv\Scripts\python.exe -m pytest -q
