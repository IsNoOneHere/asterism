FROM node:20-alpine AS web-build
WORKDIR /src/workbench
ARG HTTP_PROXY
ARG HTTPS_PROXY
ENV HTTP_PROXY=$HTTP_PROXY HTTPS_PROXY=$HTTPS_PROXY
COPY workbench/package*.json ./
RUN npm ci --include=dev
COPY workbench ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS server-build
WORKDIR /src/control-plane
ARG HTTP_PROXY
ARG HTTPS_PROXY
COPY control-plane/pom.xml ./
COPY control-plane/src ./src
# 前端静态资源随控制面进入同一个可发布镜像。
COPY --from=web-build /src/workbench/dist ./src/main/resources/static
# Maven 不读取 HTTP_PROXY，这里按需生成构建阶段的代理配置。
RUN proxy="${HTTPS_PROXY:-}"; \
    if [ -n "$proxy" ]; then \
      proxy="${proxy#*://}"; \
      mkdir -p /root/.m2; \
      printf '<settings><proxies><proxy><id>build-proxy</id><active>true</active><protocol>http</protocol><host>%s</host><port>%s</port></proxy></proxies></settings>\n' \
        "${proxy%:*}" "${proxy##*:}" > /root/.m2/settings.xml; \
    fi; \
    mvn -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=server-build /src/control-plane/target/asterism-control-plane-*.jar app.jar
EXPOSE 8085
CMD ["java", "-jar", "app.jar"]
