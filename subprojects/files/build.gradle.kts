plugins {
    id("gradlebuild.distribution.api-java")
    id("gradlebuild.publish-public-libraries")
}

description = "Base tools to work with files"

gradlebuildJava.usedInWorkers()

dependencies {
    api(project(":functional"))

    api(libs.jsr305)

    implementation(project(":base-annotations"))
    implementation(libs.guava)
    implementation(libs.slf4jApi)

    testImplementation(project(":native"))
    testImplementation(project(":base-services")) {
        because("TextUtil is needed")
    }
    testImplementation(testFixtures(project(":native")))
}

dependencyAnalysis {
    issues {
        onAny {
            severity("fail")
        }
    }
}
