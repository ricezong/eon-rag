package cn.kong.eon.rag.it;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema 与三路检索 SQL 集成测试。
 * 前置：本机 Docker 可用，且已构建镜像 eon-postgres:16。
 * 触发：环境变量 EON_RAG_IT=1；无 Docker 时自动跳过。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "EON_RAG_IT", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaSqlIT {

    @Container
    private static final GenericContainer<?> PG =
            new GenericContainer<>(DockerImageName.parse("eon-postgres:16"))
                    .withEnv("POSTGRES_DB", "eon_rag")
                    .withEnv("POSTGRES_USER", "eon")
                    .withEnv("POSTGRES_PASSWORD", "it")
                    .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=C.UTF-8")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

    private static Connection conn;
    private static String schemaSql;

    @BeforeAll
    static void connect() throws Exception {
        conn = DriverManager.getConnection(
                "jdbc:postgresql://%s:%d/eon_rag".formatted(PG.getHost(), PG.getMappedPort(5432)),
                "eon", "it");
        schemaSql = Files.readString(Path.of("docker/init/001_schema.sql"));
        // 建表由本测试执行
        try (Statement st = conn.createStatement()) {
            st.execute(schemaSql);
        }
    }

    @Test
    @Order(1)
    void schemaIsIdempotent() throws Exception {
        // 重复执行两次验证幂等
        try (Statement st = conn.createStatement()) {
            st.execute(schemaSql);
            st.execute(schemaSql);
        }
    }

    @Test
    @Order(2)
    void zhCfgSegmentsChinese() throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_tsvector('zh_cfg', '支付网关配置')")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).contains("支付"), "zhparser 应切出中文词元");
        }
    }

    @Test
    @Order(2)
    void trigramHandlesChinese() throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT similarity('支付网关', '支付网关')")) {
            assertTrue(rs.next());
            assertTrue(rs.getDouble(1) > 0.99, "lc_ctype=C.UTF-8 下 pg_trgm 应处理中文");
        }
    }

    @Test
    @Order(3)
    void tsvectorTriggerAndThreeWayRetrieval() throws Exception {
        String vector = "[" + "0.1,".repeat(1023) + "0.1]";
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO documents (id, file_name, file_type) "
                    + "VALUES (gen_random_uuid(), 'it.pdf', 'pdf')");
            st.execute("INSERT INTO parent_chunks (id, document_id, seq, title, content) "
                    + "SELECT gen_random_uuid(), d.id, 0, '章节', '父块内容' FROM documents d");
            st.execute("INSERT INTO child_chunks (parent_id, document_id, seq, content, context_prefix, embedding) "
                    + "SELECT p.id, p.document_id, 0, '支付网关的配置说明', '出自测试文档', '" + vector + "'::vector "
                    + "FROM parent_chunks p");
        }

        // 触发器：正文 A 权重 + 前缀 B 权重
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT search_content FROM child_chunks")) {
            assertTrue(rs.next());
            String tsvector = rs.getString(1);
            assertTrue(tsvector.contains("'支付'") || tsvector.contains("'配置说明'"), "正文应入 A 权重词元");
            assertTrue(tsvector.contains("'出自'") || tsvector.contains("'测试文档'"), "前缀应入 B 权重词元");
        }

        // 通道 1：向量余弦（ORDER BY <=> ASC）
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT c.id FROM child_chunks c "
                        + "ORDER BY c.embedding <=> ?::vector ASC LIMIT 20")) {
            ps.setString(1, vector);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
        }

        // 通道 2：中文全文（websearch_to_tsquery，问号不应报错）
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT c.id FROM child_chunks c, "
                     + "websearch_to_tsquery('zh_cfg', '支付网关？') q(query) "
                     + "WHERE c.search_content @@ q.query")) {
            assertTrue(rs.next());
        }

        // 通道 3：Trigram 模糊（% 运算符 + 同事务 SET LOCAL）
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.execute("SET LOCAL pg_trgm.similarity_threshold = 0.2");
            try (ResultSet rs = st.executeQuery(
                    "SELECT id FROM child_chunks WHERE content % '支付网关'")) {
                assertTrue(rs.next());
            }
        } finally {
            conn.commit();
            conn.setAutoCommit(true);
        }

        // 级联清理：删主表即清父子块
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM documents");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM child_chunks")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }
}
