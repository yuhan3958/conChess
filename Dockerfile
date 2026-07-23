# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle

RUN chmod +x ./gradlew

RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/workspace/.gradle \
    ./gradlew --no-daemon dependencies

COPY src ./src

RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/workspace/.gradle \
    ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21-jre

WORKDIR /app

ENV ENVIRONMENT=production
ENV PORT=8080
ENV DATABASE_PATH=/data/conchess.sqlite

RUN apt-get update \
    && apt-get install -y --no-install-recommends sqlite3 \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /data

COPY --from=build /workspace/build/install/conChess ./

EXPOSE 8080

CMD ["./bin/conChess"]
