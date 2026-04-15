package com.blindassist.server.ws;

import com.blindassist.server.service.AgentLoopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentWebSocketHandlerTest {

    @Mock
    private AgentLoopService agentLoopService;

    @Mock
    private WebSocketSession session;

    private AgentWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AgentWebSocketHandler(agentLoopService);
    }

    @Test
    void heartbeatStillReturnsPongWithoutCallingLoop() throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"heartbeat\"}"));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().getPayload().contains("\"type\":\"pong\""));
        verify(agentLoopService, never()).handleInit(any(), any());
    }

    @Test
    void initMessagesAreDelegatedToLoopService() throws Exception {
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"init\",\"task\":\"Take me home\"}"));

        verify(agentLoopService).handleInit(any(), any());
    }

    @Test
    void unknownMessageTypeReturnsError() throws Exception {
        when(session.isOpen()).thenReturn(true);
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"unknown\"}"));

        ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().getPayload().contains("\"status\":\"error\""));
    }
}
