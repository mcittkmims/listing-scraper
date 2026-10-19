import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}

group = "md.apartmentlens"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
}

tasks.test {
    useJUnitPlatform()
    // Integration tests apply the migrations of the main repository (database/migrations).
    systemProperty(
        "migrations.dir",
        providers.gradleProperty("migrationsDir")
            .getOrElse(layout.projectDirectory.dir("../../database/migrations").asFile.absolutePath),
    )
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

tasks.bootJar {
    archiveFileName = "listing-scraper.jar"
}

tasks.test {
    // Opt-in evaluation over a saved snapshot: -Dsnapshot=... -Dreport=...
    listOf("snapshot", "report").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = true }
}
