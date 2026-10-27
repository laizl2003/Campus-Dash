package com.campusdash.presentation;

import com.campusdash.application.usecase.ArbitrateErrandUseCase;
import com.campusdash.application.usecase.query.QueryErrandListUseCase;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.voucher.model.VoucherType;
import com.campusdash.domain.voucher.model.VoucherUse;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.model.EscrowOrder;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    public AdminController(QueryErrandListUseCase queryUseCase,
                           ErrandRepository errandRepository,
                           WalletRepository walletRepository,
                           VoucherRepository voucherRepository,
                           ArbitrateErrandUseCase arbitrateUseCase) {
        this.queryUseCase = queryUseCase;
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.voucherRepository = voucherRepository;
        this.arbitrateUseCase = arbitrateUseCase;
    }

    public record ArbitrateRequest(String favor) {
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
        row.put("grabberId", errand.grabberId() == null ? "" : errand.grabberId());
        row.put("round", errand.round());
        return row;
    }
}
