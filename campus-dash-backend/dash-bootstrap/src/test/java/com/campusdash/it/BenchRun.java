package com.campusdash.it;

import com.campusdash.bench.BenchRunRecorder;

import java.util.ArrayList;
import java.util.List;

/**
 * 集成测试的轮次登记助手。
 *
 * P1 时测试数据全混在一起，跑几轮之后 verify SQL 就没法读了，也无法做前后对比。
 * 现在每个测试类开一个 run，产生的任务登记到 bench_run_item，
 * 于是数据可以保留下来做回归对比，也能被 cleanup.sh 精确清掉。
 *
 * 注意：这里不再删除测试数据（P1 的 OversellControlExperimentIT 会 DELETE errand），
 * 数据保留交给 cleanup.sh 按 --keep-last 策略统一管理。
 */
final class BenchRun implements AutoCloseable {

    private final BenchRunRecorder recorder;
    private final String runId;
    private final List<Long> errandIds = new ArrayList<>();

    private BenchRun(BenchRunRecorder recorder, String runId) {
        this.recorder = recorder;
        this.runId = runId;
    }

    static BenchRun start(String scenario) {
        BenchRunRecorder recorder = new BenchRunRecorder();
        String runId = recorder.startRun("IT", scenario, null, "mvn test 集成测试，应用与中间件同机");
        return new BenchRun(recorder, runId);
    }

    String runId() {
        return runId;
    }

    void track(long errandId) {
        errandIds.add(errandId);
    }

    void finish(String status, String summaryJson) {
        recorder.trackErrands(runId, errandIds);
        recorder.finishRun(runId, status, summaryJson);
    }

    @Override
    public void close() {
        recorder.close();
    }
}
