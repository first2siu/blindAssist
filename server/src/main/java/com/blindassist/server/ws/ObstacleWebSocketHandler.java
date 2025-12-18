package com.blindassist.server.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 避障 WebSocket Handler：
 * - 客户端发送二进制图像帧
 * - 当前示例：简单统计收到的帧数，并周期性返回“向左/向右微调”等假指令
 * - 实际项目中可在此处接入实时目标检测/分割模型，并结合导航路线生成更智能的指令
 */
@Component
public class ObstacleWebSocketHandler extends BinaryWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger frameCounter = new AtomicInteger(0);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.sendMessage(new TextMessage("已建立避障通道，可以开始发送图像帧。"));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        int count = frameCounter.incrementAndGet();
        byte[] payload = message.getPayload().array();
        // TODO: 在此解析图像字节，调用视觉模型

        // 示例：每收到 10 帧，返回一条“假装”的避障指令
        if (count % 10 == 0) {
            sendFakeInstruction(session, count, payload.length);
        }
    }

    private void sendFakeInstruction(WebSocketSession session, int frameCount, int bytes) throws IOException {
        String direction = (frameCount / 10) % 2 == 0 ? "left" : "right";
        Map<String, Object> instruction = Map.of(
                "type", "warning",
                "direction", direction,
                "distance", 1.5,
                "message", "示例：请稍微向" + ("left".equals(direction) ? "左" : "右") + "侧偏一点，前方有障碍物。"
        );
        session.sendMessage(new TextMessage(mapper.writeValueAsString(instruction)));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        session.close(CloseStatus.SERVER_ERROR);
    }
}


