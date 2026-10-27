# ── 构建阶段 ──
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# ── 运行阶段 ──
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 非 root 运行
RUN addgroup -S app && adduser -S app -G app

COPY --from=build /app/target/*.jar app.jar
RUN chown app:app app.jar

USER app

EXPOSE 8080

# ── 健康检查：只验"JVM 进程还活着"，不碰 HTTP ──
#
# ⚠️ 为什么不用原来的 wget /api/health —— 那是双重错误：
#   ① 后端根本没有 /api/health，真实路径是 /api/settings/health（见 SettingsController）；
#   ② 即使路径改对也拿不到 200，因为 SecurityConfig 只放行 /api/auth/**、/ws/**、OPTIONS，
#      其余 anyRequest().authenticated() —— 探活会收到 401，容器被误判 unhealthy。
#   把 /api/settings/health 改成 permitAll 虽然能跑通，但那会把五项依赖探活结果匿名暴露到公网
#   （等于告诉攻击者你的 ES / Redis 哪个正挂着），得不偿失。
#
# 也不用 nc / telnet 探 TCP 端口：alpine 基础镜像里这些工具是否齐全无法在此环境实测，
# 一旦缺命令，healthcheck 会恒定失败——比没有 healthcheck 更糟。
# jcmd 属于 JDK 而非 JRE，运行阶段镜像是 17-jre-alpine，所以也不能用。
#
# pidof 由 busybox 自带且不依赖应用内部路由与安全策略，故选它。
# 代价：只能证明"进程活着"，证明不了"能正确响应请求"。真正的就绪性判断留给编排层
# （compose 的 depends_on / k8s readinessProbe 去走带鉴权的接口或应用自报）。
# 长期正解是引入 Actuator 的 liveness/readiness 端点，见 docs 里的技术栈评估。
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD pidof java > /dev/null 2>&1 || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]