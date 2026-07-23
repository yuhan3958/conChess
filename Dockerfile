FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21-jre

WORKDIR /app

ENV ENVIRONMENT=production
ENV PORT=8080
ENV DATABASE_PATH=/data/conchess.sqlite

RUN mkdir -p /data

COPY --from=build /workspace/build/install/conChess ./

EXPOSE 8080

CMD ["./bin/conChess"]
