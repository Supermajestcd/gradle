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

package org.gradle.internal.properties.annotations;

import com.google.common.collect.ImmutableSet;
import org.gradle.api.internal.tasks.TaskDependencyContainer;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Nested;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.properties.PropertyValue;
import org.gradle.internal.properties.PropertyVisitor;

import javax.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.Collection;

public class NestedBeanAnnotationHandler extends AbstractPropertyAnnotationHandler {
    public NestedBeanAnnotationHandler(Collection<Class<? extends Annotation>> allowedModifiers) {
        super(Nested.class, Kind.OTHER, ImmutableSet.copyOf(allowedModifiers));
    }

    @Override
    public boolean isPropertyRelevant() {
        return true;
    }

    @Override
    public void visitPropertyValue(String propertyName, PropertyValue value, PropertyMetadata propertyMetadata, PropertyVisitor visitor, BeanPropertyContext context) {
        Object nested;
        try {
            nested = unpackProvider(value.call());
        } catch (Exception e) {
            visitor.visitInputProperty(propertyName, new InvalidValue(e), false);
            return;
        }
        if (nested != null) {
            context.addNested(nested);
        } else if (getAllowedModifiers().stream().noneMatch(propertyMetadata::isAnnotationPresent)) {
            visitor.visitInputProperty(propertyName, PropertyValue.ABSENT, false);
        }
    }

    @Nullable
    private static Object unpackProvider(@Nullable Object value) {
        // Only unpack one level of Providers, since Provider<Provider<>> is not supported - we don't need two levels of laziness.
        if (value instanceof Provider) {
            return ((Provider<?>) value).getOrNull();
        }
        return value;
    }

    private static class InvalidValue implements PropertyValue {
        private final Exception exception;

        public InvalidValue(Exception exception) {
            this.exception = exception;
        }

        @Nullable
        @Override
        public Object call() {
            throw UncheckedException.throwAsUncheckedException(exception);
        }

        @Override
        public TaskDependencyContainer getTaskDependencies() {
            // Ignore
            return TaskDependencyContainer.EMPTY;
        }

        @Override
        public void maybeFinalizeValue() {
            // Ignore
        }

        @Override
        public String toString() {
            return "INVALID: " + exception.getMessage();
        }
    }
}
