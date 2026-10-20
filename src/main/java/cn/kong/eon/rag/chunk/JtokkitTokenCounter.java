package cn.kong.eon.rag.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * jtokkit 精确计量（CL100K_BASE，复用 spring-ai-starter-model-openai 传递依赖）。
 * jtokkit 不可用时降级为零开销估算：中文 × 1.5 + 其它字符 × 0.25。
 */
@Component
public class JtokkitTokenCounter implements TokenCounter {

    private static final Logger log = LoggerFactory.getLogger(JtokkitTokenCounter.class);

    private final Encoding encoding;

    public JtokkitTokenCounter() {
        Encoding enc;
        try {
            EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
            enc = registry.getEncoding(EncodingType.CL100K_BASE);
        } catch (Throwable t) {
            log.warn("jtokkit unavailable, fallback to estimated counting: {}", t.getMessage());
            enc = null;
        }
        this.encoding = enc;
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (encoding == null) {
            return estimate(text);
        }
        try {
            return encoding.countTokens(text);
        } catch (Throwable t) {
            return estimate(text);
        }
    }

    @Override
    public String tail(String text, int maxTokens) {
        if (encoding == null || text == null || text.isEmpty() || maxTokens <= 0) {
            return estimateTail(text, maxTokens);
        }
        try {
            IntArrayList tokens = encoding.encode(text);
            if (tokens.size() <= maxTokens) {
                return text;
            }
            return encoding.decode(slice(tokens, tokens.size() - maxTokens, tokens.size()));
        } catch (Throwable t) {
            return estimateTail(text, maxTokens);
        }
    }

    @Override
    public String head(String text, int maxTokens) {
        if (encoding == null || text == null || text.isEmpty() || maxTokens <= 0) {
            return estimateHead(text, maxTokens);
        }
        try {
            IntArrayList tokens = encoding.encode(text);
            if (tokens.size() <= maxTokens) {
                return text;
            }
            return encoding.decode(slice(tokens, 0, maxTokens));
        } catch (Throwable t) {
            return estimateHead(text, maxTokens);
        }
    }

    private IntArrayList slice(IntArrayList tokens, int from, int to) {
        IntArrayList slice = new IntArrayList(to - from);
        for (int i = from; i < to; i++) {
            slice.add(tokens.get(i));
        }
        return slice;
    }

    /* ---------- 零开销降级估算 ---------- */

    private int estimate(String text) {
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            tokens += text.charAt(i) > 0x2E7F ? 1.5 : 0.25;
        }
        return (int) Math.ceil(tokens);
    }

    private String estimateTail(String text, int maxTokens) {
        if (text == null || maxTokens <= 0) {
            return "";
        }
        int chars = (int) (maxTokens / 1.5);
        return text.length() <= chars ? text : text.substring(text.length() - chars);
    }

    private String estimateHead(String text, int maxTokens) {
        if (text == null || maxTokens <= 0) {
            return "";
        }
        int chars = (int) (maxTokens / 1.5);
        return text.length() <= chars ? text : text.substring(0, chars);
    }
}
