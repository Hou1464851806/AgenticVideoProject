FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS build
WORKDIR /src
COPY pom.xml ./
COPY backend/business-api/pom.xml backend/business-api/pom.xml
COPY backend/media-worker backend/media-worker
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl backend/media-worker -am -DskipTests package
FROM eclipse-temurin:17-jre-noble@sha256:900322f4cc1b9da9d730ae4bb56d8a1dabbaa9cfa32809fc8b542b600370a045
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg=7:6.1.1-3ubuntu5 && rm -rf /var/lib/apt/lists/* && mkdir /work && chown 10001:10001 /work
WORKDIR /work
COPY --from=build /src/backend/media-worker/target/media-worker-0.1.0-SNAPSHOT.jar /app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app.jar"]
