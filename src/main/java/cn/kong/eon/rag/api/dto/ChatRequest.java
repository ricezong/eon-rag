package cn.kong.eon.rag.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 问答请求体。
 *
 * @param question 必填，去空白后非空
 * @param kbIds    知识库范围；缺省或 null 表示全库检索
 */
public record ChatRequest(
        @NotBlank String question,
        @Size(max = 50) List<String> kbIds) {

    public List<String> kbIdsOrEmpty() {
        return kbIds == null ? List.of() : kbIds;
    }
}
