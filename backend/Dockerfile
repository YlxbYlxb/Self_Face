# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom 并预热依赖：pom 没变时这一层直接命中缓存，
# 改业务代码不会触发重新下载全部依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre-alpine

# 「今日题单」按日期分桶、日志时间戳都依赖本地时区，
# 容器默认 UTC 会让跨零点前后的行为对不上，必须显式设置
RUN apk add --no-cache tzdata \
    && addgroup -S app \
    && adduser -S -G app app

WORKDIR /app
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

COPY --from=build /build/target/*.jar app.jar

# 日志目录预先建好并交给非 root 用户，否则以 app 身份启动时写不进去
RUN mkdir -p /app/logs && chown -R app:app /app

USER app
EXPOSE 8081

# 用 /api/health 做存活探针；alpine 自带 wget
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8081/api/health >/dev/null 2>&1 || exit 1

# 走 sh -c 是为了让 JAVA_OPTS 里的多个参数正确展开
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
