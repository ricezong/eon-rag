package cn.kong.eon.rag.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 子块上下文前缀生成（Contextual Retrieval）：
 * 仅对"父块无标题且以代词开头"的子块生成背景前缀（提示词限制 80 字以内）。
 * 生成失败仅记 warn 置空，不阻断摄入。
 */
@Component
public class ContextPrefixGenerator {

    private static final Logger log = LoggerFactory.getLogger(ContextPrefixGenerator.class);
    private static final String[] PRONOUN_PREFIXES = {
            "该", "此", "其", "本", "上述", "这些", "那些", "它们", "他们", "它", "这", "那", "如下"
    };

    private final ChatModel chatModel;

    public ContextPrefixGenerator(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 该子块是否需要生成前缀：父块无标题且子块以代词开头 */
    public boolean needsPrefix(String parentTitle, String childContent) {
        if (parentTitle != null && !parentTitle.isBlank()) {
            return false;
        }
        String text = childContent == null ? "" : childContent.strip();
        if (text.isEmpty()) {
            return false;
        }
        for (String pronoun : PRONOUN_PREFIXES) {
            if (text.startsWith(pronoun)) {
                return true;
            }
        }
        return false;
    }

    /** 生成背景前缀；失败返回 null */
    public String generate(String fileName, String parentTitle, String childContent) {
        try {
            String prompt = """
                    为知识库片段生成一句背景说明（不超过 80 字），说明其所属文档与主题，\
                    帮助脱离上下文时理解该片段。直接输出说明文本，不要任何前缀或引号。
                    文档名：%s
                    章节：%s
                    片段内容：%s""".formatted(fileName == null ? "未知" : fileName,
                    parentTitle == null || parentTitle.isBlank() ? "无" : parentTitle,
                    childContent.length() > 300 ? childContent.substring(0, 300) : childContent);
            String result = chatModel.call(prompt);
            return result == null ? null : result.strip();
        } catch (Exception e) {
            log.warn("context prefix generation failed, skip: {}", e.getMessage());
            return null;
        }
    }
}
