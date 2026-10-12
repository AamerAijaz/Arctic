plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":tools"))
    implementation(project(":agent"))
    implementation(libs.spring.ai.starter.mcp.server)
    implementation(libs.spring.boot.starter.webmvc)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(testFixtures(project(":polarion-client")))
}

springBoot {
    mainClass = "dev.arctic.local.ArcticApplication"
    buildInfo {
        excludes = setOf("time")
    }
}

// WindowsCredentialStore calls Advapi32 through the Foreign Function & Memory API.
val nativeAccess = "--enable-native-access=ALL-UNNAMED"

tasks.bootJar {
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

tasks.bootRun {
    jvmArgs(nativeAccess)
}

tasks.test {
    jvmArgs(nativeAccess)
    val bootJar = tasks.bootJar.flatMap { it.archiveFile }
    val launcher = javaToolchains.launcherFor(java.toolchain)
    inputs.file(bootJar)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Darctic.test.bootJar=${bootJar.get().asFile.absolutePath}",
            "-Darctic.test.java=${launcher.get().executablePath.asFile.absolutePath}",
        )
    })
}
