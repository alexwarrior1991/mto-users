# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /workspace

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S mtousers \
    && adduser -S mtousers -G mtousers \
    && apk add --no-cache curl

WORKDIR /app
COPY --from=build /workspace/target/mto-users-*.jar /app/app.jar

# Sin valores por defecto para Keycloak a proposito: un servicio de administracion de usuarios que
# arranca apuntando a un realm de ejemplo es un fallo silencioso. Se pasan al desplegar.
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    LOGGING_LEVEL_ROOT=INFO \
    JAVA_OPTS=""

EXPOSE 8080

USER mtousers
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
