/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.smoketests

import org.gradle.util.internal.VersionNumber
import org.junit.Assume

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class GradleVersionsPluginSmokeTest extends AbstractPluginValidatingSmokeTest {

    def 'can check for updated versions'() {
        Assume.assumeTrue('Incompatible with Groovy 4: Execution failed for task \':dependencyUpdates\'. > groovy/util/XmlSlurper', VersionNumber.parse(GroovySystem.version).major < 4) // TODO Watch for merge and release of https://github.com/ben-manes/gradle-versions-plugin/pull/656
        given:
        buildFile << """
            plugins {
                id "com.github.ben-manes.versions" version "$TestedVersions.gradleVersions"
            }

            subprojects {
                apply plugin: 'java'

                ${mavenCentralRepository()}
            }
            project(":sub1") {
                dependencies {
                    implementation group: 'log4j', name: 'log4j', version: '1.2.14'
                }
            }
            project(":sub2") {
                dependencies {
                    implementation group: 'junit', name: 'junit', version: '4.10'
                }
            }
        """
        settingsFile << """
            include "sub1", "sub2"
        """

        when:
        def result = runner('dependencyUpdates', '-DoutputFormatter=txt').forwardOutput().build()

        then:
        result.task(':dependencyUpdates').outcome == SUCCESS
        result.output.contains("- junit:junit [4.10 -> 4.13")
        result.output.contains("- log4j:log4j [1.2.14 -> 1.2.17]")

        file("build/dependencyUpdates/report.txt").exists()
    }

    @Override
    Map<String, Versions> getPluginsToValidate() {
        [
            'com.github.ben-manes.versions': Versions.of(TestedVersions.gradleVersions)
        ]
    }
}
