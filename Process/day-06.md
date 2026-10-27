# day-06 · presentation 层源码详解：17 个文件

> 阅读范围：`dash-presentation` 全部 17 个类。
> 沿用前几天的格式：类型、字段、构造器、方法签名、功能、关键源码。
> 这一层只做三件事：**HTTP 协议转换、身份解析、结果包装**。

## 0. 总览

| 分组 | 文件 | 一句话 |
| --- | ---: | --- |
| 认证与身份 | 3 | 登录/刷新/登出、拦截器、当前用户 |
| 任务接口 | 1 | 13 个端点，全生命周期 |
| 查询接口 | 3 | 信用、钱包、站内消息 |
| 基础设施 | 3 | 健康检查、全局异常、Web 配置 |
| 内部运维 | 1 | 缓存统计/校验/布隆重建 |
| 序列化 | 1 | long → string |
| 实时推送 | 5 | WebSocket 配置、握手、处理器、注册表、推送实现 |

**原则**：Controller 不写业务，只做「HTTP 参数 → Command」「领域结果 → Result」。

---

## 0.1 类名中文对照

| 类名 | 中文名 | 分组 |
| --- | --- | --- |
| `AuthController` | 认证控制器 | 认证 |
| `AuthInterceptor` | 认证拦截器 | 认证 |
| `CurrentUser` | 当前用户 | 认证 |
| `ErrandController` | 任务控制器 | 任务 |
| `CreditController` | 信用控制器 | 查询 |
| `WalletController` | 钱包控制器 | 查询 |
| `NotificationController` | 站内消息控制器 | 查询 |
| `HealthController` | 健康检查控制器 | 基础 |
| `GlobalExceptionHandler` | 全局异常处理 | 基础 |
| `WebConfig` | Web 配置 | 基础 |
| `InternalController` | 内部运维控制器 | 运维 |
| `JacksonConfig` | Jackson 配置 | 序列化 |
| `WebSocketConfig` | WebSocket 配置 | 实时 |
| `WsAuthHandshakeInterceptor` | WS 握手鉴权 | 实时 |
| `WsHandler` | WS 连接处理器 | 实时 |
| `WsSessionRegistry` | WS 会话注册表 | 实时 |
| `RealtimePushService` | 实时推送服务 | 实时 |

---

## 1. 认证与身份（3）

### 1.1 `AuthController` · 认证控制器（class）

**功能**：登录、刷新、登出三个端点。登录/刷新**不走拦截器**（还没有有效 access）。

**字段**：`AuthPort authPort`
**构造器**：`AuthController(AuthPort authPort)`
**请求体**：`LoginRequest(Long userId)`、`RefreshRequest(String refreshToken)`、`LogoutRequest(String refreshToken)`

**方法**

| 方法 | 端点 | 说明 |
| --- | --- | --- |
| `login(LoginRequest)` | POST `/api/auth/login` | 校验 userId > 0；若实现是 `RefreshTokenPort` 就发一对，否则只发 access |
| `refresh(RefreshRequest)` | POST `/api/auth/refresh` | 调 `refresh`，成功返回新的一对，失败返回 `UNAUTHORIZED` |
| `logout(Authorization, LogoutRequest)` | POST `/api/auth/logout` | 取 Bearer token 调 `logout` 拉黑 jti；body 带 refreshToken 时一并 `revokeRefresh`，始终返回 ok |

**关键源码（双模式兼容）**

~~~java
if (authPort instanceof RefreshTokenPort refreshTokenPort) {
    var pair = refreshTokenPort.loginWithRefresh(req.userId());
    data.put("token", pair.accessToken());      // 兼容旧前端
    data.put("accessToken", pair.accessToken());
    data.put("refreshToken", pair.refreshToken());
    return Result.ok(data);
}
return Result.ok(Map.of("token", authPort.login(req.userId()), "userId", req.userId()));
~~~

**要点**：

- 负数和 0 被拒绝——系统内部账户（-1/-2）不能通过演示登录拿到令牌；
- 登出必须同时撤销 refresh：只把 access 的 `jti` 拉黑，refresh 在 7 天内仍能换出新 access，等于登出无效。
  该缺口已于 2026-10-27 补齐（见 `TODO.md` 第 1 条），前端 `logout()` 会用 `keepalive` 请求把 refresh 交给服务端撤销。

### 1.2 `AuthInterceptor` · 认证拦截器（class）

**功能**：每个 `/api/**` 请求解析出 userId，放进 request attribute。

**字段**：`AuthPort authPort`、`boolean allowHeaderIdentity`
**构造器**：`AuthInterceptor(AuthPort, @Value allow-header-identity)`

**方法**：`preHandle(req, resp, handler)`

**关键源码**

~~~java
if (allowHeaderIdentity) {
    String header = req.getHeader("X-User-Id");     // 测试/压测后门
    if (header != null && !header.isBlank()) userId = Long.parseLong(header);
}
if (userId == null) {
    String auth = req.getHeader("Authorization");
    if (auth != null && auth.startsWith("Bearer ")) {
        userId = authPort.resolve(auth.substring(7).trim()).orElse(null);
    }
}
if (userId == null) { resp.setStatus(401); return false; }
req.setAttribute(CurrentUser.ATTR, userId);
return true;
~~~

**要点**：后门开关默认 false，生产打开等于任何人带个 Header 就能冒充任何人。

### 1.3 `CurrentUser` · 当前用户（class）

**功能**：Controller 里静态取当前请求者，避免每个方法都传 userId。

**字段**：`static final String ATTR = "currentUserId"`；私有构造器
**方法**：`static long get()`

~~~java
RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
Object v = attrs == null ? null : attrs.getAttribute(ATTR, SCOPE_REQUEST);
if (v == null) throw new BizException(ErrorCode.UNAUTHORIZED, ...);
return (Long) v;
~~~

**要点**：依赖 ThreadLocal 的请求上下文，**子线程/异步任务里取不到**，需要显式传参。

---

## 2. 任务接口（1）

### 2.1 `ErrandController` · 任务控制器（class）

**功能**：发布、列表、详情、时间线、抢单、确认、取货、送达、结算、取消、争议、仲裁，共 13 个端点。

**字段**：12 个用例 + `ErrandRepository`（详情里算 availableActions 用）
**构造器**：13 参构造
**请求体**：`PublishRequest(campusId, type, title, rewardCents, slotTotal)`、`ArbitrateRequest(String favor)`

**端点**

| 方法 | 端点 | 返回 |
| --- | --- | --- |
| `publish` | POST `/api/errands` | `Result<PublishErrandUseCase.Result>` |
| `list` | GET `/api/errands` | 列表或游标页 |
| `mine` | GET `/api/errands/mine` | 我发布的/我抢的 |
| `detail` | GET `/api/errands/{id}` | 任务卡片 |
| `timeline` | GET `/api/errands/{id}/timeline` | 状态变更 |
| `grab` | POST `/api/errands/{id}/grab` | 抢单结果 |
| `confirm` | POST `/api/errands/{id}/confirm` | ok |
| `pickup` | POST `/api/errands/{id}/pickup` | ok |
| `deliver` | POST `/api/errands/{id}/deliver` | ok |
| `settle` | POST `/api/errands/{id}/settle` | result 枚举 |
| `cancel` | POST `/api/errands/{id}/cancel` | result 枚举 |
| `dispute` | POST `/api/errands/{id}/dispute` | ok |
| `arbitrate` | POST `/api/errands/{id}/arbitrate` | result 枚举 |

**关键源码 1：统一卡片**

~~~java
private Map<String, Object> toCard(Errand e, long viewer) {
    var resolved = actionResolver.resolve(e, viewer);
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("id", e.id());
    ...
    card.put("grabberId", e.grabberId() == null ? -1L : e.grabberId());
    card.put("role", resolved.role());
    card.put("availableActions", resolved.actions());
    return card;
}
~~~

列表与详情共用，保证按钮集合处处一致；`grabberId` 用 -1 表示“没人抢”，避免 JSON 里的 null 处理。

**关键源码 2：请求级数据不进共享缓存**

~~~java
// detail(): 先走缓存判断存在性，再用聚合算 availableActions
var cachedJson = detailUseCase.detailJson(errandId);
if (cachedJson.isEmpty()) return Result.fail(ERRAND_NOT_FOUND);
return errandRepository.findById(errandId)
        .map(e -> Result.ok(toCard(e, viewer)))
        .orElseGet(() -> Result.fail(ERRAND_NOT_FOUND));
~~~

注释里点明了缓存设计里最容易出的事故：**凡是与请求者身份相关的派生数据都不能进共享缓存**，否则 A 用户会看到 B 用户的按钮。

**关键源码 3：抢单幂等键**

~~~java
String requestId = header == null ? UUID.randomUUID().toString() : header;
var cmd = new GrabErrandUseCase.Command(errandId, runnerId, requestId, 60);
GrabErrandUseCase.Result result = grabUseCase.grab(cmd);
return result.grabbed() ? Result.ok(result) : Result.fail(result.code(), result);
~~~

客户端用同一个 `X-Request-Id` 重试，服务端靠它做幂等；抢不到属于“业务失败”，用 `code` 而不是 HTTP 5xx。

---

## 3. 查询接口（3）

### 3.1 `CreditController` · 信用控制器（class）

**字段**：`QueryCreditUseCase`
**方法**：

| 方法 | 端点 | 返回 |
| --- | --- | --- |
| `self()` | GET `/api/credit/self` | `score / windowDays / events` |
| `ranking(campusId, limit)` | GET `/api/credit/ranking` | 校区榜 |

**要点**：排行榜也调了一次 `CurrentUser.get()`——只为强制登录；事件里带上 `description`，让用户知道“为什么是这个分”。

### 3.2 `WalletController` · 钱包控制器（class）

**字段**：`QueryWalletUseCase`
**方法**：`balance()`（availableCents/frozenCents）、`ledger(page, size)`（流水，含 bizNo）。

### 3.3 `NotificationController` · 站内消息控制器（class）

**字段**：`QueryNotificationUseCase`
**方法**：`list(page, size)`、`unread()` → `{count}`。

---

## 4. 基础设施（3）

### 4.1 `HealthController` · 健康检查（class）

`GET /api/health` → `{status:"UP", project:"campus-dash"}`，不走认证（前端/运维探测用）。

### 4.2 `GlobalExceptionHandler` · 全局异常处理（class）

**注解**：`@RestControllerAdvice`

| 异常 | 返回 | 说明 |
| --- | --- | --- |
| `BizException` | 200 + `Result.fail(code)` | **业务失败不是服务器错误** |
| `DuplicateKeyException` | 200 + `GRAB_CONFLICT` | 唯一索引兜底生效，属预期 |
| `Exception` | 500 + `INTERNAL_ERROR` | 记 error 日志 |

**要点**：抢不到单是正常业务结果，不该返回 5xx；否则前端会把“名额已满”当成服务故障。

### 4.3 `WebConfig` · Web 配置（class）

**功能**：注册拦截器 + CORS。

~~~java
registry.addInterceptor(authInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/auth/login", "/api/auth/refresh", "/api/health")
        .excludePathPatterns("/api/internal/**");
~~~

**要点**：`/api/internal/**` 不做鉴权，**生产必须由网关屏蔽外部访问**；CORS 只允许配置的前端源。

---

## 5. 内部运维（1）

### 5.1 `InternalController` · 内部运维控制器（class）

**字段**：`GetErrandDetailUseCase`、`CacheConsistencyCheckUseCase`、`BloomRebuildUseCase`

| 方法 | 端点 | 用途 |
| --- | --- | --- |
| `cacheStats()` | GET `/api/internal/cache-stats` | 请求数/命中/回源/命中率 |
| `resetStats()` | POST `/api/internal/cache-stats/reset` | 压测前清零 |
| `cacheCheck()` | POST `/api/internal/cache-check` | 手动跑一轮一致性校验 |
| `bloomRebuild()` | POST `/api/internal/bloom-rebuild` | 批量造数后重建布隆 |

**要点**：把“运维动作”做成端点，压测时不用重启应用；安全边界靠部署层。

---

## 6. 序列化（1）

### 6.1 `JacksonConfig` · Jackson 配置（class）

**功能**：全局把 `long/Long` 序列化成字符串。

~~~java
SimpleModule module = new SimpleModule();
module.addSerializer(Long.class, ToStringSerializer.instance);
module.addSerializer(Long.TYPE, ToStringSerializer.instance);
builder.modules(module);
~~~

**要点**：雪花 ID 约 2.15e17 > JS 安全整数 2^53，不转字符串前端会截断（P4 实测 215731113107132416 → ...400）。反序列化不受影响。

---

## 7. 实时推送（5）

### 7.1 `WebSocketConfig` · WebSocket 配置（class）

`@EnableWebSocket` + 条件装配（`dash.ws.enabled`）：

~~~java
registry.addHandler(wsHandler, "/ws")
        .addInterceptors(handshakeInterceptor)
        .setAllowedOrigins("*");     // 演示放开，生产要收敛
~~~

### 7.2 `WsAuthHandshakeInterceptor` · WS 握手鉴权（class）

**功能**：从 `?token=xxx` 解析身份，成功则把 userId 放进 session attributes。

~~~java
String token = servletRequest.getServletRequest().getParameter("token");
Optional<Long> userId = authPort.resolve(token);
if (userId.isPresent()) {
    attributes.put(WsHandler.ATTR_USER_ID, userId.get());
    return true;
}
return false;   // 拒绝握手
~~~

**要点**：浏览器原生 WebSocket 不能设自定义 Header，只能把 token 放 query；生产建议一次性短时 ticket + WSS。

### 7.3 `WsHandler` · WS 连接处理器（class）

| 方法 | 行为 |
| --- | --- |
| `afterConnectionEstablished` | 取 userId，为空则关闭；否则 `registry.add` |
| `handleTextMessage` | 收到 `ping` 回 `pong`，其他消息忽略 |
| `afterConnectionClosed` | `registry.remove` |

**要点**：协议只有“服务端 → 客户端”的业务语义，客户端消息只用来保活。

### 7.4 `WsSessionRegistry` · WS 会话注册表（class）

**字段**：`ConcurrentHashMap<Long, Set<WebSocketSession>> sessions`

| 方法 | 说明 |
| --- | --- |
| `add(userId, session)` | `computeIfAbsent` + `CopyOnWriteArraySet`，支持多端 |
| `remove(userId, session)` | 移除后若集合空则删 key |
| `of(userId)` | 返回该用户全部连接（无则空集合） |
| `onlineUsers()` | 在线用户数 |

**要点**：读多写少，用 `CopyOnWriteArraySet`；遍历推送时不会因并发增删抛异常。

### 7.5 `RealtimePushService` · 实时推送服务（class）

**功能**：实现 `RealtimeNotifier`，把三种事件推给在线连接。

| 方法 | 消息 type | 接收人 |
| --- | --- | --- |
| `errandStatusChanged` | `errand.status` | 发单人 + 抢中者 |
| `notificationArrived` | `notification.new` | 指定用户 |
| `creditChanged` | `credit.changed` | 本人 |

**关键源码（发送要加锁）**

~~~java
for (WebSocketSession session : registry.of(userId)) {
    if (!session.isOpen()) continue;
    try {
        synchronized (session) {                 // sendMessage 非线程安全
            session.sendMessage(new TextMessage(json));
        }
    } catch (IOException e) {
        log.debug("推送失败 userId={}（连接可能已断）", userId);
    }
}
~~~

**要点**：JSON 手写拼装 + `escape` 转义；推送失败只记日志不重试——事实以 DB 为准，前端有轮询兜底。

---

## 8. 横向模式

| 模式 | 例子 | 为什么 |
| --- | --- | --- |
| **Controller 只做协议转换** | 所有 Controller | 业务在 UseCase，Controller 不写规则 |
| **身份统一入口** | `CurrentUser.get()` | 避免每个方法传 userId |
| **统一响应** | `Result<T>` | 前端只解析一种结构 |
| **异常统一映射** | `GlobalExceptionHandler` | 业务失败 200、系统异常 500 |
| **DTO 不暴露领域对象** | `toCard` / `Map` | 领域模型可自由演进 |
| **请求级数据不入缓存** | `availableActions` | 防缓存串号 |
| **WebSocket 复用 AuthPort** | 握手拦截器 | 同一套认证逻辑 |

## 9. 自测

- [ ] 为什么 `AuthController` 的登录/刷新要排除在拦截器之外？
- [ ] `CurrentUser.get()` 在异步线程里为什么取不到？怎么解决？
- [ ] 为什么 `availableActions` 不能进 Redis 缓存？
- [ ] `GlobalExceptionHandler` 为什么把业务异常映射成 200？
- [ ] WebSocket 为什么用 `?token=` 而不是 Header？
- [ ] `WsSessionRegistry` 为什么用 `CopyOnWriteArraySet`？
