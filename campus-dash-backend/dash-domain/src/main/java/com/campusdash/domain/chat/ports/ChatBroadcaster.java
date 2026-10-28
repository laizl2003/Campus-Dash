package com.campusdash.domain.chat.ports;

import com.campusdash.domain.chat.model.ChatMessage;

/** 聊天广播端口：落库成功后推给所有在线连接。 */
public interface ChatBroadcaster {
    void broadcast(ChatMessage message);
}
