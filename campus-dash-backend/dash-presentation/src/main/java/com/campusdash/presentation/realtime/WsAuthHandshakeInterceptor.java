package com.campusdash.presentation.realtime;

import com.campusdash.domain.auth.ports.AuthPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

/**
 * WebSocket 握手鉴权：从 ?token=xxx 解析用户身份。
 *
 * 为什么用 query 参数而不是 header：浏览器原生 WebSocket API
 * 不能设置自定义请求头，query 是标准做法。token 会出现在 URL 里，
 * 生产环境应上 WSS + 短时效一次性 ticket，教程里讲这个演进。
 */
@Component
@ConditionalOnProperty(name = "dash.ws.enabled", havingValue = "true", matchIfMissing = true)
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

    private final AuthPort authPort;

    public WsAuthHandshakeInterceptor(AuthPort authPort) {
        this.authPort = authPort;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String token = servletRequest.getServletRequest().getParameter("token");
            Optional<Long> userId = authPort.resolve(token);
            if (userId.isPresent()) {
                attributes.put(WsHandler.ATTR_USER_ID, userId.get());
                return true;
            }
        }
        return false; // 未认证：拒绝握手
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
