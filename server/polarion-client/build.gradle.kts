plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(libs.spring.web)
    implementation(libs.jackson.databind)
    implementation(libs.spring.boot.autoconfigure)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.context)
    testImplementation("org.assertj:assertj-core")

    testFixturesApi(platform(libs.spring.boot.dependencies))
    testFixturesApi(libs.jackson.databind)
    testFixturesApi(libs.junit.jupiter)
}
