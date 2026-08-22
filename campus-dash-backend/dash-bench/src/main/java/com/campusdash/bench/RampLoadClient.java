package com.campusdash.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S2 稳态阶梯加压客户端。
 *
 * S1 的瞬时吞吐只能证明并发正确性，不能当单机 QPS。这个客户端按多个并发档位持续压测，
 * 每档独立登记 bench_run，输出 QPS 与延迟分位数，供 P7 R1-R6 调优前后对比。
 *
 * 用法：
 *   java -cp ... com.campusdash.bench.RampLoadClient [baseUrl] [stageCsv] [durationSeconds]
 */
public class RampLoadClient {

    private static final String DEFAULT_BASE = "http://127.0.0.1:8080";
    private static final long LOGIN_USER = 1001L;

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE;
        int[] stages = parseStages(args.length > 1 ? args[1] : "50,100,200,400,800");
        int durationSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 180;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        String token = login(client, baseUrl, LOGIN_USER);

        for (int stage : stages) {
            runStage(client, baseUrl, token, stage, durationSeconds);
        }
    }

    private static void runStage(HttpClient client, String baseUrl, String token,
                                 int concurrency, int durationSeconds) throws Exception {
        BenchRunRecorder recorder = new BenchRunRecorder();
        String runId = recorder.startRun("BENCH", "S2", concurrency,
                "S2 阶梯加压；应用与中间件、发压端同机，数字仅作基线");

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        Map<String, AtomicInteger> errorTypes = new ConcurrentHashMap<>();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
        CountDownLatch done = new CountDownLatch(concurrency);
        long started = System.currentTimeMillis();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    try {
                        while (System.nanoTime() < deadline) {
                            long s = System.nanoTime();
                            try {
                                HttpRequest req = HttpRequest.newBuilder()
                                        .uri(URI.create(baseUrl + "/api/errands?campusId=1&status=PUBLISHED&size=20"))
                                        .header("Authorization", "Bearer " + token)
                                        .timeout(Duration.ofSeconds(10))
                                        .GET()
                                        .build();
                                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                                latencies.add((System.nanoTime() - s) / 1_000_000);
                                if (resp.statusCode() == 200 && resp.body().contains("\"code\":\"OK\"")) {
                                    ok.incrementAndGet();
                                } else {
                                    errors.incrementAndGet();
                                    errorTypes.computeIfAbsent("HTTP_" + resp.statusCode(), k -> new AtomicInteger())
                                            .incrementAndGet();
                                }
                            } catch (Exception e) {
                                errors.incrementAndGet();
                                String type = e.getClass().getSimpleName();
                                errorTypes.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            if (!done.await(durationSeconds + 60L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("S2 档位未按时结束 concurrency=" + concurrency);
            }
        }

        long elapsedMs = Math.max(System.currentTimeMillis() - started, 1);
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        int total = ok.get() + errors.get();
        long qps = Math.round(total * 1000.0 / elapsedMs);
        long p50 = sorted.isEmpty() ? -1 : pct(sorted, 50);
        long p95 = sorted.isEmpty() ? -1 : pct(sorted, 95);
        long p99 = sorted.isEmpty() ? -1 : pct(sorted, 99);
        long max = sorted.isEmpty() ? -1 : sorted.get(sorted.size() - 1);

        System.out.println("================ S2 阶梯加压结果 ================");
        System.out.printf("runId=%s concurrency=%d duration=%ds%n", runId, concurrency, durationSeconds);
        System.out.printf("requests=%d ok=%d errors=%d qps=%d%n", total, ok.get(), errors.get(), qps);
        System.out.printf("latency P50/P95/P99/Max=%d/%d/%d/%d ms%n", p50, p95, p99, max);
        if (!errorTypes.isEmpty()) {
            System.out.println("errors=" + errorTypes);
        }
        System.out.println("=================================================");

        boolean pass = errors.get() == 0;
        recorder.finishRun(runId, pass ? "PASS" : "FAIL", String.format(
                "{\"stage\":%d,\"requests\":%d,\"ok\":%d,\"errors\":%d,"
                        + "\"qps\":%d,\"p50Ms\":%d,\"p95Ms\":%d,\"p99Ms\":%d,\"maxMs\":%d}",
                concurrency, total, ok.get(), errors.get(), qps, p50, p95, p99, max));
        recorder.close();
    }

    private static int[] parseStages(String csv) {
        String[] parts = csv.split(",");
        int[] stages = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            stages[i] = Integer.parseInt(parts[i].trim());
        }
        return stages;
    }

    private static String login(HttpClient client, String baseUrl, long userId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":" + userId + "}"))
                .build();
        String resp = client.send(req, HttpResponse.BodyHandlers.ofString()).body();
        int i = resp.indexOf("\"token\":\"") + 9;
        return resp.substring(i, resp.indexOf('"', i));
    }

    private static long pct(List<Long> sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
