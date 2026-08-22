package com.campusdash.application.usecase.query;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 计算"当前请求者对这个任务能做什么"（availableActions）。
 *
 * ── 为什么要这个东西 ──
 * 状态机（ErrandStatus.allowedTargets + 聚合方法里的身份校验）是本项目核心资产。
 * 如果前端按"status === 'DELIVERED' && isPublisher"自己渲染按钮，
 * 就是状态机的第二份副本——状态一改、前端漏改，两边必然漂移。
 * 所以前端只负责"后端给了哪个 action 就渲染哪个按钮"，判断逻辑集中在这里。
 *
 * ── 如何不产生第二份状态机 ──
 * 本类不重写"哪些状态能流转"，而是复用领域层的 allowedTargets() 做候选过滤，
 * 只在其上叠加"谁有资格"的身份判断。流转规则变了，这里自动跟着变。
 */
@Component
public class ErrandActionResolver {

    /** 平台仲裁员的固定用户 id，由配置给定。真实系统里是角色系统，本项目简化 */
    private final long arbitratorId;

    public ErrandActionResolver(@Value("${dash.auth.arbitrator-id:9001}") long arbitratorId) {
        this.arbitratorId = arbitratorId;
    }

    public record Resolved(List<String> actions, String role) {}

    public Resolved resolve(Errand errand, long viewerId) {
        ErrandStatus status = errand.status();
        List<String> actions = new ArrayList<>();

        boolean isPublisher = errand.publisherId() == viewerId;
        boolean isCurrentRunner = errand.grabberId() != null && errand.grabberId() == viewerId;
        boolean isArbitrator = viewerId == arbitratorId;

        // 抢单：任何非发单人的跑腿都可以（发单人不能抢自己的任务）
        if (status.grabbable() && !isPublisher) {
            actions.add("GRAB");
        }
        // 确认接单：只有当前抢中者
        if (status == ErrandStatus.LOCKED && isCurrentRunner) {
            actions.add("CONFIRM");
        }
        if (status == ErrandStatus.ACCEPTED && isCurrentRunner) {
            actions.add("PICKUP");
        }
        if (status == ErrandStatus.PICKED_UP && isCurrentRunner) {
            actions.add("DELIVER");
        }
        // 确认完成：只有发单人（自动结算由系统触发，不是用户动作）
        if (status == ErrandStatus.DELIVERED && isPublisher
                && status.canTransitTo(ErrandStatus.SETTLED)) {
            actions.add("SETTLE");
        }
        // 取消：发单人，仅限没人接单的早期状态。
        // 注意这里比状态机更严：canTransitTo 允许 LOCKED->CANCELLED（P1 定义），
        // 但领域聚合 cancelByPublisher 要求 DRAFT/PUBLISHED——已有人抢中就不可无偿取消。
        // 两处规则不一致时 Resolver 必须跟随更严的领域规则，否则前端给了按钮、
        // 点下去却被业务层拒绝（ApiEndpointIT 的 LOCKED 状态期望就是被这个坑揪出来的）
        if (isPublisher && (status == ErrandStatus.DRAFT || status == ErrandStatus.PUBLISHED)) {
            actions.add("CANCEL");
        }
        // 争议：双方均可，状态机允许争议流转时
        if (isPublisher || isCurrentRunner) {
            if (status.canTransitTo(ErrandStatus.DISPUTED)) {
                actions.add("DISPUTE");
            }
        }
        // 仲裁：只有仲裁员，仅在 DISPUTED
        if (isArbitrator && status == ErrandStatus.DISPUTED) {
            actions.add("ARBITRATE");
        }

        String role = isArbitrator ? "ARBITRATOR"
                : isPublisher ? "PUBLISHER"
                : isCurrentRunner ? "CURRENT_RUNNER"
                : "VIEWER";
        return new Resolved(actions, role);
    }
}
