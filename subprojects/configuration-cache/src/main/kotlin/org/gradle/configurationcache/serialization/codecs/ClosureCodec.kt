/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.configurationcache.serialization.codecs

import groovy.lang.Closure
import groovy.lang.GroovyObjectSupport
import org.gradle.configurationcache.problems.DocumentationSection
import org.gradle.configurationcache.problems.PropertyProblem
import org.gradle.configurationcache.problems.PropertyTrace
import org.gradle.configurationcache.problems.StructuredMessage
import org.gradle.configurationcache.serialization.Codec
import org.gradle.configurationcache.serialization.IsolateContext
import org.gradle.configurationcache.serialization.ReadContext
import org.gradle.configurationcache.serialization.WriteContext
import org.gradle.internal.metaobject.ConfigureDelegate


object ClosureCodec : Codec<Closure<*>> {
    override suspend fun WriteContext.encode(value: Closure<*>) {
        writeReference(unpackOwner(value))
        writeReference(value.delegate)
        writeReference(value.thisObject)
        BeanCodec.run { encode(value.dehydrate()) }
    }

    private
    fun unpackOwner(closure: Closure<*>): Any? {
        val owner = closure.owner
        return if (owner is ConfigureDelegate) {
            owner._original_owner()
        } else {
            owner
        }
    }

    private
    suspend fun WriteContext.writeReference(value: Any?) {
        when (value) {
            is org.gradle.api.Script -> {
                // Cannot warn about an unsupported type here, because we don't know whether the closure will attempt to use the script object
                // and almost every closure in a Groovy build script legitimately has the script as an owner
                // So instead, warn when the script object is used by the closure when executing
                write(ScriptReference())
            }

            is Closure<*> -> {
                write(value)
            }

            else -> {
                // Discard the value for now
                write(null)
            }
        }
    }

    override suspend fun ReadContext.decode(): Closure<*> {
        val owner = readReference()
        val delegate = readReference()
        val thisObject = readReference()
        return BeanCodec.run {
            decode() as Closure<*>
        }.rehydrate(delegate, owner, thisObject)
    }

    private
    suspend fun ReadContext.readReference(): Any? {
        val reference = read()
        val trace = trace
        return if (reference is ScriptReference) {
            BrokenScript(trace, this)
        } else {
            reference
        }
    }

    private
    class ScriptReference

    private
    class BrokenScript(private val trace: PropertyTrace, private val context: IsolateContext): GroovyObjectSupport() {
        override fun getProperty(propertyName: String): Any? {
            reportReference()
            throw IllegalStateException("Cannot reference a Gradle script object from a Groovy closure as these are not supported with the configuration cache.")
        }

        override fun setProperty(propertyName: String, newValue: Any?) {
            reportReference()
            throw IllegalStateException("Cannot reference a Gradle script object from a Groovy closure as these are not supported with the configuration cache.")
        }

        override fun invokeMethod(name: String, args: Any): Any {
            reportReference()
            throw IllegalStateException("Cannot reference a Gradle script object from a Groovy closure as these are not supported with the configuration cache.")
        }

        private fun reportReference() {
            context.onProblem(PropertyProblem(
                trace,
                StructuredMessage.build {
                    text("cannot reference a Gradle script object from a Groovy closure as these are not support with the configuration cache.")
                },
                null,
                DocumentationSection.RequirementsDisallowedTypes
            ))
        }
    }
}
