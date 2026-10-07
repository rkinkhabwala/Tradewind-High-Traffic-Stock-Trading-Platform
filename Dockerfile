# syntax=docker/dockerfile:1
# One build for the whole reactor; each service image copies only its own jar.
# Images are pinned and were pulled before being written here (spec section 10).

FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21.0.12.1_1-jre AS runtime
RUN useradd --system --uid 10001 --no-create-home tradewind
WORKDIR /app
USER tradewind
# Heap sized from the container memory limit; crash fast on OOM so the orchestrator restarts the service.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS gateway
COPY --from=build /src/gateway/target.nosync/gateway-*.jar /app/app.jar
EXPOSE 8080

FROM runtime AS account-service
COPY --from=build /src/account-service/target.nosync/account-service-*.jar /app/app.jar
EXPOSE 8081

FROM runtime AS marketdata-service
COPY --from=build /src/marketdata-service/target.nosync/marketdata-service-*.jar /app/app.jar
EXPOSE 8082

FROM runtime AS order-service
COPY --from=build /src/order-service/target.nosync/order-service-*.jar /app/app.jar
EXPOSE 8083

FROM runtime AS execution-service
COPY --from=build /src/execution-service/target.nosync/execution-service-*.jar /app/app.jar
EXPOSE 8084

FROM runtime AS portfolio-service
COPY --from=build /src/portfolio-service/target.nosync/portfolio-service-*.jar /app/app.jar
EXPOSE 8085
