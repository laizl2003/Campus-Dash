package com.campusdash.bench;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 压测/测试轮次登记器。
 *
 * 存在的理由：P1 阶段所有轮次的数据混在一起，verify SQL 一次列出 31 行任务（含预热），
 * 轮次多了报告就没法读，清理也只能全表 DELETE。
 * 有了 run_id 之后，每轮数据可以精确查询、跨轮对比、按轮清理，
 * 于是"改动前后同档位对比"才真正可行。
 *
 * 刻意用纯 JDBC 而不依赖 Spring：压测客户端要能独立运行，
 * 集成测试也能以 test scope 复用同一份实现，避免两处重复。
 */
public class BenchRunRecorder implements AutoCloseable {

    public static final String DEFAULT_JDBC_URL =
            "jdbc:mysql://127.0.0.1:3307/campus_dash?useSSL=false&serverTimezone=Asia/Shanghai";
    public static final String DEFAULT_USER = "root";
    public static final String DEFAULT_PASSWORD = "dash123456";

    private static final DateTimeFormatter RUN_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Connection conn;

    public BenchRunRecorder() {
        this(DEFAULT_JDBC_URL, DEFAULT_USER, DEFAULT_PASSWORD);
    }

    public BenchRunRecorder(String url, String user, String password) {
        try {
            this.conn = DriverManager.getConnection(url, user, password);
        } catch (SQLException e) {
            throw new IllegalStateException("无法连接数据库，请确认 docker compose 已启动: " + url, e);
        }
    }

    /** 开始一轮，返回 run_id。同一秒内重复开始会自动加后缀避免主键冲突 */
    public String startRun(String kind, String scenario, Integer concurrency, String envNote) {
        String base = LocalDateTime.now().format(RUN_ID_FMT);
        String runId = base;
        for (int suffix = 1; exists(runId); suffix++) {
            runId = base + "-" + suffix;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO bench_run (run_id, kind, scenario, started_at, status, concurrency, env_note)
                VALUES (?, ?, ?, NOW(3), 'RUNNING', ?, ?)
                """)) {
            ps.setString(1, runId);
            ps.setString(2, kind);
            ps.setString(3, scenario);
            if (concurrency == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, concurrency);
            }
            ps.setString(5, envNote);
            ps.executeUpdate();
            return runId;
        } catch (SQLException e) {
            throw new IllegalStateException("登记压测轮次失败", e);
        }
    }

    private boolean exists(String runId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM bench_run WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询轮次失败", e);
        }
    }

    /** 登记本轮产生的任务，清理脚本据此精确删除，不会误伤 seed 数据 */
    public void trackErrand(String runId, long errandId) {
        trackErrands(runId, List.of(errandId));
    }

    public void trackErrands(String runId, List<Long> errandIds) {
        if (errandIds.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO bench_run_item (run_id, entity_type, entity_id) VALUES (?, 'ERRAND', ?)")) {
            for (Long id : errandIds) {
                ps.setString(1, runId);
                ps.setLong(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("登记轮次数据失败", e);
        }
    }

    /** 收尾并写入结果摘要，摘要是跨轮对比的依据 */
    public void finishRun(String runId, String status, String summaryJson) {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE bench_run SET finished_at = NOW(3), status = ?, summary = CAST(? AS JSON)
                 WHERE run_id = ?
                """)) {
            ps.setString(1, status);
            ps.setString(2, summaryJson);
            ps.setString(3, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("回填轮次结果失败", e);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // 关闭失败不影响压测结论
        }
    }
}
