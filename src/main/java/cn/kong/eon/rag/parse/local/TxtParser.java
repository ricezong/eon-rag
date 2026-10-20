package cn.kong.eon.rag.parse.local;

import cn.kong.eon.rag.parse.Block;
import cn.kong.eon.rag.parse.DocumentParser;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 纯文本解析：UTF-8 严格解码失败回退 GBK（中文 txt 常见编码），BOM 剥离，按空行分段。
 */
@Component
public class TxtParser implements DocumentParser {

    @Override
    public List<String> extensions() {
        return List.of("txt");
    }

    @Override
    public List<Block> parse(byte[] bytes, String fileType) {
        String content = decode(bytes);
        List<Block> blocks = new ArrayList<>();
        for (String para : content.strip().split("\\n\\s*\\n")) {
            String text = para.strip();
            if (!text.isEmpty()) {
                blocks.add(new Block(Block.BlockType.PARAGRAPH, 0, text, 0, 0, Map.of()));
            }
        }
        return blocks;
    }

    private String decode(byte[] bytes) {
        // UTF-8 BOM
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, java.nio.charset.Charset.forName("GBK"));
        }
    }
}
