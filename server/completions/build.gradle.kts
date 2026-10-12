plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
}

val checkNoPolarionClient = tasks.register("checkNoPolarionClient") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if polarion-client is on the completions runtime classpath."
    val root = configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }
    doLast {
        val seen = mutableSetOf<ResolvedComponentResult>()
        val pending = ArrayDeque(listOf(root.get()))
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            if (seen.add(component)) {
                pending += component.dependencies.filterIsInstance<ResolvedDependencyResult>().map { it.selected }
            }
        }
        if (seen.any { (it.id as? ProjectComponentIdentifier)?.projectPath == ":polarion-client" }) {
            throw GradleException("completions must not depend on :polarion-client (found on runtimeClasspath)")
        }
    }
}

tasks.check {
    dependsOn(checkNoPolarionClient)
}
