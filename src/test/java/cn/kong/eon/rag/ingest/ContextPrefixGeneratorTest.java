package cn.kong.eon.rag.ingest;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前缀增强触发条件：父块无标题且子块以代词开头。
 */
class ContextPrefixGeneratorTest {

    private final ContextPrefixGenerator generator =
            new ContextPrefixGenerator(Mockito.mock(org.springframework.ai.chat.model.ChatModel.class));

    @Test
    void pronounWithoutParentTitleNeedsPrefix() {
        assertTrue(generator.needsPrefix(null, "该方案采用父子分块。"));
        assertTrue(generator.needsPrefix("", "上述指标表明召回率提升。"));
    }

    @Test
    void titledParentOrNonPronounSkips() {
        assertFalse(generator.needsPrefix("系统架构", "该方案采用父子分块。"));
        assertFalse(generator.needsPrefix(null, "系统采用三路混合检索。"));
        assertFalse(generator.needsPrefix(null, ""));
    }
}
