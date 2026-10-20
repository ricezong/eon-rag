package cn.kong.eon.rag.api;

import cn.kong.eon.rag.chunk.TokenCounter;
import cn.kong.eon.rag.common.api.ApiResponse;
import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.common.exception.ErrorCode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Token 精确计数接口，供编辑器展示与保存前校验使用。
 */
@RestController
@RequestMapping("/api/tokens")
public class TokenCountController {

    private static final int MAX_TEXTS = 8;

    private final TokenCounter tokenCounter;

    public TokenCountController(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    @PostMapping("/count")
    public ApiResponse<List<Integer>> count(@RequestBody TokenCountRequest request) {
        List<String> texts = request == null ? null : request.texts();
        if (texts == null || texts.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        if (texts.size() > MAX_TEXTS) {
            throw new BizException(ErrorCode.INVALID_CHUNK_OPERATION,
                    "一次最多计算 " + MAX_TEXTS + " 段文本");
        }
        return ApiResponse.ok(texts.stream().map(tokenCounter::count).toList());
    }

    public record TokenCountRequest(List<String> texts) {
    }
}
