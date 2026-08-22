package com.campusdash.presentation;

import com.campusdash.application.usecase.query.BloomRebuildUseCase;
import com.campusdash.application.usecase.query.CacheConsistencyCheckUseCase;
import com.campusdash.application.usecase.query.GetErrandDetailUseCase;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 内部观测端点：压测取数与运维动作用。
 *
 * 安全边界：这些端点在 WebConfig 里被排除鉴权，仅供本机/内网压测与运维。
 * 生产部署必须由网关层屏蔽 /api/internal/** 的外部访问——
 * 代码注释留痕，部署清单里也要有这一条。
 */
@RestController
@RequestMapping("/api/internal")
public class InternalController {

    private final GetErrandDetailUseCase detailUseCase;
    private final CacheConsistencyCheckUseCase checkUseCase;
    private final BloomRebuildUseCase bloomRebuildUseCase;

    public InternalController(GetErrandDetailUseCase detailUseCase,
                              CacheConsistencyCheckUseCase checkUseCase,
                              BloomRebuildUseCase bloomRebuildUseCase) {
        this.detailUseCase = detailUseCase;
        this.checkUseCase = checkUseCase;
        this.bloomRebuildUseCase = bloomRebuildUseCase;
    }

    /** 缓存统计：S3 压测的命中率取这里 */
    @GetMapping("/cache-stats")
    public Result<Map<String, Object>> cacheStats() {
        return Result.ok(Map.of(
                "requests", detailUseCase.requestCount(),
                "cacheHits", detailUseCase.cacheHitCount(),
                "dbLoads", detailUseCase.dbLoadCount(),
                "hitRate", detailUseCase.hitRate()));
    }

    @PostMapping("/cache-stats/reset")
    public Result<Void> resetStats() {
        detailUseCase.resetStats();
        return Result.ok(null);
    }

    /** 手动触发一轮一致性校验，返回差异数 */
    @PostMapping("/cache-check")
    public Result<Map<String, Object>> cacheCheck() {
        return Result.ok(Map.of("diffs", checkUseCase.runOnce()));
    }

    /** 手动重建布隆（批量造数后调用） */
    @PostMapping("/bloom-rebuild")
    public Result<Map<String, Object>> bloomRebuild() {
        return Result.ok(Map.of("registered", bloomRebuildUseCase.rebuild()));
    }
}
