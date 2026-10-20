package cn.kong.eon.rag.chat;

import cn.kong.eon.rag.config.properties.KbProperties;
import cn.kong.eon.rag.search.ContextSegment;
import cn.kong.eon.rag.search.RetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE 流式问答完整链路：
 * 检索 → citations 事件 → Prompt 渲染 → ChatModel 流式生成 → message 增量事件 → done。
 *
 * 事件序列契约：citations（父子两级引用）→ message（增量文本）→ done；
 * 超时或生成失败时以 error 事件（data 为提示文本）收尾。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 看门狗先于 SseEmitter 超时触发，留出写 error 事件的余量 */
    private static final long TIMEOUT_GRACE_MS = 5_000L;

    private final RetrievalService retrievalService;
    private final PromptRenderer promptRenderer;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService timeoutScheduler;
    private final int sseTimeoutMs;

    public ChatService(RetrievalService retrievalService,
                       PromptRenderer promptRenderer,
                       ChatModel chatModel,
                       ObjectMapper objectMapper,
                       ScheduledExecutorService chatTimeoutScheduler,
                       KbProperties kbProperties) {
        this.retrievalService = retrievalService;
        this.promptRenderer = promptRenderer;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.timeoutScheduler = chatTimeoutScheduler;
        this.sseTimeoutMs = kbProperties.getChat().getSseTimeoutMs();
    }

    public SseEmitter chat(String question, List<UUID> kbIds) {
        ChatStream stream = new ChatStream();
        long startedAt = System.nanoTime();
        log.info("chat stage=start questionLength={} kbCount={}",
                question == null ? 0 : question.length(), kbIds == null ? 0 : kbIds.size());

        try {
            // 1. 检索与归并（同步完成，通常数百 ms）
            List<ContextSegment> segments = retrievalService.retrieve(question, kbIds);
            log.info("chat stage=retrieval.done segments={} elapsedMs={}",
                    segments.size(), elapsedMs(startedAt));

            // 2. citations 事件：父/子两级引用定位
            if (!stream.send("citations", toJson(citationsPayload(segments)))) {
                return stream.emitter();
            }

            // 3. 无检索结果：直接答"未找到"
            if (segments.isEmpty()) {
                log.info("chat stage=no_context questionLength={}", question == null ? 0 : question.length());
                stream.send("message", "知识库中未找到相关信息。");
                stream.finish();
                return stream.emitter();
            }

            // 4. 流式生成（首帧输出 TTFT）
            Prompt prompt = new Prompt(promptRenderer.render(question, segments));
            log.info("chat stage=generation.start segments={} elapsedMs={}",
                    segments.size(), elapsedMs(startedAt));
            subscribe(stream, chatModel.stream(prompt), startedAt, segments.size());
        } catch (Exception e) {
            log.error("chat stage=failed elapsedMs={}", elapsedMs(startedAt), e);
            stream.fail(e.getMessage());
        }
        return stream.emitter();
    }

    /** 订阅生成流：增量转 message 事件，终态转 done，异常转 error */
    private void subscribe(ChatStream stream, Flux<ChatResponse> responses,
                           long startedAt, int segmentCount) {
        boolean[] firstToken = {true};
        stream.bind(responses.subscribe(
                response -> {
                    String delta = extractDelta(response);
                    if (delta == null || delta.isEmpty()) {
                        return;
                    }
                    if (firstToken[0]) {
                        firstToken[0] = false;
                        log.info("chat stage=first_token elapsedMs={} segments={}",
                                elapsedMs(startedAt), segmentCount);
                    }
                    stream.send("message", delta);
                },
                error -> {
                    log.error("chat stage=stream.failed elapsedMs={} segments={}",
                            elapsedMs(startedAt), segmentCount, error);
                    stream.fail(error.getMessage());
                },
                () -> {
                    log.info("chat stage=done elapsedMs={} segments={}", elapsedMs(startedAt), segmentCount);
                    stream.finish();
                }));
    }

    /** ChatResponse → 增量文本（兼容空结果帧） */
    private String extractDelta(ChatResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return null;
        }
        AssistantMessage output = response.getResults().get(0).getOutput();
        return output == null ? null : output.getText();
    }

    /** citations 载荷：父块编号/标题/文档/定位元数据 + 命中子块明细 */
    private List<Map<String, Object>> citationsPayload(List<ContextSegment> segments) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ContextSegment segment : segments) {
            Map<String, Object> item = new HashMap<>();
            item.put("index", segment.index());
            item.put("title", segment.title());
            item.put("documentId", segment.documentId());
            item.put("parentId", segment.parentId());
            item.put("meta", parseMeta(segment.parentMeta()));
            item.put("children", segment.hits().stream()
                    .map(ref -> Map.of("childId", ref.childId(), "seq", ref.seq(), "score", ref.score()))
                    .toList());
            payload.add(item);
        }
        return payload;
    }

    private Map<String, String> parseMeta(String json) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                    });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * 单次问答的 SSE 流：终止幂等，终止时取消超时看门狗并释放上游订阅。
     *
     * 超时由看门狗提前触发：SseEmitter 自身的超时回调执行前响应已被标记完成，
     * 回调内无法再写事件，其超时仅作为看门狗失效时的兜底。
     */
    private final class ChatStream {

        private final SseEmitter emitter;
        private final AtomicReference<Disposable> upstream = new AtomicReference<>();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final ScheduledFuture<?> watchdog;

        private ChatStream() {
            this.emitter = new SseEmitter(sseTimeoutMs + TIMEOUT_GRACE_MS);
            this.watchdog = timeoutScheduler.schedule(this::onTimeout, sseTimeoutMs, TimeUnit.MILLISECONDS);
            emitter.onTimeout(() -> log.warn("chat sse closed by container timeout, timeoutMs={}", sseTimeoutMs));
            emitter.onError(error -> {
                log.warn("chat sse error: {}", error.toString());
                release();
            });
            emitter.onCompletion(this::release);
        }

        private SseEmitter emitter() {
            return emitter;
        }

        /** 业务事件：发送失败即终止流并释放上游，返回 false 表示连接已失效 */
        private boolean send(String event, String data) {
            if (terminated.get()) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
                return true;
            } catch (IOException | IllegalStateException e) {
                log.warn("chat sse send failed, event={}, cause={}", event, e.getMessage());
                if (terminated.compareAndSet(false, true)) {
                    release();
                    close();
                }
                return false;
            }
        }

        /** 收尾事件：终止位已置，发送失败仅记录日志 */
        private void writeFinal(String event, String data) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException e) {
                log.warn("chat sse final event not delivered, event={}, cause={}", event, e.getMessage());
            }
        }

        /** 正常收尾：done 事件后结束流 */
        private void finish() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            writeFinal("done", "{}");
            close();
        }

        /** 异常收尾：error 事件后结束流 */
        private void fail(String message) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            release();
            writeFinal("error", message == null || message.isBlank() ? "生成失败，请稍后重试。" : message);
            close();
        }

        private void close() {
            watchdog.cancel(false);
            try {
                emitter.complete();
            } catch (IllegalStateException e) {
                log.debug("chat sse already closed: {}", e.getMessage());
            }
        }

        private void bind(Disposable disposable) {
            upstream.set(disposable);
        }

        private void release() {
            Disposable disposable = upstream.get();
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
        }

        private void onTimeout() {
            log.warn("chat sse timeout, timeoutMs={}", sseTimeoutMs);
            fail("回答生成超时（" + sseTimeoutMs / 1000 + " 秒），请重试或缩短问题。");
        }
    }
}
