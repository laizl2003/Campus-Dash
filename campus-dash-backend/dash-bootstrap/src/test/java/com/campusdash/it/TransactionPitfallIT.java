package com.campusdash.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @Transactional 六种失效场景 + 连接池死锁，全部做成可执行断言。
 *
 * 每个陷阱都是"错误写法不回滚 / 正确写法回滚"成对验证。
 * 光写文档说"注意自调用会失效"没有约束力，写成测试才能在有人改坏时报警。
 */
@SpringBootTest(properties = "dash.mq.enabled=false")
class TransactionPitfallIT {

    @Autowired PitfallService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void prepareTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pitfall_probe (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tag VARCHAR(64) NOT NULL,
                  PRIMARY KEY (id), KEY idx_tag (tag)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        // 故意用 MyISAM：陷阱 6 要证明"引擎不支持事务时注解无效"
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pitfall_myisam (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tag VARCHAR(64) NOT NULL,
                  PRIMARY KEY (id), KEY idx_tag (tag)
                ) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4
                """);
        jdbc.update("DELETE FROM pitfall_probe");
        jdbc.update("DELETE FROM pitfall_myisam");
    }

    private int count(String tag) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pitfall_probe WHERE tag = ?", Integer.class, tag);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("陷阱1 同类自调用：错误写法不回滚，走代理才回滚")
    void self_invocation() {
        // 错误：this.txMethod() 绕过代理
        assertThrows(IllegalStateException.class, () -> service.selfInvokeWrong("t1-wrong"));
        assertEquals(1, count("t1-wrong"), "自调用应导致事务失效，数据残留");

        // 正确：从容器拿到的代理对象直接调
        assertThrows(IllegalStateException.class, () -> service.txInsertThenFail("t1-right"));
        assertEquals(0, count("t1-right"), "走代理应正常回滚");
    }

    @Test
    @DisplayName("陷阱2 非 public 方法：@Transactional 静默失效")
    void non_public_method() {
        assertThrows(IllegalStateException.class, () -> service.nonPublicWrong("t2-wrong"));
        assertEquals(1, count("t2-wrong"), "protected 方法上的事务注解不生效");
    }

    @Test
    @DisplayName("陷阱3 异常被吞：不回滚；setRollbackOnly 才回滚")
    void swallowed_exception() {
        service.swallowExceptionWrong("t3-wrong");   // 不抛出，所以不用 assertThrows
        assertEquals(1, count("t3-wrong"), "异常被 catch 掉，代理感知不到，事务提交");

        service.swallowExceptionRight("t3-right");
        assertEquals(0, count("t3-right"), "手动 setRollbackOnly 后应回滚");
    }

    @Test
    @DisplayName("陷阱4 checked 异常：不写 rollbackFor 不回滚")
    void checked_exception_without_rollback_for() {
        assertThrows(Exception.class, () -> service.checkedExceptionWrong("t4-wrong"));
        assertEquals(1, count("t4-wrong"), "Spring 默认只对 RuntimeException 回滚");

        assertThrows(Exception.class, () -> service.checkedExceptionRight("t4-right"));
        assertEquals(0, count("t4-right"), "声明 rollbackFor=Exception.class 后回滚");
    }

    @Test
    @DisplayName("陷阱5 手动 new 对象：没有代理，注解无效")
    void manual_new_bypasses_proxy() {
        ManualNewService bare = new ManualNewService(jdbc);
        assertThrows(IllegalStateException.class, () -> bare.insertThenFail("t5-wrong"));
        assertEquals(1, count("t5-wrong"), "手动 new 的对象不被代理，事务注解是废纸");
    }

    @Test
    @DisplayName("陷阱6 MyISAM 引擎：事务写得再对也回滚不了")
    void myisam_engine_ignores_transaction() {
        assertThrows(IllegalStateException.class, () -> service.myisamWrong("t6-wrong"));
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pitfall_myisam WHERE tag = ?", Integer.class, "t6-wrong");
        assertEquals(1, n, "MyISAM 不支持事务，注解无能为力");

        // 顺带证明表引擎确实是 MyISAM，避免"以为建成 MyISAM 其实是 InnoDB"的假验证
        String engine = jdbc.queryForObject("""
                SELECT ENGINE FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pitfall_myisam'
                """, String.class);
        assertEquals("MyISAM", engine);
    }

    /**
     * REQUIRES_NEW 的真实代价：它要额外占一个数据库连接，而主事务的连接不释放。
     *
     * 池子设为 2、并发 4 个"主事务 + 内层 REQUIRES_NEW"，必然有线程拿不到第二个连接。
     * 这就是生产上"加了个审计日志用 REQUIRES_NEW，压测直接卡死"的原因。
     *
     * 正确配比：池大小 >= (1 + REQUIRES_NEW 嵌套层数) × 预期并发。
     * 本项目主库池按 20 配，只有一层 REQUIRES_NEW（资金审计），并发上限 10 时安全。
     */
    @Test
    @DisplayName("连接池死锁：池=2 时并发 4 个 REQUIRES_NEW 必有线程拿不到连接")
    void requires_new_exhausts_small_pool() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl("jdbc:mysql://127.0.0.1:3307/campus_dash?useSSL=false&serverTimezone=Asia/Shanghai");
            cfg.setUsername("root");
            cfg.setPassword("dash123456");
            cfg.setMaximumPoolSize(2);          // 刻意调小
            cfg.setConnectionTimeout(2000);     // 2s 拿不到就抛异常
            cfg.setPoolName("pitfall-tiny-pool");

            try (HikariDataSource tiny = new HikariDataSource(cfg)) {
                JdbcTemplate tinyJdbc = new JdbcTemplate(tiny);
                TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(tiny));
                TransactionTemplate inner = new TransactionTemplate(new DataSourceTransactionManager(tiny));
                inner.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

                int concurrency = 4;
                ExecutorService pool = Executors.newFixedThreadPool(concurrency);
                CountDownLatch ready = new CountDownLatch(concurrency);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger success = new AtomicInteger();
                AtomicInteger exhausted = new AtomicInteger();

                for (int i = 0; i < concurrency; i++) {
                    final int idx = i;
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            start.await();
                            outer.execute(status -> {
                                tinyJdbc.update("INSERT INTO pitfall_probe (tag) VALUES (?)", "pool-" + idx);
                                // 主事务握着连接不放，这里再去借第二个
                                sleepQuietly(300);
                                inner.execute(s2 -> {
                                    tinyJdbc.update("INSERT INTO pitfall_probe (tag) VALUES (?)", "pool-inner-" + idx);
                                    return null;
                                });
                                return null;
                            });
                            success.incrementAndGet();
                        } catch (Exception e) {
                            // 期待的就是这个：SQLTransientConnectionException（拿不到连接）
                            if (rootCauseMentionsPool(e)) {
                                exhausted.incrementAndGet();
                            }
                        }
                        return null;
                    });
                }
                ready.await();
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(12, TimeUnit.SECONDS), "线程池应在超时前收敛");

                System.out.printf("[连接池死锁] 池=2 并发=%d 成功=%d 连接耗尽=%d%n",
                        concurrency, success.get(), exhausted.get());
                assertTrue(exhausted.get() > 0,
                        "池=2 并发=4 的 REQUIRES_NEW 应当压出连接耗尽；实际成功=" + success.get());
            }
        });
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean rootCauseMentionsPool(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String n = t.getClass().getName();
            String m = String.valueOf(t.getMessage());
            if (n.contains("SQLTransientConnectionException")
                    || m.contains("Connection is not available")
                    || m.contains("request timed out")) {
                return true;
            }
        }
        return false;
    }
}
