package cn.kong.eon.rag.parse;

import cn.kong.eon.rag.common.exception.BizException;
import cn.kong.eon.rag.common.exception.ErrorCode;
import cn.kong.eon.rag.config.properties.KbParseProperties;
import cn.kong.eon.rag.parse.cloud.LlamaParseClient;
import cn.kong.eon.rag.parse.local.MarkdownParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 解析路由：
 * AUTO——cloud-types 白名单格式且云端可用时走云端，异常回退本地；
 * LOCAL——强制本地；CLOUD——强制云端，失败即报错。
 */
@Component
public class ParseRouter {

    private static final Logger log = LoggerFactory.getLogger(ParseRouter.class);

    private final Map<String, DocumentParser> parsersByExt;
    private final MarkdownParser markdownParser;
    private final KbParseProperties props;
    private final Optional<LlamaParseClient> cloudClient;

    public ParseRouter(List<DocumentParser> parsers,
                       MarkdownParser markdownParser,
                       KbParseProperties props,
                       Optional<LlamaParseClient> cloudClient) {
        this.parsersByExt = parsers.stream()
                .flatMap(p -> p.extensions().stream().map(ext -> Map.entry(ext, p)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        this.markdownParser = markdownParser;
        this.props = props;
        this.cloudClient = cloudClient;
    }

    /** 按配置默认模式解析 */
    public List<Block> parse(byte[] bytes, String fileType) {
        return parse(bytes, fileType, null, null);
    }

    /** 按指定模式解析（fileName 供云端任务命名） */
    public List<Block> parse(byte[] bytes, String fileType, String modeOverride, String fileName) {
        String ext = fileType == null ? "" : fileType.toLowerCase(Locale.ROOT);
        String mode = modeOverride == null || modeOverride.isBlank()
                ? props.getMode() : modeOverride.toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "LOCAL" -> parseLocal(bytes, ext);
            case "CLOUD" -> parseCloud(bytes, ext, fileName);
            default -> parseAuto(bytes, ext, fileName);
        };
    }

    private List<Block> parseAuto(byte[] bytes, String ext, String fileName) {
        if (props.getCloudTypes().contains(ext) && cloudClient.isPresent()) {
            try {
                return parseCloud(bytes, ext, fileName);
            } catch (Exception e) {
                // 云端失败回退本地兜底，回退事件记日志
                log.warn("cloud parse failed for '{}', fallback to local: {}", ext, e.getMessage());
            }
        }
        return parseLocal(bytes, ext);
    }

    private List<Block> parseLocal(byte[] bytes, String ext) {
        DocumentParser parser = parsersByExt.get(ext);
        if (parser == null) {
            throw new BizException(ErrorCode.UNSUPPORTED_FORMAT, ext);
        }
        long started = System.nanoTime();
        List<Block> blocks = parser.parse(bytes, ext);
        // 全格式扫描件校验（PDF 的页均阈值在 PdfParser 内另行执行）
        if (!"pdf".equals(ext)) {
            ScanDetector.assertNotScanned(
                    List.of(blocks.stream().map(Block::text).collect(Collectors.joining("\n"))),
                    props.getMinTextChars());
        }
        log.info("parse stage=local ext={} parser={} blocks={} elapsedMs={}",
                ext, parser.getClass().getSimpleName(), blocks.size(),
                (System.nanoTime() - started) / 1_000_000);
        return blocks;
    }

    private List<Block> parseCloud(byte[] bytes, String ext, String fileName) {
        LlamaParseClient client = cloudClient
                .orElseThrow(() -> new BizException(ErrorCode.PARSE_FAILED,
                        "cloud parse requested but LLAMA_CLOUD_API_KEY not configured"));
        long started = System.nanoTime();
        String markdown = client.parseToMarkdown(bytes, fileName == null ? "document." + ext : fileName);
        List<Block> blocks = markdownParser.parse(markdown.getBytes(StandardCharsets.UTF_8), "md");
        log.info("parse stage=cloud ext={} blocks={} elapsedMs={}",
                ext, blocks.size(), (System.nanoTime() - started) / 1_000_000);
        return blocks;
    }
}
