# Build
FROM maven:3.9-eclipse-temurin-21 AS build
# Proxy/miroir d'entreprise :
#  - settings.xml (miroir Nexus/Artifactory, credentials) injecté en SECRET BuildKit :
#    disponible pendant le RUN uniquement, jamais stocké dans une couche de l'image ;
#  - MAVEN_OPTS pour les options JVM de proxy (la JVM ignore HTTP_PROXY).
#  Voir .env.example (MAVEN_SETTINGS, MAVEN_OPTS, BUILD_NETWORK).
ARG MAVEN_OPTS=""
ENV MAVEN_OPTS=${MAVEN_OPTS}
WORKDIR /build
COPY pom.xml .
RUN --mount=type=secret,id=maven-settings,target=/root/.m2/settings.xml \
    --mount=type=cache,target=/root/.m2/repository \
    mvn -q dependency:go-offline
COPY src ./src
RUN --mount=type=secret,id=maven-settings,target=/root/.m2/settings.xml \
    --mount=type=cache,target=/root/.m2/repository \
    mvn -q -DskipTests package

# Run
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
