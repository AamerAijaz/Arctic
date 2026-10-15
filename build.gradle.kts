plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}

group = "com.arctic"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.springframework:spring-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test:4.1.1")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
