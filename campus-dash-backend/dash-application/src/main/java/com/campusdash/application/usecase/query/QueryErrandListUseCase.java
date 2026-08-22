package com.campusdash.application.usecase.query;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandQueryPort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 任务广场列表查询。
 *
 * 不经过聚合根、不碰状态机——读路径与写路径物理分开（读写分离的最小形态）。
 * 默认只查 PUBLISHED：广场展示的是"可抢的任务"，其他状态的任务属于
 * "我的任务"视角，混在一起展示会让跑腿看到一堆无法操作的任务。
 */
@Service
public class QueryErrandListUseCase {

    private final ErrandQueryPort queryPort;
    private final ErrandRepository errandRepository;

    public QueryErrandListUseCase(ErrandQueryPort queryPort, ErrandRepository errandRepository) {
        this.queryPort = queryPort;
        this.errandRepository = errandRepository;
    }

    public List<Errand> list(long campusId, String status, int page, int size) {
        String normalized = status == null || status.isBlank()
                ? ErrandStatus.PUBLISHED.name()
                : ErrandStatus.valueOf(status.toUpperCase()).name();
        return queryPort.list(campusId, normalized, Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }

    public CursorPage<Errand> listByCursor(long campusId, String status, String cursor, int size) {
        String normalized = status == null || status.isBlank()
                ? ErrandStatus.PUBLISHED.name()
                : ErrandStatus.valueOf(status.toUpperCase()).name();
        int normalizedSize = Math.min(Math.max(size, 1), 50);
        Cursor parsed = decode(cursor);
        List<ErrandQueryPort.CursorItem> rows = queryPort.listByCursor(
                campusId, normalized, parsed.createdAt(), parsed.id(), normalizedSize + 1);
        boolean hasNext = rows.size() > normalizedSize;
        List<ErrandQueryPort.CursorItem> pageRows = hasNext ? rows.subList(0, normalizedSize) : rows;
        String nextCursor = null;
        if (hasNext && !pageRows.isEmpty()) {
            var last = pageRows.get(pageRows.size() - 1);
            nextCursor = encode(last.createdAt(), last.errand().id());
        }
        return new CursorPage<>(pageRows.stream().map(ErrandQueryPort.CursorItem::errand).toList(), nextCursor);
    }

    public List<Errand> myPublished(long publisherId, int page, int size) {
        return queryPort.listByPublisher(publisherId, Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }

    public List<Errand> myGrabbed(long runnerId, int page, int size) {
        return queryPort.listByRunner(runnerId, Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }

    public List<ErrandQueryPort.StatusChange> statusLog(long errandId) {
        Errand errand = errandRepository.findById(errandId)
                .orElseThrow(() -> new BizException(ErrorCode.ERRAND_NOT_FOUND, "id=" + errandId));
        return queryPort.statusLog(errand.campusId(), errandId);
    }

    private Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, null);
        }
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String[] parts = raw.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("非法游标");
        }
        return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), Long.parseLong(parts[1]));
    }

    private String encode(Instant createdAt, long id) {
        String raw = createdAt.toEpochMilli() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(Instant createdAt, Long id) {}
}
