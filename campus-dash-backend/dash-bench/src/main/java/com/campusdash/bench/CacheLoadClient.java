package com.campusdash.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S3 缓存读写混合压测客户端。
 *
 * 四个场景（与压测方案一致）：
 *   a  纯读，缓存关闭（应用以 --dash.cache.enabled=false 启动）——基准
 *   b  纯读，缓存开启——对比命中率与回源次数
 *   c  读写混合 9:1（写 = 持续发布新任务）+ 压后一致性校验
 *   d  热 Key：90% 请求打同一个 id——验证分片打散
 *
 * 关键指标不用 MySQL Questions（噪声大），用应用自己的 dbLoads（详情回源次数）：
 * 这是"详情查询真正打到 DB 的次数"，比数据库侧全局计数精确得多。
 *
 * 用法：java -cp ... com.campusdash.bench.CacheLoadClient <mode> [baseUrl]
 */
public class CacheLoadClient {

    static final long SEED_BASE = 920_000_000_000L;
    static final int SEED_COUNT = 100;
    static final int CONCURRENCY = 50;
    static final int ROUNDS_PER_THREAD = 100;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "b";
        String baseUrl = args.length > 1 ? args[1] : "http://127.0.0.1:8080";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        BenchRunRecorder recorder = new BenchRunRecorder();
        String runId = recorder.startRun("BENCH", "S3-" + mode, CONCURRENCY,
                "缓存压测，应用与中间件同机，数字仅作同机基线");
        System.out.println("[run] runId=" + runId + " mode=" + mode);

        String token = login(client, baseUrl, 1001);
        // 压前重置统计，保证命中率/回源数只反映本轮
        post(client, baseUrl, "/api/internal/cache-stats/reset", token, null);

        long hotId = SEED_BASE + 1;
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicLong writeCount = new AtomicLong();
        List<Long> latencies = new java.util.concurrent.CopyOnWriteArrayList<>();

        CountDownLatch done = new CountDownLatch(CONCURRENCY);
        long t0 = System.currentTimeMillis();
        for (int t = 0; t < CONCURRENCY; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    runRound(mode, client, baseUrl, token, tid, ok, fail, writeCount, latencies, hotId);
                } catch (Exception e) {
                    fail.addAndGet(ROUNDS_PER_THREAD);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        done.await();
        long elapsed = System.currentTimeMillis() - t0;

        String stats = get(client, baseUrl, "/api/internal/cache-stats", token);
        int diffs = -1;
        if ("c".equals(mode)) {
            String checkResp = post(client, baseUrl, "/api/internal/cache-check", token, null);
            diffs = extractInt(checkResp, "diffs");
        }

        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        long total = ok.get() + fail.get();
        long rps = Math.round(total * 1000.0 / Math.max(elapsed, 1));
        System.out.println("=================================================");
        System.out.printf("S3-%s 结果（并发=%d，总请求=%d，耗时=%dms，RPS≈%d）%n",
                mode, CONCURRENCY, total, elapsed, rps);
        System.out.printf("成功=%d 失败=%d 写操作=%d%n", ok.get(), fail.get(), writeCount.get());
        if (!sorted.isEmpty()) {
            System.out.printf("延迟 P50/P95/P99/Max: %d / %d / %d / %d ms%n",
                    pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), sorted.get(sorted.size() - 1));
        }
        System.out.println("缓存统计: " + stats);
        if (diffs >= 0) {
            System.out.println("一致性校验差异数: " + diffs + (diffs == 0 ? "（PASS）" : "（FAIL）"));
        }
        System.out.println("=================================================");

        boolean pass = fail.get() == 0 && (diffs < 0 || diffs == 0);
        recorder.finishRun(runId, pass ? "PASS" : "FAIL", String.format(
                "{\"mode\":\"S3-%s\",\"concurrency\":%d,\"requests\":%d,\"elapsedMs\":%d,"
                        + "\"rps\":%d,\"p99Ms\":%d,\"diffs\":%d,\"stats\":%s}",
                mode, CONCURRENCY, total, elapsed, rps,
                sorted.isEmpty() ? -1 : pct(sorted, 99), diffs, stats));
        recorder.close();
        if (!pass) {
            System.exit(1);
        }
    }

    static boolean readDetail(HttpClient client, String baseUrl, String token, long id) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/errands/" + id))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 && resp.body().contains("\"code\":\"OK\"");
    }

    static boolean publish(HttpClient client, String baseUrl, String token, int seq) throws Exception {
        String body = String.format(
                "{\"title\":\"s3_write_%d\",\"rewardCents\":100,\"slotTotal\":1}", seq);
        String resp = post(client, baseUrl, "/api/errands", token, body);
        return resp != null && resp.contains("\"code\":\"OK\"");
    }

    static String login(HttpClient client, String baseUrl, long userId) throws Exception {
        String resp = post(client, baseUrl, "/api/auth/login", null,
                "{\"userId\":" + userId + "}");
        int i = resp.indexOf("\"token\":\"") + 9;
        return resp.substring(i, resp.indexOf('"', i));
    }

    static String post(HttpClient client, String baseUrl, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static String get(HttpClient client, String baseUrl, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static int extractInt(String json, String field) {
        String needle = "\"" + field + "\":";
        int i = json.indexOf(needle) + needle.length();
        int end = i;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Integer.parseInt(json.substring(i, end));
    }

    static long pct(List<Long> sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    /** 单线程的压测轮次：按模式决定读写比例与目标 id */
    static void runRound(String mode, HttpClient client, String baseUrl, String token,
                         int tid, AtomicInteger ok, AtomicInteger fail,
                         AtomicLong writeCount, List<Long> latencies, long hotId) throws Exception {
        for (int r = 0; r < ROUNDS_PER_THREAD; r++) {
            // c 模式：每 10 次读配 1 次写（9:1），写 = 发布新任务
            if ("c".equals(mode) && r % 10 == 9 && tid < CONCURRENCY / 2) {
                long s = System.nanoTime();
                boolean w = publish(client, baseUrl, token, tid * 1000 + r);
                latencies.add((System.nanoTime() - s) / 1_000_000);
                if (w) writeCount.incrementAndGet(); else fail.incrementAndGet();
                continue;
            }
            // d 模式：90% 打热 Key，验证分片打散
            long id;
            if ("d".equals(mode) && ThreadLocalRandom.current().nextInt(10) < 9) {
                id = hotId;
            } else {
                id = SEED_BASE + 1 + ThreadLocalRandom.current().nextInt(SEED_COUNT);
            }
            long s = System.nanoTime();
            boolean success = readDetail(client, baseUrl, token, id);
            latencies.add((System.nanoTime() - s) / 1_000_000);
            if (success) ok.incrementAndGet(); else fail.incrementAndGet();
        }
    }
}
