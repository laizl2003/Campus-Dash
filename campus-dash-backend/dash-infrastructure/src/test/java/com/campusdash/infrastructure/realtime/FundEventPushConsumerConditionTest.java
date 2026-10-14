package com.campusdash.infrastructure.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FundEventPushConsumerConditionTest {
    @Test
    void worker_with_websocket_disabled_does_not_join_the_app_push_group() {
        assertConsumerRegistered(false, Map.of("dash.mq.enabled", "true", "dash.ws.enabled", "false"));
    }

    @Test
    void app_keeps_websocket_push_enabled_by_default() {
        assertConsumerRegistered(true, Map.of("dash.mq.enabled", "true"));
    }

    @Test
    void app_can_explicitly_enable_websocket_push() {
        assertConsumerRegistered(true, Map.of("dash.mq.enabled", "true", "dash.ws.enabled", "true"));
    }

    @Test
    void mq_disabled_does_not_register_a_push_consumer() {
        assertConsumerRegistered(false, Map.of("dash.mq.enabled", "false", "dash.ws.enabled", "true"));
    }

    @Test
    void missing_mq_setting_does_not_accidentally_enable_a_consumer() {
        assertConsumerRegistered(false, Map.of("dash.ws.enabled", "true"));
    }

    private void assertConsumerRegistered(boolean expected, Map<String, Object> properties) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            // 只验证真实 Spring 条件装配，延迟实例化以避免构造 MQ 客户端和网络连接。
            context.addBeanFactoryPostProcessor(factory -> {
                for (String name : factory.getBeanDefinitionNames()) {
                    factory.getBeanDefinition(name).setLazyInit(true);
                }
            });
            context.register(FundEventPushConsumer.class);
            context.refresh();
            assertEquals(expected ? 1 : 0,
                    context.getBeanNamesForType(FundEventPushConsumer.class, false, false).length);
        }
    }
}
