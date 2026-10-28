package com.campusdash.worker;

import com.campusdash.domain.chat.ports.ChatBroadcaster;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * worker 不对外提供 WebSocket，但组件扫描会加载 SendChatMessageUseCase，
 * 它依赖 ChatBroadcaster（实现只在 presentation 模块）。这里给一个 no-op，
 * 避免 worker 因为聊天推送 Bean 缺失而启动失败、连带在线状态清理 job 停摆。
 */
@Configuration
public class WorkerChatConfig {

    @Bean
    @ConditionalOnMissingBean(ChatBroadcaster.class)
    public ChatBroadcaster noopChatBroadcaster() {
        return message -> { };
    }
}
