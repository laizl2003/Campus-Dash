package com.campusdash.presentation;

import com.campusdash.application.usecase.ArbitrateErrandUseCase;
import com.campusdash.application.usecase.query.QueryErrandListUseCase;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.voucher.model.VoucherStock;
import com.campusdash.domain.voucher.ports.VoucherClaimPort;
import com.campusdash.domain.voucher.model.VoucherType;
import com.campusdash.domain.voucher.model.VoucherUse;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.model.EscrowOrder;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.domain.admin.ports.AdminArbitrationQueryPort;
import com.campusdash.domain.admin.ports.AdminUserQueryPort;
import com.campusdash.domain.auth.ports.AuthPort;
import com.campusdash.domain.auth.ports.ForceLogoutPort;
import com.campusdash.domain.auth.model.UserAccount;
import com.campusdash.domain.auth.ports.UserRepository;
import com.campusdash.domain.config.ports.PlatformConfigPort;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.presentation.realtime.WsSessionRegistry;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.CloseStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：仲裁工作台。
 *
 * 整个 /api/admin/** 由 AdminInterceptor 做角色校验，
 * 这里不再重复判断 role —— 权限边界只在拦截器一处，避免多点校验漂移。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final QueryErrandListUseCase queryUseCase;
    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final VoucherRepository voucherRepository;
    private final ArbitrateErrandUseCase arbitrateUseCase;
    private final PlatformConfigPort platformConfig;
    private final AdminUserQueryPort adminUserQuery;
    private final AdminArbitrationQueryPort adminArbitrationQuery;
    private final WsSessionRegistry wsSessionRegistry;
    private final ForceLogoutPort forceLogoutPort;
    private final UserRepository userRepository;
    private final AuthPort authPort;
    private final VoucherClaimPort voucherClaimCache;

    public AdminController(QueryErrandListUseCase queryUseCase,
                           ErrandRepository errandRepository,
                           WalletRepository walletRepository,
                           VoucherRepository voucherRepository,
                           ArbitrateErrandUseCase arbitrateUseCase,
                           PlatformConfigPort platformConfig,
                           AdminUserQueryPort adminUserQuery,
                           AdminArbitrationQueryPort adminArbitrationQuery,
                           WsSessionRegistry wsSessionRegistry,
                           ForceLogoutPort forceLogoutPort,
                           UserRepository userRepository,
                           AuthPort authPort,
                           VoucherClaimPort voucherClaimCache) {
        this.queryUseCase = queryUseCase;
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.voucherRepository = voucherRepository;
        this.arbitrateUseCase = arbitrateUseCase;
        this.platformConfig = platformConfig;
        this.adminUserQuery = adminUserQuery;
        this.adminArbitrationQuery = adminArbitrationQuery;
        this.wsSessionRegistry = wsSessionRegistry;
        this.forceLogoutPort = forceLogoutPort;
        this.userRepository = userRepository;
        this.authPort = authPort;
        this.voucherClaimCache = voucherClaimCache;
    }

    public record ArbitrateRequest(String favor) {
    }

    public record VoucherCreateRequest(Long thresholdCents, Long discountCents, Integer stock) {
    }

    public record VoucherStockRequest(Integer stock) {
    }

    public record VoucherStatusRequest(String status) {
    }

    public record PlatformConfigRequest(Double commissionRate, Double voucherRunnerBearRatio) {
    }

    public record UserStatusRequest(Boolean enabled) {
    }

    /** 启用/禁用普通账号：禁用时同时撤销其全部凭证并置离线 */
    @PostMapping("/users/{userId}/status")
    public Result<Void> updateUserStatus(@PathVariable String userId, @RequestBody UserStatusRequest req) {
        if (req == null || req.enabled() == null) {
            throw new BizException(ErrorCode.INVALID_STATUS, "enabled 必填");
        }
        UserAccount target = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "id=" + userId));
        if ("Admin".equals(target.role()) || userId.equals(CurrentUser.get())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLE_FORBIDDEN);
        }
        userRepository.updateStatus(userId, req.enabled());
        if (!req.enabled()) {
            // 禁用立即生效：删掉该用户所有 token，并挡住旧标记
            authPort.revokeAllSessions(userId);
            forceLogoutPort.force(userId);
            userRepository.markOffline(userId);
        } else {
            // 重新启用：允许重新登录
            forceLogoutPort.clear(userId);
        }
        return Result.ok(null);
    }

    /** 平台配置：佣金比例与优惠券抽成比例 */
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commissionRate", platformConfig.commissionRate());
        data.put("voucherRunnerBearRatio", platformConfig.voucherRunnerBearRatio());
        return Result.ok(data);
    }

    @PutMapping("/config")
    public Result<Map<String, Object>> updateConfig(@RequestBody PlatformConfigRequest req) {
        double commission = req == null || req.commissionRate() == null ? -1 : req.commissionRate();
        double voucher = req == null || req.voucherRunnerBearRatio() == null ? -1 : req.voucherRunnerBearRatio();
        if (commission < 0 || commission > 1 || voucher < 0 || voucher > 1) {
            throw new BizException(ErrorCode.INVALID_AMOUNT, "比例必须在 0 到 1 之间");
        }
        platformConfig.update(commission, voucher);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commissionRate", commission);
        data.put("voucherRunnerBearRatio", voucher);
        return Result.ok(data);
    }

    /** 强制下线：关闭该用户全部 WS 连接，并打上标记，重新登录前拒绝请求 */
    @PostMapping("/users/{userId}/force-logout")
    public Result<Void> forceLogout(@PathVariable String userId) {
        wsSessionRegistry.closeUser(userId);
        // 先撤销该用户全部已签发凭证，再打强制下线标记；成本只发生在这一个动作上
        authPort.revokeAllSessions(userId);
        forceLogoutPort.force(userId);
        userRepository.markOffline(userId);
        return Result.ok(null);
    }

    /** 用户列表：role=user，分页 20，带在线状态与余额 */
    @GetMapping("/users")
    public Result<Map<String, Object>> users(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.max(1, Math.min(size, 20));
        int safePage = Math.max(0, page);

        // 在线用户优先：先取全量（普通用户规模很小），按在线状态排序后再分页。
        // 若数据量变大，应改为维护 Redis 在线集合，再用 SQL CASE 排序 + LIMIT。
        var all = adminUserQuery.page(0, 10_000);
        List<Map<String, Object>> mapped = new java.util.ArrayList<>(all.items().stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("userId", row.userId());
                    item.put("username", row.username());
                    boolean online = authPort.isOnline(row.userId());
                    item.put("online", online);
                    item.put("enabled", row.enabled());
                    item.put("loginStatus", row.loginStatus());
                    item.put("lastSeenAt", row.lastSeenAt() == null ? "" : row.lastSeenAt().toString());
                    item.put("lastLoginAt", row.lastLoginAt() == null ? "" : row.lastLoginAt().toString());
                    item.put("balanceCents", row.balanceCents());
                    return item;
                })
                .toList());
        mapped.sort(java.util.Comparator.comparing((Map<String, Object> m) -> Boolean.TRUE.equals(m.get("online")))
                .reversed());
        int from = Math.min(safePage * safeSize, mapped.size());
        int to = Math.min(from + safeSize, mapped.size());
        List<Map<String, Object>> items = mapped.subList(from, to);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", all.total());
        data.put("page", safePage);
        data.put("size", safeSize);
        return Result.ok(data);
    }

    /** 优惠券管理：列表 */
    @GetMapping("/vouchers")
    public Result<List<Map<String, Object>>> vouchers() {
        return Result.ok(voucherRepository.findAllStocks().stream().map(this::voucherView).toList());
    }

    /** 优惠券管理：新增（type 自增，描述按“满XX元减XX元”生成） */
    @PostMapping("/vouchers")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> createVoucher(@RequestBody VoucherCreateRequest req) {
        long threshold = req == null || req.thresholdCents() == null ? 0 : req.thresholdCents();
        long discount = req == null || req.discountCents() == null ? 0 : req.discountCents();
        int stock = req == null || req.stock() == null ? 0 : req.stock();
        if (threshold <= 0 || discount <= 0 || discount >= threshold || stock < 0) {
            throw new BizException(ErrorCode.INVALID_AMOUNT, "满减金额/优惠价/库存不合法");
        }
        int type = voucherRepository.nextType();
        String description = "满" + moneyText(threshold) + "元减" + moneyText(discount) + "元";
        voucherRepository.insertStock(new VoucherStock(type, stock, "ONLINE", threshold, discount,
                description, Instant.now()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        return Result.ok(data);
    }

    /** 优惠券管理：修改库存 */
    @PutMapping("/vouchers/{type}/stock")
    public Result<Void> updateVoucherStock(@PathVariable int type, @RequestBody VoucherStockRequest req) {
        if (req == null || req.stock() == null || req.stock() < 0) {
            throw new BizException(ErrorCode.INVALID_AMOUNT, "库存不能为负");
        }
        if (voucherRepository.updateStock(type, req.stock()) == 0) {
            throw new BizException(ErrorCode.VOUCHER_INVALID, "type=" + type);
        }
        voucherClaimCache.syncStock(type, req.stock());
        return Result.ok(null);
    }

    /** 优惠券管理：上架 / 下架 */
    @PostMapping("/vouchers/{type}/status")
    public Result<Void> updateVoucherStatus(@PathVariable int type, @RequestBody VoucherStatusRequest req) {
        String status = req == null || req.status() == null ? "" : req.status().toUpperCase();
        if (!"ONLINE".equals(status) && !"OFFLINE".equals(status)) {
            throw new BizException(ErrorCode.INVALID_AMOUNT, "status 只能是 ONLINE / OFFLINE");
        }
        if (voucherRepository.updateStatus(type, status) == 0) {
            throw new BizException(ErrorCode.VOUCHER_INVALID, "type=" + type);
        }
        return Result.ok(null);
    }

    /** 优惠券管理：删除券类型与全部领取记录 */
    @DeleteMapping("/vouchers/{type}")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteVoucher(@PathVariable int type) {
        voucherRepository.deleteRecordsByType(type);
        if (voucherRepository.deleteStock(type) == 0) {
            throw new BizException(ErrorCode.VOUCHER_INVALID, "type=" + type);
        }
        return Result.ok(null);
    }

    private Map<String, Object> voucherView(VoucherStock stock) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", stock.type());
        item.put("stock", stock.stock());
        item.put("status", stock.status());
        item.put("thresholdCents", stock.thresholdCents());
        item.put("discountCents", stock.discountCents());
        item.put("description", stock.description());
        item.put("lastChangeTime", stock.lastChangeTime() == null ? "" : stock.lastChangeTime().toString());
        return item;
    }

    private static String moneyText(long cents) {
        return BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString();
    }

    /** 待仲裁列表：默认查 DISPUTED，最多 50 条 */
    @GetMapping("/disputes")
    public Result<List<Map<String, Object>>> disputes(@RequestParam(defaultValue = "1") long campusId) {
        List<Map<String, Object>> items = queryUseCase.list(campusId, "DISPUTED", 0, 50).stream()
                .map(this::toRow)
                .toList();
        return Result.ok(items);
    }

    /**
     * 平台账户流水（分账户、分页、分时间区间）。
     * account = COMMISSION | MARKETING；period = WEEK | MONTH | YEAR | ALL。
     */
    @GetMapping("/ledger")
    public Result<Map<String, Object>> ledger(@RequestParam(defaultValue = "COMMISSION") String account,
                                              @RequestParam(defaultValue = "ALL") String period,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "10") int size) {
        AccountType type;
        String ownerId;
        if ("MARKETING".equalsIgnoreCase(account)) {
            type = AccountType.MARKETING;
            ownerId = "-4";
        } else {
            type = AccountType.COMMISSION;
            ownerId = "-2";
        }

        Instant from = switch (period == null ? "ALL" : period.toUpperCase()) {
            case "WEEK" -> Instant.now().minus(7, ChronoUnit.DAYS);
            case "MONTH" -> Instant.now().minus(30, ChronoUnit.DAYS);
            case "YEAR" -> Instant.now().minus(365, ChronoUnit.DAYS);
            default -> null;
        };
        int safeSize = Math.max(1, Math.min(size, 50));
        int safePage = Math.max(0, page);
        int offset = safePage * safeSize;

        var pageData = walletRepository.queryPlatformLedger(type, from, offset, safeSize);
        var accountRow = walletRepository.findByOwner(ownerId, type).orElse(null);

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> accountInfo = new LinkedHashMap<>();
        accountInfo.put("type", type.name());
        accountInfo.put("ownerId", ownerId);
        accountInfo.put("name", accountRow == null ? "Admin" : accountRow.userName());
        accountInfo.put("balanceCents", accountRow == null ? 0L : accountRow.available().cents());
        data.put("account", accountInfo);
        data.put("period", period == null ? "ALL" : period.toUpperCase());
        data.put("page", safePage);
        data.put("size", safeSize);
        data.put("total", pageData.total());
        data.put("summary", Map.of(
                "incomeCents", pageData.incomeCents(),
                "expenseCents", pageData.expenseCents()));
        data.put("netCents", walletRepository.netPlatformIncomeCents());
        data.put("items", pageData.items().stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", String.valueOf(row.id()));
                    item.put("bizNo", row.bizNo());
                    item.put("accountName", row.accountName());
                    item.put("accountType", row.accountType());
                    item.put("direction", row.direction());
                    item.put("amountCents", row.amount());
                    item.put("balanceAfterCents", row.balanceAfter());
                    item.put("refType", row.refType());
                    item.put("refId", String.valueOf(row.refId()));
                    item.put("createdAt", row.createdAt().toString());
                    return item;
                })
                .toList());
        return Result.ok(data);
    }

    /** 仲裁记录：fund_audit_log 里的 ARBITRATE 流水，按时间倒序 */
    @GetMapping("/arbitrations")
    public Result<Map<String, Object>> arbitrations(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "10") int size) {
        int safeSize = Math.max(1, Math.min(size, 50));
        int safePage = Math.max(0, page);
        var result = adminArbitrationQuery.page(safePage * safeSize, safeSize);
        List<Map<String, Object>> items = result.items().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(row.id()));
            item.put("errandId", String.valueOf(row.errandId()));
            item.put("errandTitle", row.errandTitle());
            item.put("operatorName", row.operatorName());
            item.put("favor", row.favor());
            item.put("amountCents", row.amountCents());
            item.put("result", row.result());
            item.put("createdAt", row.createdAt().toString());
            return item;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", result.total());
        data.put("page", safePage);
        data.put("size", safeSize);
        return Result.ok(data);
    }

    @GetMapping("/disputes/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        Errand errand = errandRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + id));
        Map<String, Object> data = toRow(errand);
        walletRepository.findEscrowByErrandId(errand.campusId(), id).ifPresent(escrow -> {
            data.put("escrowAmountCents", escrow.amount().cents());
            data.put("escrowStatus", escrow.status().name());
        });
        voucherRepository.findUseByErrand(id).ifPresent(voucher -> {
            data.put("voucherDescription", VoucherType.of(voucher.type())
                    .map(VoucherType::description).orElse(""));
            data.put("voucherDiscountCents", voucher.discountCents());
            data.put("runnerBearCents", voucher.runnerBearCents());
        });
        // 供仲裁员判断双方诉求的原始描述
        data.put("rewardCents", errand.reward().cents());
        return Result.ok(data);
    }

    @PostMapping("/disputes/{id}/arbitrate")
    public Result<Map<String, Object>> arbitrate(@PathVariable long id, @RequestBody ArbitrateRequest req) {
        ArbitrateErrandUseCase.Favor favor;
        try {
            favor = ArbitrateErrandUseCase.Favor.valueOf(req == null ? "" : String.valueOf(req.favor()));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_AMOUNT, "favor 只能是 RUNNER 或 PUBLISHER");
        }
        ArbitrateErrandUseCase.Result result =
                arbitrateUseCase.arbitrate(id, favor, CurrentUser.get());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", result.name());
        return Result.ok(data);
    }

    private Map<String, Object> toRow(Errand errand) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("errandId", String.valueOf(errand.id()));
        row.put("title", errand.title());
        row.put("status", errand.status().name());
        row.put("rewardCents", errand.reward().cents());
        row.put("publisherId", errand.publisherId());
        row.put("publisherName", userRepository.findById(errand.publisherId())
                .map(com.campusdash.domain.auth.model.UserAccount::username).orElse(errand.publisherId()));
        row.put("grabberId", errand.grabberId() == null ? "" : errand.grabberId());
        row.put("grabberName", errand.grabberId() == null ? "" : userRepository.findById(errand.grabberId())
                .map(com.campusdash.domain.auth.model.UserAccount::username).orElse(errand.grabberId()));
        row.put("round", errand.round());
        return row;
    }
}
