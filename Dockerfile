# Build
FROM maven:3.9-eclipse-temurin-21 AS build
# Proxy d'entreprise : passer les options JVM de proxy à Maven (voir .env.example,
# MAVEN_OPTS) — la JVM n'honore pas les variables HTTP_PROXY/HTTPS_PROXY.
ARG MAVEN_OPTS=""
ENV MAVEN_OPTS=${MAVEN_OPTS}
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# Run
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
