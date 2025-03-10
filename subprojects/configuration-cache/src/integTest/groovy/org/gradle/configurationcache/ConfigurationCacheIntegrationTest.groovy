/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.configurationcache

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.initialization.LoadProjectsBuildOperationType
import org.gradle.initialization.ProjectsIdentifiedProgressDetails
import org.gradle.initialization.StartParameterBuildOptions.ConfigurationCacheRecreateOption
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.integtests.fixtures.configurationcache.ConfigurationCacheFixture
import spock.lang.Issue

class ConfigurationCacheIntegrationTest extends AbstractConfigurationCacheIntegrationTest {

    def "configuration cache for Help plugin task '#task' on empty project"() {
        given:
        settingsFile.createFile()
        configurationCacheRun(task, *options)
        def firstRunOutput = removeVfsLogOutput(result.normalizedOutput)
            .replaceAll(/Calculating task graph as no configuration cache is available for tasks: ${task}.*\n/, '')
            .replaceAll(/Configuration cache entry stored.\n/, '')

        when:
        configurationCacheRun(task, *options)
        def secondRunOutput = removeVfsLogOutput(result.normalizedOutput)
            .replaceAll(/Reusing configuration cache.\n/, '')
            .replaceAll(/Configuration cache entry reused.\n/, '')

        then:
        firstRunOutput == secondRunOutput

        where:
        task            | options
        "help"          | []
        "properties"    | []
        "dependencies"  | []
        "help"          | ["--task", "help"]
        "help"          | ["--rerun"]
    }

    def "can store task selection success/failure for :help --task"() {
        def configurationCache = newConfigurationCacheFixture()
        buildFile.text = """
        task aTask
        """
        when:
        configurationCacheFails "help", "--task", "bTask"
        then:
        failure.assertHasCause("Task 'bTask' not found in root project")
        configurationCache.assertStateStored()

        when:
        configurationCacheFails "help", "--task", "cTask"
        then:
        failure.assertHasCause("Task 'cTask' not found in root project")
        configurationCache.assertStateStored()

        when:
        configurationCacheFails "help", "--task", "bTask"
        then:
        failure.assertHasCause("Task 'bTask' not found in root project")
        configurationCache.assertStateLoaded()

        when:
        configurationCacheRun "help", "--task", "aTask"
        then:
        output.contains "Detailed task information for aTask"
        configurationCache.assertStateStored()

        when:
        configurationCacheFails "help", "--task", "cTask"
        then:
        failure.assertHasCause("Task 'cTask' not found in root project")
        configurationCache.assertStateLoaded()

        when:
        buildFile << """
        task bTask
        """
        configurationCacheRun "help", "--task", "bTask"
        then:
        output.contains "Detailed task information for bTask"
        configurationCache.assertStateStored()
    }

    @Issue("https://github.com/gradle/gradle/issues/18064")
    def "can build plugin with project dependencies"() {
        given:
        settingsFile << """
            include 'my-lib'
            include 'my-plugin'
        """
        file('my-lib/build.gradle') << """
            plugins { id 'java' }
        """
        file('my-plugin/build.gradle') << """
            plugins { id 'java-gradle-plugin' }

            dependencies {
              implementation project(":my-lib")
            }

            gradlePlugin {
              plugins {
                myPlugin {
                  id = 'com.example.my-plugin'
                  implementationClass = 'com.example.MyPlugin'
                }
              }
            }
        """
        file('src/main/java/com/example/MyPlugin.java') << """
            package com.example;
            public class MyPlugin implements $Plugin.name<$Project.name> {
              @Override
              public void apply($Project.name project) {
              }
            }
        """
        def configurationCache = newConfigurationCacheFixture()

        when:
        configurationCacheRun "build"
        configurationCacheRun "build"

        then:
        configurationCache.assertStateLoaded()
    }

    def "can copy zipTree"() {
        given:
        def configurationCache = newConfigurationCacheFixture()
        buildFile """
            def jar = tasks.register("jar", org.gradle.jvm.tasks.Jar) {
                it.from("a.file")
                it.destinationDirectory.set(layout.buildDirectory)
                it.archiveFileName.set("output.jar")
            }

            tasks.register("copy", org.gradle.api.tasks.Copy) {
                it.from(zipTree(${provider}))
                it.destinationDir(new File(project.buildDir, "copied"))
            }
        """
        file("a.file") << "42"

        when:
        configurationCacheRun "copy"
        configurationCacheRun "copy"

        then:
        configurationCache.assertStateLoaded()

        where:
        provider                         | _
        "jar.flatMap { it.archiveFile }" | _
        "jar.get().archiveFile"          | _
    }

    @Issue("gradle/gradle#20390")
    def "can deserialize copy task with rename"() {
        given:
        def configurationCache = newConfigurationCacheFixture()
        buildFile """
            tasks.register('copyAndRename', Copy) {
                from('foo') { rename { 'bar' } }
            }
        """

        when:
        configurationCacheRun "copyAndRename"
        configurationCacheRun "copyAndRename"

        then:
        configurationCache.assertStateLoaded()
    }

    private static String removeVfsLogOutput(String normalizedOutput) {
        normalizedOutput
            .replaceAll(/Received \d+ file system events .*\n/, '')
            .replaceAll(/Spent \d+ ms processing file system events since last build\n/, '')
            .replaceAll(/Watching \d+ (directory hierarchies to track changes between builds in \d+ directories|directories to track changes between builds)\n/, '')
            .replaceAll(/Spent \d+ ms registering watches for file system events\n/, '')
            .replaceAll(/Virtual file system .*\n/, '')
    }

    def "can request to recreate the cache"() {
        given:
        def configurationCache = newConfigurationCacheFixture()

        when:
        configurationCacheRun "help", "-D${ConfigurationCacheRecreateOption.PROPERTY_NAME}=true"

        then:
        configurationCache.assertStateStored()

        when:
        configurationCacheRun "help", "-D${ConfigurationCacheRecreateOption.PROPERTY_NAME}=true"

        then:
        configurationCache.assertStateStored()
        outputContains("Recreating configuration cache")
    }

    def "restores some details of the project structure"() {
        def fixture = new BuildOperationsFixture(executer, temporaryFolder)

        settingsFile << """
            rootProject.name = 'thing'
            include 'a', 'b', 'c'
            include 'a:b'
            project(':a:b').projectDir = file('custom')
            gradle.rootProject {
                allprojects {
                    task thing
                }
            }
        """

        when:
        configurationCacheRun "help"

        then:
        def op1 = fixture.only(LoadProjectsBuildOperationType)
        op1.result.rootProject.name == 'thing'
        op1.result.rootProject.path == ':'
        op1.result.rootProject.children.size() == 3 // All projects are created when storing

        def events1 = fixture.progress(ProjectsIdentifiedProgressDetails)
        events1.size() == 1
        events1[0].details.rootProject.name == 'thing'
        events1[0].details.rootProject.path == ':'
        events1[0].details.rootProject.children.size() == 3

        when:
        configurationCacheRun "help"

        then:
        def op2 = fixture.only(LoadProjectsBuildOperationType)
        op2.result.rootProject.name == 'thing'
        op2.result.rootProject.path == ':'
        op2.result.rootProject.projectDir == testDirectory.absolutePath
        op2.result.rootProject.children.empty // None of the child projects are created when loading, as they have no tasks scheduled

        def events2 = fixture.progress(ProjectsIdentifiedProgressDetails)
        events2.size() == 1
        events2[0].details.rootProject.name == 'thing'
        events2[0].details.rootProject.path == ':'
        events2[0].details.rootProject.projectDir == testDirectory.absolutePath
        events2[0].details.rootProject.children.empty

        when:
        configurationCacheRun ":a:thing"

        then:
        def op3 = fixture.only(LoadProjectsBuildOperationType)
        op3.result.rootProject.name == 'thing'
        op3.result.rootProject.children.size() == 3 // All projects are created when storing

        def events3 = fixture.progress(ProjectsIdentifiedProgressDetails)
        events3.size() == 1
        events3[0].details.rootProject.name == 'thing'
        events3[0].details.rootProject.children.size() == 3

        when:
        configurationCacheRun ":a:thing"

        then:
        def op4 = fixture.only(LoadProjectsBuildOperationType)
        op4.result.rootProject.name == 'thing'
        op4.result.rootProject.path == ':'
        op4.result.rootProject.projectDir == testDirectory.absolutePath
        op4.result.rootProject.children.size() == 1 // Only project a is created when loading
        def project1 = op4.result.rootProject.children.first()
        project1.name == 'a'
        project1.path == ':a'
        project1.projectDir == file('a').absolutePath
        project1.children.empty

        def events4 = fixture.progress(ProjectsIdentifiedProgressDetails)
        events4.size() == 1
        events4[0].details.rootProject.name == 'thing'
        events4[0].details.rootProject.path == ':'
        events4[0].details.rootProject.children.size() == 1
        def project2 = events4[0].details.rootProject.children.first()
        project2.name == 'a'
        project2.path == ':a'
        project2.projectDir == file('a').absolutePath
        project2.children.empty

        when:
        configurationCacheRun ":a:b:thing"

        then:
        def op5 = fixture.only(LoadProjectsBuildOperationType)
        op5.result.rootProject.name == 'thing'
        op5.result.rootProject.children.size() == 3 // All projects are created when storing

        def events5 = fixture.progress(ProjectsIdentifiedProgressDetails)
        events5.size() == 1
        events5[0].details.rootProject.name == 'thing'
        events5[0].details.rootProject.children.size() == 3

        when:
        configurationCacheRun ":a:b:thing"

        then:
        def op6 = fixture.only(LoadProjectsBuildOperationType)
        op6.result.rootProject.name == 'thing'
        op6.result.rootProject.path == ':'
        op6.result.rootProject.projectDir == testDirectory.absolutePath
        op6.result.rootProject.children.size() == 1
        def project3 = op6.result.rootProject.children.first()
        project3.name == 'a'
        project3.path == ':a'
        project3.projectDir == file('a').absolutePath
        project3.children.size() == 1
        def project4 = project3.children.first()
        project4.name == 'b'
        project4.path == ':a:b'
        project4.projectDir == file('custom').absolutePath

        def events6 = fixture.progress(ProjectsIdentifiedProgressDetails)
        events6.size() == 1
        events6[0].details.rootProject.name == 'thing'
        events6[0].details.rootProject.path == ':'
        events6[0].details.rootProject.children.size() == 1
        def project5 = events6[0].details.rootProject.children.first()
        project5.name == 'a'
        project5.path == ':a'
        project5.projectDir == file('a').absolutePath
        project5.children.size() == 1
        def project6 = project5.children.first()
        project6.name == 'b'
        project6.path == ':a:b'
        project6.projectDir == file('custom').absolutePath
    }

    def "does not configure build when task graph is already cached for requested tasks"() {

        def configurationCache = newConfigurationCacheFixture()

        given:
        buildFile << """
            println "running build script"

            class SomeTask extends DefaultTask {
                SomeTask() {
                    println("create task")
                }
            }
            task a(type: SomeTask) {
                println("configure task")
            }
            task b {
                dependsOn a
            }
        """

        when:
        configurationCacheRun "a"

        then:
        configurationCache.assertStateStored()
        outputContains("Calculating task graph as no configuration cache is available for tasks: a")
        outputContains("running build script")
        outputContains("create task")
        outputContains("configure task")
        result.assertTasksExecuted(":a")

        when:
        configurationCacheRun "a"

        then:
        configurationCache.assertStateLoaded()
        outputContains("Reusing configuration cache.")
        outputDoesNotContain("running build script")
        outputDoesNotContain("create task")
        outputDoesNotContain("configure task")
        result.assertTasksExecuted(":a")

        when:
        configurationCacheRun "b"

        then:
        configurationCache.assertStateStored()
        outputContains("Calculating task graph as no configuration cache is available for tasks: b")
        outputContains("running build script")
        outputContains("create task")
        outputContains("configure task")
        result.assertTasksExecuted(":a", ":b")

        when:
        configurationCacheRun "a"

        then:
        configurationCache.assertStateLoaded()
        outputContains("Reusing configuration cache.")
        outputDoesNotContain("running build script")
        outputDoesNotContain("create task")
        outputDoesNotContain("configure task")
        result.assertTasksExecuted(":a")
    }

    def "configuration cache for multi-level projects"() {
        given:
        settingsFile << """
            include 'a:b', 'a:c'
        """
        configurationCacheRun ":a:b:help", ":a:c:help"
        def firstRunOutput = result.groupedOutput

        when:
        configurationCacheRun ":a:b:help", ":a:c:help"

        then:
        result.groupedOutput.task(":a:b:help").output == firstRunOutput.task(":a:b:help").output
        result.groupedOutput.task(":a:c:help").output == firstRunOutput.task(":a:c:help").output
    }

    def "captures changes applied in task graph whenReady listener"() {
        buildFile << """
            class SomeTask extends DefaultTask {
                @Internal
                String value

                @TaskAction
                void run() {
                    println "value = " + value
                }
            }

            task ok(type: SomeTask)

            gradle.taskGraph.whenReady {
                ok.value = 'value'
            }
        """

        when:
        configurationCacheRun "ok"
        configurationCacheRun "ok"

        then:
        outputContains("value = value")
    }

    def "can init two projects in a row"() {
        def configurationCache = new ConfigurationCacheFixture(this)
        when:
        useTestDirectoryThatIsNotEmbeddedInAnotherBuild()
        configurationCacheRun "init", "--dsl", "groovy", "--type", "basic"

        then:
        outputContains("> Task :init")
        configurationCache.assertStateStoredAndDiscarded {
            assert totalProblems == 0
        }
        succeeds 'properties'
        def projectName1 = testDirectory.name
        outputContains("name: ${projectName1}")

        when:
        useTestDirectoryThatIsNotEmbeddedInAnotherBuild()
        configurationCacheRun "init", "--dsl", "groovy", "--type", "basic"

        then:
        outputContains("> Task :init")
        succeeds 'properties'
        def projectName2 = testDirectory.name
        outputContains("name: ${projectName2}")
        projectName1 != projectName2
    }
}
