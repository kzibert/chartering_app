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
# Sized for a small container, Render's free 512 MB above all. Without these the JVM takes a
# quarter of the container for the heap (128 MB there) and leaves memory it never uses. Half is
# what fits: the other 256 MB holds about 130 MB of metaspace (Spring, Hibernate), the code cache
# capped below, and the thread stacks, which are halved. Going past half risks the container
# being killed outright rather than the JVM saying it is short. SerialGC is the right collector
# for one CPU or less and has the smallest footprint. JAVA_TOOL_OPTIONS so a deployment can
# replace the lot; render.yaml adds TieredStopAtLevel=1, which trades peak speed for a faster
# cold start on 0.1 CPU.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -Xss512k -XX:ReservedCodeCacheSize=48m"
COPY --from=build /build/target/chartering-api-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
