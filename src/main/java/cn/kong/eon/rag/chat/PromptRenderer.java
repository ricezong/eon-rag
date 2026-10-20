package cn.kong.eon.rag.chat;

import cn.kong.eon.rag.search.ContextSegment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 直接拼接：不做占位符替换；context 标签仅作资料边界。
 */
@Component
public class PromptRenderer {

    private static final String HEAD = """
            你是知识库问答助手。请严格依据下方 <context> 标签内的资料回答问题：
            1. 资料未涵盖的内容，明确回答"知识库中未找到相关信息"，禁止编造；
            2. 回答中引用资料时，在对应句子末尾标注角标 [n]（n 为资料编号）。

            <context>
            """;

    private static final String TAIL = """

            </context>

            问题：""";

    public String render(String question, List<ContextSegment> segments) {
        StringBuilder sb = new StringBuilder(HEAD);
        for (ContextSegment segment : segments) {
            sb.append('[').append(segment.index()).append("] ");
            if (segment.title() != null && !segment.title().isBlank()) {
                sb.append("（《").append(segment.title()).append("》）");
            }
            sb.append('\n').append(segment.text()).append("\n\n");
        }
        return sb.append(TAIL).append(question).toString();
    }
}
