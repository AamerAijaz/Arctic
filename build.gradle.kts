import org.springframework.boot.gradle.tasks.run.BootRun

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
    implementation("org.springframework.ai:spring-ai-starter-mcp-server")
}

// Gradle does not attach stdin by default; stdio MCP exits on EOF without this.
tasks.named<BootRun>("bootRun") {
    standardInput = System.`in`
}
