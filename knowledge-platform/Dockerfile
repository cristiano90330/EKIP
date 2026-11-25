# 先构建 Java 后端包，再用较小的 JRE 镜像运行。

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拷 pom 拉依赖，再拷源码
# 依赖放 BuildKit 缓存卷而不是镜像层：本次实测 go-offline 单层要 1247s，且 Maven 中央仓库会
# 「Premature end of Content-Length」截断，缓存卷能让下次构建增量复用、中断的下载接着传。
# ⚠️ 两个 mvn 步骤必须挂同一个 /root/.m2：只挂第一个的话依赖不进镜像层，package 阶段读不到会重下一遍。
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q dependency:go-offline || mvn -q -B dependency:resolve
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# ===== 运行阶段：JRE17 =====
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/knowledge-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
