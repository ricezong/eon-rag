package cn.kong.eon.rag.chat;

import cn.kong.eon.rag.config.properties.KbProperties;
import cn.kong.eon.rag.search.ContextSegment;
import cn.kong.eon.rag.search.RetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问答分支：检索为空时不调模型直接收尾；有上下文时才进入流式生成。
 */
class ChatServiceTest {

    private RetrievalService retrieval;
    private ChatModel chatModel;
    private ChatService service;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        retrieval = mock(RetrievalService.class);
        chatModel = mock(ChatModel.class);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        service = new ChatService(retrieval, new PromptRenderer(), chatModel,
                new ObjectMapper(), scheduler, new KbProperties());
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void emptyRetrievalSkipsModelEntirely() {
        when(retrieval.retrieve(any(), any())).thenReturn(List.of());
        SseEmitter emitter = service.chat("回调地址怎么改", List.of());
        assertNotNull(emitter);
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    @Test
    void withContextEntersStreamingGeneration() {
        ContextSegment segment = new ContextSegment(1, "回调配置",
                UUID.randomUUID(), UUID.randomUUID(), "商户可修改回调通知地址",
                12, "{}", List.of());
        when(retrieval.retrieve(any(), any())).thenReturn(List.of(segment));
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("商户可以"))))));
        SseEmitter emitter = service.chat("回调地址怎么改", List.of());
        assertNotNull(emitter);
        verify(chatModel, times(1)).stream(any(Prompt.class));
    }
}
