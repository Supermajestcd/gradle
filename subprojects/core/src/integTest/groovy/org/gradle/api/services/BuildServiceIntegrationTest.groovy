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

package org.gradle.api.services

import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ProjectLayout
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.services.internal.BuildServiceProvider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.initialization.StartParameterBuildOptions.ConfigurationCacheOption
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.RequiredFeature
import org.gradle.integtests.fixtures.UnsupportedWithConfigurationCache
import org.gradle.integtests.fixtures.configurationcache.ConfigurationCacheTest
import org.gradle.internal.reflect.Instantiator
import org.gradle.process.ExecOperations
import spock.lang.Issue

import javax.inject.Inject

import static org.hamcrest.CoreMatchers.containsString

@ConfigurationCacheTest
class BuildServiceIntegrationTest extends AbstractIntegrationSpec {

    def "does not nag when service is used by task without a corresponding usesService call and feature preview is NOT enabled"() {
        given:
        serviceImplementation()
        adhocTaskUsingUndeclaredService(1)

        when:
        succeeds 'broken'

        then:
        outputDoesNotContain "'Task#usesService'"
    }

    def "does not nag when an unconstrained service is used by task without a corresponding usesService call and feature preview is enabled"() {
        given:
        serviceImplementation()
        adhocTaskUsingUndeclaredService(null)

        when:
        succeeds 'broken'

        then:
        outputDoesNotContain "'Task#usesService'"
    }

    def "does nag when service is used by task without a corresponding usesService call and feature preview is enabled"() {
        given:
        serviceImplementation()
        adhocTaskUsingUndeclaredService(1)
        enableStableConfigurationCache()
        executer.expectDocumentedDeprecationWarning(
            "Build service 'counter' is being used by task ':broken' without the corresponding declaration via 'Task#usesService'. " +
                "This behavior has been deprecated. " +
                "This will fail with an error in Gradle 8.0. " +
                "Declare the association between the task and the build service using 'Task#usesService'. " +
                "Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_7.html#undeclared_build_service_usage"
        )

        expect:
        succeeds 'broken'
    }

    @Issue("https://github.com/gradle/configuration-cache/issues/97")
    def "does nag when service is used indirectly via another service even if task declares service reference and feature preview is enabled"() {
        given:
        serviceImplementation()
        buildFile """
            abstract class WrapperService implements ${BuildService.name}<${BuildServiceParameters.None.name}> {
                @${ServiceReference.name}('counter')
                abstract Property<CountingService> getCounter()

                public void incrementIndirectly() {
                    counter.get().increment()
                }
            }
            abstract class Consumer extends DefaultTask {
                @${ServiceReference.name}('wrapper')
                abstract Property<WrapperService> getWrapper()
                @TaskAction
                def go() {
                    wrapper.get().incrementIndirectly()
                }
            }

            gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
                maxParallelUsages = 1
            }

            gradle.sharedServices.registerIfAbsent("wrapper", WrapperService) {
                maxParallelUsages = 1
            }

            task broken(type: Consumer) {
                // reference will be set by name
            }
        """
        enableStableConfigurationCache()
        executer.expectDocumentedDeprecationWarning(
            "Build service 'counter' is being used by task ':broken' without the corresponding declaration via 'Task#usesService'. " +
                "This behavior has been deprecated. " +
                "This will fail with an error in Gradle 8.0. " +
                "Declare the association between the task and the build service using 'Task#usesService'. " +
                "Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_7.html#undeclared_build_service_usage"
        )

        expect:
        succeeds 'broken'
    }

    @Issue("https://github.com/gradle/configuration-cache/issues/156")
    def "does nag when service is used by artifact transform parameters and feature preview is enabled"() {
        given:
        serviceImplementation()
        buildFile """
            plugins {
                id('java-library')
            }
            abstract class CounterTransform implements $TransformAction.name<Parameters> {
                interface Parameters extends $TransformParameters.name {
                    @$ServiceReference.name("counter")
                    abstract Property<CountingService> getCounter()
                }

                @Override
                void transform($TransformOutputs.name outputs) {
                    println "Transforming ${UUID.randomUUID()}"
                    parameters.counter.get().increment()
                }
            }

            gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
                maxParallelUsages = 1
            }

            def artifactType = Attribute.of('artifactType', String)
            def counted = Attribute.of('counted', Boolean)

            repositories {
                mavenCentral()
            }

            configurations.all {
                afterEvaluate {
                    attributes.attribute(counted, true)
                }
            }

            dependencies {
                attributesSchema {
                    attribute(artifactType)
                }

                artifactTypes.getByName("jar") {
                    attributes.attribute(counted, false)
                }
            }

            dependencies {
                registerTransform(CounterTransform) {
                    from.attribute(counted, false).attribute(artifactType, "jar")
                    to.attribute(counted, true).attribute(artifactType, "jar")
                }
            }

            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.10'
            }
        """
        file("src/main/java").createDir()
        file("src/main/java/Foo.java").createFile().text = """class Foo {}"""
        enableStableConfigurationCache()
        // should not be expected
        executer.expectDocumentedDeprecationWarning(
            "Build service 'counter' is being used by task ':compileJava' without the corresponding declaration via 'Task#usesService'. " +
                "This behavior has been deprecated. " +
                "This will fail with an error in Gradle 8.0. " +
                "Declare the association between the task and the build service using 'Task#usesService'. " +
                "Consult the upgrading guide for further information: https://docs.gradle.org/current/userguide/upgrading_version_7.html#undeclared_build_service_usage"
        )

        when:
        succeeds 'build'

        then:
        outputContains "Transforming"
        outputContains """
service: created with value = 10
service: value is 11
        """
        outputContains """
service: closed with value 11
        """
    }

    def "does not nag when service is used by task with an explicit usesService call and feature preview is enabled"() {
        given:
        serviceImplementation()
        customTaskUsingServiceViaProperty()
        buildFile """
            def serviceProvider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 42
            }

            tasks.register("explicit") {
                it.usesService(serviceProvider)
                doFirst {
                    serviceProvider.get().increment()
                }
            }
        """
        enableStableConfigurationCache()

        when:
        succeeds 'explicit'

        then:
        outputDoesNotContain "'Task#usesService'"
    }

    def "does not nag when service is annotated with @ServiceReference (unnamed) and feature preview is enabled"() {
        given:
        serviceImplementation()
        customTaskUsingServiceViaProperty("@${ServiceReference.name}()")
        buildFile """
            def provider = gradle.sharedServices.registerIfAbsent("counterService", CountingService) {
                parameters.initial = 10
                maxParallelUsages = 1
            }

            task implicit(type: Consumer) {
                counter.convention(provider)
            }
        """
        enableStableConfigurationCache()

        when:
        succeeds 'implicit'

        then:
        outputDoesNotContain "'Task#usesService'"
    }

    def "can inject shared build service by name into nested bean property when reference is annotated with @ServiceReference('...')"() {
        given:
        serviceImplementation()
        buildFile """
            abstract class NestedBean {
                @${ServiceReference.name}('counter')
                abstract Property<CountingService> getCounter()
            }
            abstract class Consumer extends DefaultTask {
                @${Nested.name}
                final Property<NestedBean> nestedCounter = project.objects.property(NestedBean)

                @TaskAction
                def go() {
                    assert nestedCounter.present
                    nestedCounter.get().counter.get().increment()
                }
            }

            gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
                maxParallelUsages = 1
            }

            task named(type: Consumer) {
                // reference will be set by name
                nestedCounter.convention(providers.provider { objects.newInstance(NestedBean) } )
                doLast {
                    assert requiredServices.elements.any { service ->
                        service instanceof ${BuildServiceProvider.name}
                    }
                }
            }
        """
        enableStableConfigurationCache()

        when:
        succeeds 'named'

        then:
        outputDoesNotContain "'Task#usesService'"
        outputContains """
service: created with value = 10
service: value is 11
service: closed with value 11
        """
    }

    def "can inject shared build service by name when reference is annotated with @ServiceReference('...')"() {
        given:
        serviceImplementation()
        customTaskUsingServiceViaProperty("@${ServiceReference.name}('counter')")
        buildFile """
            gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
                maxParallelUsages = 1
            }

            task named(type: Consumer) {
                // reference will be set by name
                doLast {
                    assert requiredServices.elements.any { service ->
                        service.type == CountingService
                    }
                }
            }
        """
        enableStableConfigurationCache()

        when:
        succeeds 'named'

        then:
        outputDoesNotContain "'Task#usesService'"
        outputContains """
service: created with value = 10
service: value is 11
service: closed with value 11
        """
    }

    def "injection by name can be overridden by explicit convention"() {
        given:
        serviceImplementation()
        customTaskUsingServiceViaProperty("@${ServiceReference.name}('counter')")
        buildFile """
            gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
                maxParallelUsages = 1
            }
            def counterProvider2 = gradle.sharedServices.registerIfAbsent("counter2", CountingService) {
                parameters.initial = 10000
                maxParallelUsages = 1
            }

            task named(type: Consumer) {
                // override service with an explicit assignment
                counter.set(counterProvider2)
            }
        """
        enableStableConfigurationCache()

        when:
        succeeds 'named'

        then:
        outputDoesNotContain "'Task#usesService'"
        outputContains """
service: created with value = 10000
service: value is 10001
service: closed with value 10001
        """
    }

    def "injection by name works at configuration time"() {
        given:
        serviceImplementation()
        customTaskUsingServiceViaProperty("@${ServiceReference.name}('counter')")
        buildFile """
            gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
                maxParallelUsages = 1
            }

            task named(type: Consumer) {
                counter.get().increment()
            }
        """
        enableStableConfigurationCache()

        when:
        succeeds 'named'

        then:
        outputDoesNotContain "'Task#usesService'"
        outputContains """
> Configure project :
service: created with value = 10
service: value is 11

> Task :named
service: value is 12
service: closed with value 12
        """
    }

    def "injection by name fails validation if required service is not found, even if not used"() {
        given:
        serviceImplementation()
        buildFile """
            abstract class Consumer extends DefaultTask {
                @${ServiceReference.name}('counter')
                abstract Property<CountingService> getCounter()

                @TaskAction
                def go() {
                    println("Service is not used")
                }
            }
            task missingRequiredService(type: Consumer) {}
        """
        enableStableConfigurationCache()

        when:
        fails 'missingRequiredService'

        then:
        failureDescriptionContains("A problem was found with the configuration of task ':missingRequiredService' (type 'Consumer').")
        failureDescriptionContains("- Type 'Consumer' property 'counter' doesn't have a configured value.")
        failureDescriptionContains("Reason: This property isn't marked as optional and no value has been configured.")
    }

    def "injection by name does not fail validation if service is not found but property marked as @Optional"() {
        given:
        serviceImplementation()
        buildFile """
            abstract class Consumer extends DefaultTask {
                @${Optional.name}
                @${ServiceReference.name}('counter')
                abstract Property<CountingService> getCounter()

                @TaskAction
                def go() {
                    if (counter.getOrNull() == null) {
                        println("Skipping counting as service not available")
                    }
                }
            }
            task missingOptionalService(type: Consumer) {}
        """
        enableStableConfigurationCache()

        when:
        succeeds 'missingOptionalService'

        then:
        outputContains("Skipping counting as service not available")
    }

    def "injection by name fails if optional service is not found but used"() {
        given:
        serviceImplementation()
        customTaskUsingServiceViaProperty("""
            @${Optional.name}
            @${ServiceReference.name}('oneCounter')
        """)
        buildFile """
            gradle.sharedServices.registerIfAbsent("anotherCounter", CountingService) {
                parameters.initial = 10
                maxParallelUsages = 1
            }

            task missingService(type: Consumer) {
                // expect service to be injected by name (it won't though)
            }
        """
        enableStableConfigurationCache()

        when:
        fails 'missingService'

        then:
        failure.assertHasDescription("Execution failed for task ':missingService'")
        failure.assertHasCause("Cannot query the value of task ':missingService' property 'counter' because it has no value available.")
    }

    def "@ServiceReference property must implement BuildService"() {
        given:
        buildFile """
            abstract class CountingService {}
            abstract class Consumer extends DefaultTask {
                @ServiceReference
                abstract Property<CountingService> getCounter()
                @TaskAction
                def go() {
                    //
                }
            }
            task invalidServiceType(type: Consumer) {}
        """
        enableStableConfigurationCache()

        when:
        fails 'invalidServiceType'

        then:
        failure.assertThatDescription(containsString(
            "Type 'Consumer' property 'counter' has @ServiceReference annotation used on property of type 'CountingService' which is not a build service implementation."
        ))
    }

    def "service is created once per build on first use and stopped at the end of the build"() {
        serviceImplementation()
        customTaskUsingServiceViaProperty()
        buildFile """
            def provider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
            }

            task first(type: Consumer) {
                counter = provider
            }

            task second(type: Consumer) {
                counter = provider
            }
        """

        when:
        run("first", "second")

        then:
        output.count("service:") == 4
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: value is 12")
        outputContains("service: closed with value 12")

        when:
        run("first", "second")

        then:
        output.count("service:") == 4
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: value is 12")
        outputContains("service: closed with value 12")

        when:
        run("help")

        then:
        result.assertNotOutput("service:")

        when:
        run("help")

        then:
        result.assertNotOutput("service:")
    }

    def "can use service from task doFirst() or doLast() action"() {
        serviceImplementation()
        buildFile << """
            def provider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
            }

            task first {
                doFirst {
                    provider.get().increment()
                }
            }

            task second {
                doLast {
                    provider.get().increment()
                }
            }
        """

        when:
        run("first", "second")

        then:
        output.count("service:") == 4
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: value is 12")
        outputContains("service: closed with value 12")

        when:
        run("first", "second")

        then:
        output.count("service:") == 4
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: value is 12")
        outputContains("service: closed with value 12")
    }

    def "tasks can use mapped value of service"() {
        serviceImplementation()
        buildFile << """
            def provider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
            }

            def count = provider.map { it.increment() + 10 }

            task first {
                doFirst {
                    println("got value = " + count.get())
                }
            }

            task second {
                doFirst {
                    println("got value = " + count.get())
                }
            }
        """

        when:
        run("first", "second")

        then:
        output.count("service:") == 4
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("got value = 21")
        outputContains("service: value is 12")
        outputContains("got value = 22")
        outputContains("service: closed with value 12")

        when:
        run("first", "second")

        then:
        output.count("service:") == 4
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("got value = 21")
        outputContains("service: value is 12")
        outputContains("got value = 22")
        outputContains("service: closed with value 12")
    }

    @RequiredFeature(feature = ConfigurationCacheOption.PROPERTY_NAME, value = "false")
    @UnsupportedWithConfigurationCache
    def "service can be used at configuration and execution time"() {
        serviceImplementation()
        buildFile << """
            def provider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
            }

            task count {
                doFirst {
                    provider.get().increment()
                }
            }

            provider.get().increment()
        """

        when:
        run("count")

        then:
        output.count("service:") == 4
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: value is 12")
        outputContains("service: closed with value 12")

        when:
        run("count")

        then:
        output.count("service:") == 4
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: value is 12")
        outputContains("service: closed with value 12")

        when:
        run("help")

        then:
        output.count("service:") == 3
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: closed with value 11")
    }

    @RequiredFeature(feature = ConfigurationCacheOption.PROPERTY_NAME, value = "true")
    def "service used at configuration and execution time can be used with configuration cache"() {
        serviceImplementation()
        buildFile << """
            def provider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
            }

            task count {
                doFirst {
                    provider.get().increment()
                }
            }

            provider.get().increment()
        """

        when:
        run("count")

        then:
        output.count("service:") == 4
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: value is 12")
        outputContains("service: closed with value 12")

        when:
        run("count")

        then:
        output.count("service:") == 3
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: closed with value 11")

        when:
        run("help")

        then:
        output.count("service:") == 3
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: closed with value 11")

        when:
        run("help")

        then:
        result.assertNotOutput("service:")
    }

    def "plugin applied to multiple projects can register a shared service"() {
        settingsFile << "include 'a', 'b', 'c'"
        serviceImplementation()
        buildFile << """
            class CounterPlugin implements Plugin<Project> {
                void apply(Project project) {
                    def provider = project.gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                        parameters.initial = 10
                    }
                    project.tasks.register("count") {
                        doFirst {
                            provider.get().increment()
                        }
                    }
                }
            }
            subprojects {
                apply plugin: CounterPlugin
            }
        """

        when:
        run("count")

        then:
        output.count("service:") == 5
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: value is 12")
        outputContains("service: value is 13")
        outputContains("service: closed with value 13")

        when:
        run("count")

        then:
        output.count("service:") == 5
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: value is 12")
        outputContains("service: value is 13")
        outputContains("service: closed with value 13")
    }

    def "plugin can apply conventions to shared services of a given type"() {
        serviceImplementation()
        buildFile << """
            class CounterConventionPlugin implements Plugin<Project> {
                void apply(Project project) {
                    project.gradle.sharedServices.registrations.configureEach {
                        if (parameters instanceof CountingParams) {
                            parameters.initial = parameters.initial.get() + 5
                        }
                    }
                }
            }
            apply plugin: CounterConventionPlugin

            def counter1 = project.gradle.sharedServices.registerIfAbsent("counter1", CountingService) {
                parameters.initial = 0
            }
            def counter2 = project.gradle.sharedServices.registerIfAbsent("counter2", CountingService) {
                parameters.initial = 10
            }
            task count {
                doLast {
                    counter1.get().increment()
                    counter2.get().increment()
                }
            }
        """

        when:
        run("count")

        then:
        output.count("service:") == 6
        outputContains("service: created with value = 5")
        outputContains("service: created with value = 15")
        outputContains("service: value is 6")
        outputContains("service: value is 16")
        outputContains("service: closed with value 6")
        outputContains("service: closed with value 16")

        when:
        run("count")

        then:
        output.count("service:") == 6
        outputContains("service: created with value = 5")
        outputContains("service: created with value = 15")
        outputContains("service: value is 6")
        outputContains("service: value is 16")
        outputContains("service: closed with value 6")
        outputContains("service: closed with value 16")
    }

    def "service parameters are isolated when the service is instantiated"() {
        serviceImplementation()
        buildFile << """
            def params

            def provider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                params = parameters
                parameters.initial = 10
            }

            assert params.initial.get() == 10
            params.initial = 12

            task first {
                doFirst {
                    params.initial = 15 // should have an effect
                    provider.get().reset()
                    params.initial = 1234 // should be ignored. Ideally should fail too
                }
            }

            task second {
                dependsOn first
                doFirst {
                    provider.get().increment()
                    params.initial = 456 // should be ignored
                }
            }
        """

        when:
        run("first", "second")

        then:
        output.count("service:") == 4
        outputContains("service: created with value = 15")
        outputContains("service: value is 15")
        outputContains("service: value is 16")
        outputContains("service: closed with value 16")

        when:
        run("first", "second")

        then:
        output.count("service:") == 4
        outputContains("service: created with value = 15")
        outputContains("service: value is 15")
        outputContains("service: value is 16")
        outputContains("service: closed with value 16")
    }

    def "service can take no parameters"() {
        noParametersServiceImplementation()
        buildFile << """
            def provider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {}

            task first {
                doFirst {
                    provider.get().increment()
                }
            }

            task second {
                doFirst {
                    provider.get().increment()
                }
            }
        """

        when:
        run("first", "second")

        then:
        output.count("service:") == 3
        outputContains("service: created with value = 0")
        outputContains("service: value is 1")
        outputContains("service: value is 2")

        when:
        run("first", "second")

        then:
        output.count("service:") == 3
        outputContains("service: created with value = 0")
        outputContains("service: value is 1")
        outputContains("service: value is 2")
    }

    def "service can take another service as a parameter"() {
        serviceImplementation()
        buildFile << """
            interface ForwardingParams extends BuildServiceParameters {
                Property<CountingService> getService()
            }

            abstract class ForwardingService implements BuildService<ForwardingParams> {
                void increment() {
                    println("delegating to counting service")
                    parameters.service.get().increment()
                }
            }

            def countingService = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
            }
            def service = gradle.sharedServices.registerIfAbsent("service", ForwardingService) {
                parameters.service = countingService
            }

            task first {
                doFirst {
                    service.get().increment()
                }
            }

            task second {
                dependsOn first
                doFirst {
                    service.get().increment()
                }
            }
        """

        when:
        run("first", "second")

        then:
        output.count("delegating to counting service") == 2
        output.count("service:") == 4
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: value is 12")
        outputContains("service: closed with value 12")

        when:
        run("first", "second")

        then:
        output.count("delegating to counting service") == 2
        output.count("service:") == 4
        outputContains("service: created with value = 10")
        outputContains("service: value is 11")
        outputContains("service: value is 12")
        outputContains("service: closed with value 12")
    }

    def "can inject Gradle provided service #serviceType into build service"() {
        serviceWithInjectedService(serviceType)
        buildFile << """
            def provider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
            }

            task check {
                doFirst {
                    provider.get().increment()
                }
            }
        """

        expect:
        run("check")
        run("check")

        where:
        serviceType << [
            ExecOperations,
            FileSystemOperations,
            ObjectFactory,
            ProviderFactory,
        ].collect { it.name }
    }

    def "cannot inject Gradle provided service #serviceType into build service"() {
        serviceWithInjectedService(serviceType.name)
        buildFile << """
            def provider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
            }

            task check {
                doFirst {
                    provider.get().increment()
                }
            }
        """

        when:
        fails("check")

        then:
        failure.assertHasDescription("Execution failed for task ':check'.")
        failure.assertHasCause("Services of type ${serviceType.simpleName} are not available for injection into instances of type BuildService.")

        where:
        serviceType << [
            ProjectLayout, // not isolated
            Instantiator, // internal
        ]
    }

    def "injected FileSystemOperations resolves paths relative to build root directory"() {
        serviceCopiesFiles()
        buildFile << """
            def provider = gradle.sharedServices.registerIfAbsent("copier", CopyingService) {
            }

            task copy {
                doFirst {
                    provider.get().copy("a", "b")
                }
            }
        """

        file("a").createFile()
        def dest = file("b/a")
        assert !dest.file

        when:
        run("copy")

        then:
        dest.file
    }

    def "task cannot use build service for #annotationType property"() {
        serviceImplementation()
        customTaskUsingServiceViaProperty(annotationType)
        buildFile << """
            def provider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 10
            }

            task broken(type: Consumer) {
                counter = provider
                outputFile = layout.buildDirectory.file("out.txt")
            }
        """

        expect:
        fails("broken")

        // The failure is currently very specific to the annotation type
        // TODO  - fail earlier and add some expectations here

        fails("broken")

        where:
        annotationType << [
            Input,
            InputFile,
            InputDirectory,
            InputFiles,
            OutputDirectory,
            OutputDirectories,
            OutputFile,
            LocalState].collect { it.simpleName }
    }

    def "service is stopped even if build fails"() {
        serviceImplementation()
        buildFile << """
            def counter1 = project.gradle.sharedServices.registerIfAbsent("counter1", CountingService) {
                parameters.initial = 0
            }
            def counter2 = project.gradle.sharedServices.registerIfAbsent("counter2", CountingService) {
                parameters.initial = 10
            }
            task count {
                doLast {
                    counter1.get().increment()
                    throw new RuntimeException("broken")
                }
            }
        """

        when:
        fails("count")

        then:
        output.count("service:") == 3
        outputContains("service: created with value = 0")
        outputContains("service: value is 1")
        outputContains("service: closed with value 1")

        when:
        fails("count")

        then:
        output.count("service:") == 3
        outputContains("service: created with value = 0")
        outputContains("service: value is 1")
        outputContains("service: closed with value 1")
    }

    def "reports failure to create the service instance"() {
        brokenServiceImplementation()
        buildFile << """
            def provider1 = gradle.sharedServices.registerIfAbsent("counter1", CountingService) {
                parameters.initial = 10
            }
            def provider2 = gradle.sharedServices.registerIfAbsent("counter2", CountingService) {
                parameters.initial = 10
            }

            task first {
                doFirst {
                    provider1.get().increment()
                }
            }

            task second {
                doFirst {
                    provider2.get().increment()
                }
            }
        """

        when:
        fails("first", "second", "--continue")

        then:
        failure.assertHasFailures(2)
        failure.assertHasDescription("Execution failed for task ':first'.")
        failure.assertHasCause("Failed to create service 'counter1'.")
        failure.assertHasCause("Could not create an instance of type CountingService.")
        failure.assertHasCause("broken")
        failure.assertHasDescription("Execution failed for task ':second'.")
        failure.assertHasCause("Failed to create service 'counter2'.")
        failure.assertHasCause("Could not create an instance of type CountingService.")
        failure.assertHasCause("broken")

        when:
        fails("first", "second", "--continue")

        then:
        failure.assertHasFailures(2)
        failure.assertHasDescription("Execution failed for task ':first'.")
        failure.assertHasCause("Failed to create service 'counter1'.")
        failure.assertHasCause("Could not create an instance of type CountingService.")
        failure.assertHasCause("broken")
        failure.assertHasDescription("Execution failed for task ':second'.")
        failure.assertHasCause("Failed to create service 'counter2'.")
        failure.assertHasCause("Could not create an instance of type CountingService.")
        failure.assertHasCause("broken")
    }

    def "reports failure to stop the service instance"() {
        brokenStopServiceImplementation()
        buildFile << """
            def provider1 = gradle.sharedServices.registerIfAbsent("counter1", CountingService) {
                parameters.initial = 10
            }
            def provider2 = gradle.sharedServices.registerIfAbsent("counter2", CountingService) {
                parameters.initial = 10
            }

            task first {
                doFirst {
                    provider1.get().increment()
                }
            }

            task second {
                doFirst {
                    provider2.get().increment()
                }
            }
        """

        when:
        fails("first", "second")

        then:
        failure.assertHasFailure("Failed to stop service 'counter1'.") {
            it.assertHasCause("broken")
        }
        failure.assertHasFailure("Failed to stop service 'counter2'.") {
            it.assertHasCause("broken")
        }
    }

    private void enableStableConfigurationCache() {
        settingsFile '''
            enableFeaturePreview 'STABLE_CONFIGURATION_CACHE'
        '''
    }

    private void adhocTaskUsingUndeclaredService(Integer maxParallelUsages) {
        buildFile """
            def serviceProvider = gradle.sharedServices.registerIfAbsent("counter", CountingService) {
                parameters.initial = 42
                maxParallelUsages = $maxParallelUsages
            }

            tasks.register("broken") {
                doFirst {
                    serviceProvider.get().increment()
                }
            }
        """
    }

    private void customTaskUsingServiceViaProperty(String annotationSnippet = "@Internal") {
        buildFile << """
            abstract class Consumer extends DefaultTask {
                ${annotationSnippet}
                abstract Property<CountingService> getCounter()

                @TaskAction
                def go() {
                    counter.get().increment()
                }
            }
        """
    }

    def serviceImplementation() {
        buildFile """
            interface CountingParams extends BuildServiceParameters {
                Property<Integer> getInitial()
            }

            abstract class CountingService implements BuildService<CountingParams>, AutoCloseable {
                int value

                CountingService() {
                    value = parameters.initial.get()
                    println("service: created with value = \${value}")
                }

                synchronized int getInitialValue() { return parameters.initial.get() }

                // Service must be thread-safe
                synchronized void reset() {
                    value = parameters.initial.get()
                    println("service: value is \${value}")
                }

                // Service must be thread-safe
                synchronized int increment() {
                    value++
                    println("service: value is \${value}")
                    return value
                }

                void close() {
                    println("service: closed with value \${value}")
                }
            }
        """
    }

    def noParametersServiceImplementation() {
        buildFile << """
            abstract class CountingService implements BuildService<${BuildServiceParameters.name}.None> {
                int value

                CountingService() {
                    value = 0
                    println("service: created with value = \${value}")
                }

                // Service must be thread-safe
                synchronized int increment() {
                    value++
                    println("service: value is \${value}")
                    return value
                }
            }
        """
    }

    def serviceWithInjectedService(String serviceType) {
        buildFile << """
            import ${Inject.name}

            abstract class CountingService implements BuildService<${BuildServiceParameters.name}.None> {
                int value

                CountingService() {
                    value = 0
                    println("service: created with value = \${value}")
                }

                @Inject
                abstract ${serviceType} getInjectedService()

                // Service must be thread-safe
                synchronized int increment() {
                    assert injectedService != null
                    value++
                    return value
                }
            }
        """
    }

    def serviceCopiesFiles() {
        buildFile << """
            import ${Inject.name}

            abstract class CopyingService implements BuildService<${BuildServiceParameters.name}.None> {
                @Inject
                abstract FileSystemOperations getFiles()

                void copy(String source, String dest) {
                    files.copy {
                        it.from(source)
                        it.into(dest)
                    }
                }
            }
        """
    }

    def brokenServiceImplementation() {
        buildFile << """
            interface Params extends BuildServiceParameters {
                Property<Integer> getInitial()
            }

            abstract class CountingService implements BuildService<Params> {
                CountingService() {
                    throw new IOException("broken") // use a checked exception
                }

                void increment() {
                    throw new IOException("broken") // use a checked exception
                }
            }
        """
    }

    def brokenStopServiceImplementation() {
        buildFile << """
            interface Params extends BuildServiceParameters {
                Property<Integer> getInitial()
            }

            abstract class CountingService implements BuildService<Params>, AutoCloseable {
                CountingService() {
                }

                void increment() {
                }

                void close() {
                    throw new IOException("broken") // use a checked exception
                }
            }
        """
    }
}
