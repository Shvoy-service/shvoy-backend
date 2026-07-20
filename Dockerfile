# syntax=docker/dockerfile:1
FROM maven:3.9.16-eclipse-temurin-25-alpine AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S shvoy && adduser -S shvoy -G shvoy
COPY --from=build /app/target/*.jar app.jar
USER shvoy:shvoy

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
