-- ============================================================================
-- eon-rag 幂等初始化脚本
-- 前置约束：数据库实例初始化时 LC_CTYPE 必须为 C.UTF-8 或 en_US.utf8
--          （docker-compose 中通过 POSTGRES_INITDB_ARGS 保证），
--          否则 pg_trgm 将完全忽略所有非 ASCII 中文字符
-- ============================================================================

-- ============================================================================
-- 1. 启用核心扩展
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS zhparser;

-- ============================================================================
-- 2. 配置 zhparser 中文全文检索词典映射
-- 覆盖名词(n)、动词(v)、形容词(a)、习语(i)、叹词/英文数字词元(e)、成语(l)、简称(j)、
-- 区别词(b)、副词(d)、方位词(f)、数词(m)、量词(q)、代词(r)、处所词(s)、时间词(t)、
-- 自定义词条/未知专名(x)、状态词(z)，过滤纯标点符号词性(u/w)
-- ============================================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'zh_cfg') THEN
        CREATE TEXT SEARCH CONFIGURATION zh_cfg (PARSER = zhparser);
        ALTER TEXT SEARCH CONFIGURATION zh_cfg
            ADD MAPPING FOR n,v,a,i,e,l,j,b,d,f,m,q,r,s,t,x,z WITH simple;
    END IF;
END
$$;

-- ============================================================================
-- 3. 通用 updated_at 自动刷新触发器函数
-- ============================================================================
CREATE OR REPLACE FUNCTION trigger_set_updated_at()
RETURNS trigger AS $$
BEGIN
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- 4. 文档主表：记录解析模式、状态机与父子切片统计
-- ============================================================================
CREATE TABLE IF NOT EXISTS documents (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_name     VARCHAR(500) NOT NULL,
    file_type     VARCHAR(20)  NOT NULL,
    file_size     BIGINT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING'
                  CHECK (status IN ('PROCESSING', 'CHUNKED', 'INDEXING', 'INDEX_FAILED',
                                    'COMPLETED', 'NEEDS_OCR', 'FAILED')),
    parse_mode    VARCHAR(20)  NOT NULL DEFAULT 'AUTO'
                  CHECK (parse_mode IN ('AUTO', 'LOCAL', 'CLOUD')),
    error_message TEXT,
    parent_count  INT NOT NULL DEFAULT 0,
    child_count   INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_status ON documents (status);

CREATE OR REPLACE TRIGGER trg_documents_updated_at
    BEFORE UPDATE ON documents
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ============================================================================
-- 5. 父块表：仅存储完整上下文文本与结构元数据，不建向量索引
-- ============================================================================
CREATE TABLE IF NOT EXISTS parent_chunks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    seq         INT  NOT NULL,
    title       VARCHAR(500),
    content     TEXT NOT NULL,
    token_count INT  NOT NULL DEFAULT 0,
    meta        JSONB NOT NULL DEFAULT '{}'::jsonb, -- 页码范围 / 表名 / 幻灯片范围
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_parent_doc_seq UNIQUE (document_id, seq)
);

-- ============================================================================
-- 6. 子块表：三路混合检索的核心单元（向量 + 中文全文 + Trigram）
-- ============================================================================
CREATE TABLE IF NOT EXISTS child_chunks (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id      UUID NOT NULL REFERENCES parent_chunks(id) ON DELETE CASCADE,
    document_id    UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    seq            INT  NOT NULL,
    content        TEXT NOT NULL,
    context_prefix TEXT,                            -- 上下文增强前缀，可为空
    embedding      vector(1024),                   -- 手动向量化前为空；完成后写入
    search_content tsvector,                        -- 由触发器自动维护的中文全文向量
    token_count    INT  NOT NULL DEFAULT 0,
    meta           JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_child_parent_seq UNIQUE (parent_id, seq)
);

-- HNSW 向量余弦距离索引
CREATE INDEX IF NOT EXISTS idx_child_embedding
    ON child_chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 中文全文检索 GIN 索引
CREATE INDEX IF NOT EXISTS idx_child_search
    ON child_chunks USING gin (search_content);

-- Trigram 模糊匹配 GIN 索引
CREATE INDEX IF NOT EXISTS idx_child_trgm
    ON child_chunks USING gin (content gin_trgm_ops);

-- 关联过滤与邻接窗口查询索引
CREATE INDEX IF NOT EXISTS idx_child_doc ON child_chunks (document_id);

-- 自动维护子块中文全文检索向量（拼接 context_prefix 与 content）
CREATE OR REPLACE FUNCTION child_chunks_tsvector_trigger()
RETURNS trigger AS $$
BEGIN
    NEW.search_content :=
        setweight(to_tsvector('zh_cfg', COALESCE(NEW.context_prefix, '')), 'B') ||
        setweight(to_tsvector('zh_cfg', COALESCE(NEW.content, '')), 'A');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_child_chunks_tsvector
    BEFORE INSERT OR UPDATE OF content, context_prefix ON child_chunks
    FOR EACH ROW EXECUTE FUNCTION child_chunks_tsvector_trigger();

-- ============================================================================
-- 7. 知识库分类表与文档多对多关联表
-- ============================================================================
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    color       VARCHAR(7)   NOT NULL DEFAULT '#3b82f6',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_kb_name ON knowledge_bases (name);

CREATE OR REPLACE TRIGGER trg_knowledge_bases_updated_at
    BEFORE UPDATE ON knowledge_bases
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TABLE IF NOT EXISTS document_knowledge_bases (
    document_id       UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (document_id, knowledge_base_id)
);

CREATE INDEX IF NOT EXISTS idx_dkb_kb  ON document_knowledge_bases (knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_dkb_doc ON document_knowledge_bases (document_id);
