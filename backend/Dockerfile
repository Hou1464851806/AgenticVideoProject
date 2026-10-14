FROM node:24-bookworm-slim@sha256:d6aa754f16b3197301076f047b5def2f02ea1dbbc2ca920407d46d7ec7f87b20 AS frontend
WORKDIR /frontend
COPY frontend/package*.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS build
WORKDIR /workspace
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY --from=frontend /frontend/dist backend/src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl backend -am package -DskipTests

FROM eclipse-temurin:17-jre-noble@sha256:900322f4cc1b9da9d730ae4bb56d8a1dabbaa9cfa32809fc8b542b600370a045
RUN apt-get -o Acquire::http::Timeout=30 -o Acquire::Retries=3 update && apt-get -o Acquire::http::Timeout=30 -o Acquire::Retries=3 install -y --no-install-recommends curl ffmpeg=7:6.1.1-3ubuntu5 fonts-noto-cjk=1:20230817+repack1-3 && rm -rf /var/lib/apt/lists/* && mkdir /work && chown 10001:10001 /work
WORKDIR /app
COPY --from=build /workspace/backend/target/backend-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
