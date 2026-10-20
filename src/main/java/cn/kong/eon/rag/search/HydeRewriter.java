package cn.kong.eon.rag.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * HyDE 假设性文档改写：
 * 改写文本仅作为向量通道输入；生成失败降级为原始提问。
 */
@Component
public class HydeRewriter {

    private static final Logger log = LoggerFactory.getLogger(HydeRewriter.class);

    private final ChatModel chatModel;

    public HydeRewriter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 生成假设性文档；失败返回 null（调用方降级用原提问） */
    public String rewrite(String question) {
        try {
            String prompt = """
                    针对以下问题，生成一段可能出现在知识库文档中的假设性回答文本（200 字以内）。\
                    直接输出文本，不要任何前缀。
                    问题：%s""".formatted(question);
            String result = chatModel.call(prompt);
            return result == null || result.isBlank() ? null : result.strip();
        } catch (Exception e) {
            log.warn("hyde rewrite failed, fallback to raw question: {}", e.getMessage());
            return null;
        }
    }
}
