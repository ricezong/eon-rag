# 后端镜像：多阶段构建。构建上下文为仓库根目录（见 docker-compose.app.yml）。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/eon-rag-*.jar app.jar
EXPOSE 8080

# 用 bash 的 /dev/tcp 做存活探测，避免依赖 wget/curl
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 || exit 1"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
