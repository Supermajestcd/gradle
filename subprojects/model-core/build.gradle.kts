plugins {
    id("gradlebuild.distribution.api-java")
}

description = "Implementation of configuration model types and annotation metadata handling (Providers, software model, conventions)"

dependencies {
    api(project(":core-api"))
    api(project(":problems"))
    api(project(":base-annotations"))
    api(project(":hashing"))
    api(project(":process-services"))
    api(project(":base-services"))
    api(project(":functional"))
    api(project(":logging"))
    api(project(":messaging"))
    api(project(":persistent-cache"))
    api(project(":snapshots"))

    api(libs.asm)
    api(libs.jsr305)
    api(libs.inject)
    api(libs.groovy)
    api(libs.guava)

    implementation(project(":base-services-groovy"))

    implementation(libs.futureKotlin("stdlib"))
    implementation(libs.slf4jApi)
    implementation(libs.commonsLang)

    testFixturesApi(testFixtures(project(":diagnostics")))
    testFixturesApi(testFixtures(project(":core")))
    testFixturesApi(project(":internal-integ-testing"))
    testFixturesImplementation(libs.guava)
    testFixturesImplementation(libs.groovyAnt)
    testFixturesImplementation(libs.groovyDatetime)
    testFixturesImplementation(libs.groovyDateUtil)

    testImplementation(project(":process-services"))
    testImplementation(project(":file-collections"))
    testImplementation(project(":native"))
    testImplementation(project(":resources"))
    testImplementation(testFixtures(project(":core-api")))

    integTestImplementation(project(":platform-base"))

    testRuntimeOnly(project(":distributions-core")) {
        because("Tests instantiate DefaultClassLoaderRegistry which requires a 'gradle-plugins.properties' through DefaultPluginModuleRegistry")
    }
    integTestDistributionRuntimeOnly(project(":distributions-native")) {
        because("ModelRuleCachingIntegrationTest requires a rules implementation")
    }
}

strictCompile {
    ignoreRawTypes() // raw types used in public API
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(null as? Int)
    sourceCompatibility = "8"
    targetCompatibility = "8"
}

integTest.usesJavadocCodeSnippets.set(true)

packageCycles {
    excludePatterns.add("org/gradle/model/internal/core/**")
    excludePatterns.add("org/gradle/model/internal/inspect/**")
    excludePatterns.add("org/gradle/api/internal/tasks/**")
    excludePatterns.add("org/gradle/model/internal/manage/schema/**")
    excludePatterns.add("org/gradle/model/internal/type/**")
    excludePatterns.add("org/gradle/api/internal/plugins/*")
}

dependencyAnalysis {
    issues {
        onAny {
            severity("fail")
        }
    }
}
