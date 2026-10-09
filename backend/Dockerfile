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

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]