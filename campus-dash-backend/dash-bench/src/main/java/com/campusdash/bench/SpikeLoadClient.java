package com.campusdash.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S1 尖峰抢单压测客户端。
 *
 * 为什么自研而不用 JMeter：
 *   1. 本机没装 JMeter/wrk，自研客户端零外部依赖，任何人 clone 下来就能跑
 *   2. CountDownLatch 对齐释放比 JMeter 的 Synchronizing Timer 更可控，
 *      能保证 N 个请求真正在同一时刻发出
 *   3. 压完能直接连数据库跑校验 SQL，不需要在两个工具间来回切
 * 阶梯加压出 QPS 曲线（S2）后续用 Docker 版 JMeter，那才是它的强项。
 *
 * 用法：
 *   java -cp ... SpikeLoadClient [baseUrl] [concurrency] [slotTotal]
 */
public class SpikeLoadClient {

    private static final String DEFAULT_BASE = "http://127.0.0.1:8080";
    private static final long PUBLISHER_ID = 1001L;

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE;
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        int slotTotal = args.length > 2 ? Integer.parseInt(args[2]) : 1;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        // 登记本轮，产生的任务都挂到这个 run_id 上：
        // 数据得以保留用于改动前后对比，也能被 cleanup.sh 精确清理
        BenchRunRecorder recorder = new BenchRunRecorder();
        String runId = recorder.startRun("BENCH", "S1", concurrency,
                "应用与中间件、发压端同机，存在资源争抢；数字仅作基线");
        List<Long> trackedErrands = new ArrayList<>();
        System.out.println("[run] runId=" + runId);

        // 预热：让 JIT 编译热点方法、连接池填满。不预热的话前 30 秒数据惨不忍睹，
        // 但那不是应用的真实性能。
        System.out.println("[warmup] 20 并发预热 30 轮...");
        warmup(client, baseUrl, trackedErrands);

        long errandId = publish(client, baseUrl, slotTotal);
        trackedErrands.add(errandId);
        System.out.printf("[setup] 已发布任务 errandId=%d slotTotal=%d%n", errandId, slotTotal);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger slotFull = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();
        // 错误必须分类统计：不区分类型就无法判断瓶颈在发压端还是被压端
        java.util.Map<String, AtomicInteger> errorTypes = new java.util.concurrent.ConcurrentHashMap<>();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(concurrency));

        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                long runnerId = 2001L + i;
                pool.submit(() -> {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/errands/" + errandId + "/grab"))
                            .header("X-User-Id", String.valueOf(runnerId))
                            .header("X-Request-Id", UUID.randomUUID().toString())
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
                    ready.countDown();
                    try {
                        fire.await();   // 所有线程在这里对齐，开闸即瞬时并发
                        long t0 = System.nanoTime();
                        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        latencies.add((System.nanoTime() - t0) / 1_000_000);
                        switch (classifyResponse(resp.statusCode(), resp.body())) {
                            case SUCCESS -> success.incrementAndGet();
                            case SLOT_FULL -> slotFull.incrementAndGet();
                            case GRAB_CONFLICT -> conflict.incrementAndGet();
                            case RATE_LIMITED -> rateLimited.incrementAndGet();
                            case ERROR -> error.incrementAndGet();
                        }
                    } catch (Exception e) {
                        error.incrementAndGet();
                        String type = e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : ": " + e.getMessage());
                        errorTypes.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            if (!ready.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("线程未在 60s 内就绪");
            }
            long start = System.currentTimeMillis();
            fire.countDown();
            if (!done.await(180, TimeUnit.SECONDS)) {
                throw new IllegalStateException("压测未在 180s 内完成");
            }
            long elapsed = System.currentTimeMillis() - start;

            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            System.out.println("================ S1 尖峰抢单结果 ================");
            System.out.printf("并发数        : %d%n", concurrency);
            System.out.printf("名额总数      : %d%n", slotTotal);
            System.out.printf("成功          : %d  <- 必须等于名额数%n", success.get());
            System.out.printf("名额已满      : %d%n", slotFull.get());
            System.out.printf("并发冲突      : %d%n", conflict.get());
            System.out.printf("热点限流      : %d%n", rateLimited.get());
            System.out.printf("错误          : %d%n", error.get());
            if (!errorTypes.isEmpty()) {
                System.out.println("错误分类      :");
                errorTypes.entrySet().stream()
                        .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                        .limit(5)
                        .forEach(en -> System.out.printf("                %4d x %s%n", en.getValue().get(), en.getKey()));
            }
            System.out.printf("总耗时        : %d ms%n", elapsed);
            System.out.printf("吞吐(参考)    : %.0f req/s%n", concurrency * 1000.0 / Math.max(elapsed, 1));
            if (!sorted.isEmpty()) {
                System.out.printf("延迟 P50/P95/P99/Max : %d / %d / %d / %d ms%n",
                        pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), sorted.get(sorted.size() - 1));
            }
            System.out.println("errandId=" + errandId);
            System.out.println("runId=" + runId + "（校验：RUN_ID=" + runId + " bench/scripts/verify_run.sql）");
            System.out.println("=================================================");

            boolean pass = success.get() == slotTotal;
            long p99 = sorted.isEmpty() ? -1 : pct(sorted, 99);
            long throughput = Math.round(concurrency * 1000.0 / Math.max(elapsed, 1));
            String summary = String.format(
                    "{\"success\":%d,\"slotTotal\":%d,\"slotFull\":%d,\"conflict\":%d,\"errors\":%d,"
                            + "\"rateLimited\":%d,\"elapsedMs\":%d,\"throughput\":%d,\"p99Ms\":%d,\"oversold\":%d}",
                    success.get(), slotTotal, slotFull.get(), conflict.get(), error.get(),
                    rateLimited.get(),
                    elapsed, throughput, p99, Math.max(0, success.get() - slotTotal));
            recorder.trackErrands(runId, trackedErrands);
            recorder.finishRun(runId, pass ? "PASS" : "FAIL", summary);
            recorder.close();

            if (!pass) {
                System.err.printf("[FAIL] 成功数 %d != 名额数 %d，发生超卖或少卖%n", success.get(), slotTotal);
                System.exit(1);
            }
            System.out.println("[PASS] 应用层零超卖，请继续用 verify_run.sql 做数据库侧交叉校验");
        }
    }

    enum Outcome {
        SUCCESS,
        SLOT_FULL,
        GRAB_CONFLICT,
        RATE_LIMITED,
        ERROR
    }

    static Outcome classifyResponse(int statusCode, String body) {
        if (statusCode == 200 && body.contains("\"code\":\"OK\"")) {
            return Outcome.SUCCESS;
        }
        if (body.contains("SLOT_FULL")) {
            return Outcome.SLOT_FULL;
        }
        if (body.contains("GRAB_CONFLICT")) {
            return Outcome.GRAB_CONFLICT;
        }
        if (body.contains("GRAB_RATE_LIMITED")) {
            return Outcome.RATE_LIMITED;
        }
        return Outcome.ERROR;
    }

    private static long pct(List<Long> sorted, int p) {
        int idx = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static void warmup(HttpClient client, String baseUrl, List<Long> tracked) throws Exception {
        for (int i = 0; i < 30; i++) {
            long id = publish(client, baseUrl, 1);
            tracked.add(id);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/errands/" + id + "/grab"))
                    .header("X-User-Id", "9001")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            client.send(req, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static long publish(HttpClient client, String baseUrl, int slotTotal) throws Exception {
        String json = """
                {"campusId":1,"type":"DELIVERY","title":"bench_尖峰抢单任务","rewardCents":100,"slotTotal":%d}
                """.formatted(slotTotal);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/errands"))
                .header("X-User-Id", String.valueOf(PUBLISHER_ID))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        return parseErrandId(body);
    }

    static long parseErrandId(String body) {
        int idx = body.indexOf("\"errandId\":");
        if (idx < 0) {
            throw new IllegalStateException("发布任务失败: " + body);
        }
        int start = idx + 11;
        boolean quoted = start < body.length() && body.charAt(start) == '"';
        if (quoted) {
            start++;
        }
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) {
            end++;
        }
        if (end == start) {
            throw new IllegalStateException("发布任务响应缺少 errandId 数值: " + body);
        }
        return Long.parseLong(body.substring(start, end));
    }
}
