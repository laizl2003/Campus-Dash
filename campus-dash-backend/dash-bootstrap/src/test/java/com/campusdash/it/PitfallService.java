package com.campusdash.it;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务陷阱的被测服务。
 *
 * 每个陷阱都提供"错误写法"与"正确写法"一对方法，让 TransactionPitfallIT
 * 能断言"错误写法确实不回滚、正确写法确实回滚"。
 * 只会通过的测试没有价值——必须证明错误写法真的会错。
 *
 * 落数据用 pitfall_probe 表，与业务表完全隔离。
 */
@Service
public class PitfallService {

    private final JdbcTemplate jdbc;

    public PitfallService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void insert(String tag) {
        jdbc.update("INSERT INTO pitfall_probe (tag) VALUES (?)", tag);
    }

    // ── 陷阱 1：同类自调用 ──────────────────────────────────────────
    // 外层方法没有事务，内部 this.xxx() 调用绕过 Spring AOP 代理，
    // 被调方法上的 @Transactional 完全失效。
    // 这是 P1/P2 都刻意避开、P2 又一度重犯的坑。

    public void selfInvokeWrong(String tag) {
        this.txInsertThenFail(tag);   // 走的是原始对象，不是代理
    }

    @Transactional(rollbackFor = Exception.class)
    public void txInsertThenFail(String tag) {
        insert(tag);
        throw new IllegalStateException("故意失败");
    }

    // ── 陷阱 2：非 public 方法 ──────────────────────────────────────
    // Spring 的 AOP 代理（无论 JDK 动态代理还是 CGLIB）只能拦截 public 方法，
    // protected/private 上的 @Transactional 不生效且不会有任何报错提示。

    public void nonPublicWrong(String tag) {
        protectedTx(tag);
    }

    @Transactional(rollbackFor = Exception.class)
    protected void protectedTx(String tag) {
        insert(tag);
        throw new IllegalStateException("故意失败");
    }

    // ── 陷阱 3：异常被自己吞掉 ──────────────────────────────────────
    // 事务回滚的触发条件是"异常抛出方法边界被代理捕获"。
    // 自己 catch 掉不再抛，代理什么都感知不到，事务正常提交。

    @Transactional(rollbackFor = Exception.class)
    public void swallowExceptionWrong(String tag) {
        insert(tag);
        try {
            throw new IllegalStateException("故意失败");
        } catch (RuntimeException e) {
            // 吞掉异常，事务照常提交——很多"明明抛异常了怎么没回滚"就是这里
        }
    }

    /** 正确写法：要么别 catch，要么 catch 后手动标记回滚 */
    @Transactional(rollbackFor = Exception.class)
    public void swallowExceptionRight(String tag) {
        insert(tag);
        try {
            throw new IllegalStateException("故意失败");
        } catch (RuntimeException e) {
            org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().setRollbackOnly();
        }
    }

    // ── 陷阱 4：checked 异常未声明 rollbackFor ──────────────────────
    // Spring 默认只对 RuntimeException 与 Error 回滚。
    // 抛 checked 异常而注解没写 rollbackFor 时，事务会正常提交。

    @Transactional
    public void checkedExceptionWrong(String tag) throws Exception {
        insert(tag);
        throw new Exception("checked 异常，默认不触发回滚");
    }

    @Transactional(rollbackFor = Exception.class)
    public void checkedExceptionRight(String tag) throws Exception {
        insert(tag);
        throw new Exception("checked 异常，但声明了 rollbackFor");
    }

    // ── 陷阱 6：MyISAM 引擎 ────────────────────────────────────────
    // 事务注解写得再对，存储引擎不支持事务也白搭。

    @Transactional(rollbackFor = Exception.class)
    public void myisamWrong(String tag) {
        jdbc.update("INSERT INTO pitfall_myisam (tag) VALUES (?)", tag);
        throw new IllegalStateException("故意失败");
    }

    // ── 连接池死锁：REQUIRED 内嵌 REQUIRES_NEW ──────────────────────
    // 主事务持有连接不释放，内层 REQUIRES_NEW 要再借一个。
    // 池子小于"并发数 ×（1+嵌套层数）"时，双方互等就死锁。

    @Transactional(rollbackFor = Exception.class)
    public void outerHoldsConnection(String tag, long holdMillis) {
        insert(tag);
        sleep(holdMillis);
        innerRequiresNew(tag);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void innerRequiresNew(String tag) {
        insert(tag + "-inner");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
