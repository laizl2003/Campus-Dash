package com.campusdash.it;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 集成测试依赖 docker-compose 起的常驻 MySQL/Redis。
 *
 * 为什么不用 Testcontainers：compose 已经把容器起好了，测试直连常驻容器可以省掉
 * 每次跑测试都拉起/销毁容器的几十秒开销。代价是需要先 docker compose up，
 * 所以这里做端口探测，中间件没起时整类跳过（skip 而不是 fail），
 * 保证在任何环境下 mvn test 都不会因为没起 Docker 而变红。
 */
final class MiddlewareAvailable {

    private MiddlewareAvailable() {
    }

    static boolean check() {
        return portOpen("127.0.0.1", 3307) && portOpen("127.0.0.1", 6380);
    }

    private static boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
