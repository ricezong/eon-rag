package cn.kong.eon.rag.api;

import cn.kong.eon.rag.chat.ChatService;
import cn.kong.eon.rag.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 问答入口的参数校验：空问题、缺失问题、非法 UUID 都必须 400 拦下，
 * 不允许把字符串 "null" 送进检索链路。
 */
class ChatControllerValidationTest {

    private final ObjectMapper json = new ObjectMapper();
    private ChatService chatService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        when(chatService.chat(any(), any())).thenReturn(new SseEmitter(1000L));
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(chatService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void blankQuestionRejected() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("question", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(chatService, never()).chat(any(), any());
    }

    @Test
    void missingQuestionRejected() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kbIds", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(chatService, never()).chat(any(), any());
    }

    @Test
    void illegalKbIdRejected() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("question", "回调地址", "kbIds", List.of("not-a-uuid")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("UUID")));
        verify(chatService, never()).chat(any(), any());
    }

    @Test
    void validRequestReachesServiceWithEmptyScope() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("question", "回调地址怎么改"))))
                .andExpect(status().isOk());
        verify(chatService).chat(eq("回调地址怎么改"), eq(List.of()));
    }
}
