plugins {
    `java-library`
}

dependencies {
    api(project(":polarion-client"))
    api(libs.spring.ai.model)
    testImplementation(testFixtures(project(":polarion-client")))
}
