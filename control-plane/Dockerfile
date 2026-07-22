FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY src ./src
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
COPY --from=build /src/target/asterism-control-plane-0.1.4.jar app.jar
EXPOSE 8085
CMD ["java", "-jar", "app.jar"]
