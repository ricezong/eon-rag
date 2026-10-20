package cn.kong.eon.rag.parse;

import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.common.exception.ErrorCode;

import java.util.List;

/**
 * 疑似扫描件检测：
 * 统计去空白后的有效字符数，整篇低于 minTextChars（默认 50）
 * 或 PDF 页均低于 10，即阻断为 NEEDS_OCR。
 */
public final class ScanDetector {

    /** PDF 页均有效字符阈值 */
    static final int MIN_CHARS_PER_PAGE = 10;

    private ScanDetector() {
    }

    /**
     * @param pageTexts    每页文本（PDF 提供；无页概念格式传 null，仅校验整篇）
     * @param minTextChars 整篇阈值（kb.parse.min-text-chars）
     */
    public static void assertNotScanned(List<String> pageTexts, int minTextChars) {
        String all = pageTexts == null ? "" : String.join("", pageTexts);
        int total = effectiveChars(all);
        if (total < minTextChars) {
            throw blocked("整篇有效字符 " + total + " 低于阈值 " + minTextChars);
        }
        if (pageTexts != null && !pageTexts.isEmpty()) {
            double avg = (double) total / pageTexts.size();
            if (avg < MIN_CHARS_PER_PAGE) {
                throw blocked("页均有效字符 " + String.format("%.1f", avg) + " 低于阈值 " + MIN_CHARS_PER_PAGE);
            }
        }
    }

    /** 去除全部空白符后的有效字符数 */
    private static int effectiveChars(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static BizException blocked(String detail) {
        return new BizException(ErrorCode.NEEDS_OCR,
                "未检测到有效文本层，疑似扫描件；当前未启用 OCR，请上传文字版文档或开启云端解析（" + detail + "）");
    }
}
