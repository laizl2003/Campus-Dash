# 接入 LLM：中科大接口（qwen3.8-reasoner）

`_call_llm` 已经按 OpenAI 兼容协议写好，**你只需要填 API Key**。

## 一、只需配置环境变量

在 `campus-dash-backend/docker/` 下新建 `.env`：

```
LLM_ENABLED=true
LLM_BASE_URL=https://api.llm.ustc.edu.cn/v1
LLM_API_KEY=sk-你的密钥
LLM_MODEL=qwen3.8-reasoner
```

然后重启：

```powershell
cd campus-dash-backend/docker
docker compose -f docker-compose.yaml -f docker-compose.full.yaml up -d --build agent
```

## 二、对应关系

你给的 curl 和代码里的字段是一一对应的：

| curl | 代码 |
| --- | --- |
| `https://api.llm.ustc.edu.cn/v1` | `LLM_BASE_URL` |
| `/chat/completions` | 代码里自动拼接 |
| `Authorization: Bearer xxx` | `LLM_API_KEY` |
| `"model":"qwen3.8-reasoner"` | `LLM_MODEL` |
| `messages` | 系统提示词 + 用户提示词 |

`extractor.py` 里的实际请求：

```python
url = f"{self.settings.base_url}/chat/completions"
payload = {
    "model": self.settings.model,
    "temperature": self.settings.temperature,
    "max_tokens": self.settings.max_tokens,
    "messages": [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ],
    "response_format": {"type": "json_object"},
}
```

## 三、注意事项

### 1. 密钥必须 `sk-` 开头

网关会校验前缀，否则直接返回：

```
401 LiteLLM Virtual Key expected. Received=xxx, expected to start with 'sk-'
```

### 2. `response_format` 可能不支持

代码已做自动降级：返回 400 时去掉该字段重试一次。

### 3. reasoner 模型响应慢

已把默认超时设为 60 秒。如果还是超时，调大：

```
LLM_TIMEOUT=120
```

### 4. 推理模型的特殊情况

`qwen3.8-reasoner` 可能把思考过程放在 `reasoning_content`。
代码在 `content` 为空时会自动兜底读取，不会解析出空结果。

## 四、验证

```powershell
Invoke-RestMethod http://localhost:8088/agent/status
```

配置正确时：

```json
{
  "extractor": "LLMExtractor",
  "llm_usable": true,
  "model": "qwen3.8-reasoner",
  "missing_config": []
}
```

跑不通时 `missing_config` 会告诉你还缺哪个变量。

## 五、实测结果

用假 key 试过，请求已经正确到达网关：

```
last_error = 模型接口 HTTP 401: LiteLLM Virtual Key expected.
patch      = {"type": "DELIVERY", "reward_cents": 800}   <- 规则兜底生效
```

说明地址、路径、鉴权头、JSON 结构都正确，只差真实密钥。
而且**即使模型不可用，对话也不会中断**，会自动降级。

## 六、填好之后会看到什么

```text
> 明天下午三点帮我去菜鸟驿站取快递，送到3号楼502，悬赏8块

模型一次抽全：
{
  "type": "DELIVERY",
  "title": "取快递",
  "location_from": "菜鸟驿站",
  "location_to": "3号楼502",
  "start_time": "2026-11-01T15:00:00",
  "reward_cents": 800
}
```

一句话就把草稿填满，不再需要多轮追问。
