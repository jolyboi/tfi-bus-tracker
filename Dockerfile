# syntax=docker/dockerfile:1.7

# --- Build stage ---------------------------------------------------------
# Full JDK + Gradle to produce the Spring Boot fat jar. Discarded after build.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Copy build configuration first so dependency resolution can layer-cache
# independently of source changes.
COPY gradle ./gradle
COPY gradlew settings.gradle build.gradle ./
RUN chmod +x gradlew

# Then copy the actual sources.
COPY src ./src

# Build the fat jar. The BuildKit cache mount keeps Gradle's dependency
# cache warm between image rebuilds. Skip tests in the image build; we run
# those in CI / locally, not on the deploy path.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test && \
    cp build/libs/*.jar /app.jar

# --- Runtime stage -------------------------------------------------------
# Slim JRE-only image. This is what actually gets shipped to Fly.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /app.jar /app/app.jar

# GtfsBootstrap writes the extracted CSVs here. In production, /data is a
# Fly persistent volume mounted into the container, so the data survives
# restarts/redeploys and the ~98 MB download only happens on first boot.
ENV BUS_TRACKER_GTFS_DIR=/data/gtfs

# Spring Boot defaults to port 8080; fly.toml maps external traffic to it.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
