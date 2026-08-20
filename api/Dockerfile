# Multi-stage build: compile with Maven, run on a slim JRE.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# The API works in wall-clock local time, not UTC: every timestamp it writes (circulation
# history, the campaign log) and every "today" it computes (the day counter on the Circulars
# tab) comes from the JVM default zone, and a container with no TZ defaults to UTC. Baked in
# here so the image is right however it is launched; compose overrides it from .env.
ENV TZ=Europe/Warsaw
COPY --from=build /build/target/chartering-api-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
