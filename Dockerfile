# Build
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

# Run
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 scraper \
 && mkdir -p /data/photos && chown scraper /data/photos
USER scraper
WORKDIR /app
COPY --from=build /src/target/listing-scraper.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
