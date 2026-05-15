FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM docker:28-cli AS docker-cli

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=build /workspace/target/*.jar /app/app.jar

EXPOSE 8095
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
