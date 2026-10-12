plugins {
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "dev.arctic"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(rootProject.libs.versions.java.get())
            vendor = JvmVendorSpec.ADOPTIUM
        }
    }

    dependencies {
        "implementation"(platform(rootProject.libs.spring.boot.dependencies))
        "implementation"(platform(rootProject.libs.spring.ai.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        val fixtures = rootProject.projectDir.parentFile.resolve("fixtures")
        inputs.dir(fixtures).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("fixtures")
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Darctic.fixtures=${fixtures.absolutePath}")
        })
    }
}
