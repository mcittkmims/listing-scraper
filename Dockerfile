# Build
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

# Run
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 scraper \
 && mkdir -p /data/photos && chown scraper /data/photos
USER scraper
WORKDIR /app
COPY --from=build /src/build/libs/listing-scraper.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
