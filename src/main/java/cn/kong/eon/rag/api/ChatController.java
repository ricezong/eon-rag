package cn.kong.eon.rag.api;

import cn.kong.eon.rag.api.dto.ChatRequest;
import cn.kong.eon.rag.chat.ChatService;
import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * 问答接口：SSE 流式响应。
 * 事件序列：citations（引用）→ message（增量）→ done。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(@Valid @RequestBody ChatRequest request) {
        List<UUID> kbIds;
        try {
            kbIds = request.kbIdsOrEmpty().stream().map(UUID::fromString).toList();
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_REQUEST, "kbIds 中存在非法 UUID");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(chatService.chat(request.question(), kbIds));
    }
}
