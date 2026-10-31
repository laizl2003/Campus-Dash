# 接入 LLM 说明

整条抽取链路已经写好，**只有一处需要你填**：

```
app/agent/extractor.py  ->  LLMExtractor._call_llm()
```

## 一、你需要填的代码

打开 `app/agent/extractor.py`，找到：

```python
def _call_llm(self, system_prompt: str, user_prompt: str) -> LLMCallResult:
    raise NotImplementedError("请在 LLMExtractor._call_llm 中填入模型调用")
```

替换成你的调用。**OpenAI 兼容协议**的参考实现（DeepSeek、Moonshot、通义、vLLM 等都适用）：

```python
import httpx

resp = httpx.post(
    f"{self.settings.base_url}/chat/completions",
    headers={"Authorization": f"Bearer {self.settings.api_key}"},
    json={
        "model": self.settings.model,
        "temperature": self.settings.temperature,
        "max_tokens": self.settings.max_tokens,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    },
    timeout=self.settings.timeout,
)
resp.raise_for_status()
data = resp.json()
return LLMCallResult(
    content=data["choices"][0]["message"]["content"],
    model=data.get("model"),
    usage=data.get("usage"),
)
```

约定：**返回 `LLMCallResult(content=模型输出的文本)` 即可**，后面的解析、换算、校验、重试都不用你管。

## 二、配置环境变量

在 `campus-dash-backend/docker/` 下建 `.env`，或在系统环境变量里设：

```
LLM_ENABLED=true
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_API_KEY=sk-xxxxxxxx
LLM_MODEL=deepseek-chat
```

可选：

```
LLM_TIMEOUT=20
LLM_TEMPERATURE=0
LLM_MAX_TOKENS=512
```

然后重启 agent 服务：

```powershell
docker compose -f docker-compose.yaml -f docker-compose.full.yaml up -d --build agent
```

## 三、确认是否生效

```powershell
Invoke-RestMethod http://localhost:8088/agent/status
```

配置正确时：

```json
{
  "extractor": "LLMExtractor",
  "llm_enabled": true,
  "llm_usable": true,
  "model": "deepseek-chat",
  "missing_config": []
}
```

没配置时：

```json
{ "extractor": "RuleExtractor", "llm_usable": false, "missing_config": ["LLM_BASE_URL", "LLM_API_KEY", "LLM_MODEL"] }
}
```

## 四、已经帮你处理好的部分

| 环节 | 文件 | 说明 |
| --- | --- | --- |
| 提示词 | `app/agent/prompts.py` | 字段说明、当前草稿、正在追问的槽位、当前时间都传进去 |
| JSON 容错解析 | `extractor.py: _parse_json` | 处理 ```json 代码块、前后废话、多余文本 |
| 金额换算 | `extractor.py: _to_cents` | "8元"、"8.0"、"800" 统一成分 |
| 时间解析 | `extractor.py: _to_datetime` | ISO 优先，自然语言回退规则解析 |
| 类型别名 | `extractor.py: _normalize` | "跑腿配送" -> DELIVERY |
| 字段校验 | `extractor.py: _validate` | 丢弃非法字段和未知字段，不整轮失败 |
| 失败重试 | `extractor.py: extract` | 默认重试 2 次，指数退避 |
| 降级兜底 | `extractor.py: fallback` | 模型挂了自动走 RuleExtractor |
| 熔断 | `extractor.py: CircuitBreakerExtractor` | 连续失败 5 次后冷却 30 秒，避免每条消息都等超时 |

## 五、注意事项

1. **不要让模型决定追问**。追问顺序由 `models.required_slots` 和 `graph.py` 决定，
   模型只负责"这一步说了什么"，这样"缺字段必问"才可靠。

2. **提示词里已强调"没提到就不要输出"**。如果模型爱乱补默认值，
   可以在 `prompts.py` 的 SYSTEM_PROMPT 里再加一句强约束。

3. **金额返回分**。如果模型返回的 `reward_cents` 明显不对（比如 8 元给了 8），
   检查提示词里"8 元写作 800"这句是否被覆盖。

4. **调整超时**。模型响应慢时把 `LLM_TIMEOUT` 调大，否则会频繁触发降级。

## 六、跑测试

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```

`tests/test_llm.py` 里用假 `_call_llm` 覆盖了：代码块解析、单位换算、类型别名、
非法字段丢弃、垃圾输出降级、异常降级、未实现时的行为。
这三类改动都不会影响追问逻辑。
