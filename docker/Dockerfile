# ============================================================
# Eon 知识库 - PostgreSQL 定制镜像
#
# 基座：pgvector/pgvector:pg16（已内置 PostgreSQL 16 + pgvector 扩展）
# 追加：SCWS（中文分词内核） + zhparser（PG 中文分词扩展）
# pg_trgm 由 PostgreSQL contrib 自带，无需额外安装。
#
# 构建：docker compose build
# ============================================================
FROM pgvector/pgvector:pg16

# SCWS 源码下载地址（如 xunsearch 站点不可达，可覆盖为镜像源）
ARG SCWS_VERSION=1.2.3
ARG SCWS_URL=http://www.xunsearch.com/scws/down/scws-1.2.3.tar.bz2
# zhparser 分支/标签（master 已支持 PG16/PG17）
ARG ZHPARSER_REF=master

ENV DEBIAN_FRONTEND=noninteractive

# 1) 编译依赖（含 PG 服务端开发头文件 / pg_config）
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        build-essential \
        git \
        ca-certificates \
        wget \
        bzip2 \
        postgresql-server-dev-16; \
    rm -rf /var/lib/apt/lists/*

# 2) 编译安装 SCWS：libscws + 默认词典(dict.utf8.xdb)装到 /usr/local
RUN set -eux; \
    cd /tmp; \
    wget -O scws.tar.bz2 "${SCWS_URL}"; \
    tar xjf scws.tar.bz2; \
    cd "scws-${SCWS_VERSION}"; \
    ./configure --prefix=/usr/local; \
    make -j"$(nproc)"; \
    make install; \
    ldconfig

# 3) 编译安装 zhparser 扩展，并清理构建残留以缩减体积
RUN set -eux; \
    cd /tmp; \
    git clone --depth 1 --branch "${ZHPARSER_REF}" https://github.com/amutu/zhparser.git; \
    cd zhparser; \
    make -j"$(nproc)"; \
    make install; \
    cd /; \
    rm -rf /tmp/scws* /tmp/zhparser; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*

# 官方 postgres 入口脚本会处理 POSTGRES_* 环境变量与 initdb 目录，无需覆盖 CMD
