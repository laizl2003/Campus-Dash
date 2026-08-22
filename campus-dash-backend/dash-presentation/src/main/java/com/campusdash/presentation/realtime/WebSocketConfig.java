package com.campusdash.presentation.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 装配：/ws 端点 + 握手鉴权。
 * setAllowedOrigins("*")：演示环境放开；生产应收敛到前端域名白名单。
 */
@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "dash.ws.enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketConfig implements WebSocketConfigurer {

    private final WsHandler wsHandler;
    private final WsAuthHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(WsHandler wsHandler, WsAuthHandshakeInterceptor handshakeInterceptor) {
        this.wsHandler = wsHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsHandler, "/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
