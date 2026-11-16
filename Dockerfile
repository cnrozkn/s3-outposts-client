# syntax=docker/dockerfile:1

# The jar is the same on every architecture, so it is built once, natively, on the build machine.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/s3-outposts-client-*.jar /app.jar

# No RUN step here: a multi-architecture build then needs no emulation for this stage.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app.jar app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# 8080: web UI. 8081: health probes and Prometheus metrics; keep it off public networks.
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
