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

package org.gradle.internal.reflect.annotations.impl;

import com.google.common.collect.ImmutableMap;
import org.gradle.internal.Cast;
import org.gradle.internal.reflect.annotations.AnnotationCategory;
import org.gradle.internal.reflect.annotations.PropertyAnnotationMetadata;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Optional;

public class DefaultPropertyAnnotationMetadata implements PropertyAnnotationMetadata {
    private final String propertyName;
    private final Method method;
    private final ImmutableMap<AnnotationCategory, Annotation> annotationsByCategory;
    private final ImmutableMap<Class<? extends Annotation>, Annotation> annotationsByType;

    public DefaultPropertyAnnotationMetadata(String propertyName, Method method, ImmutableMap<AnnotationCategory, Annotation> annotationsByCategory) {
        this.propertyName = propertyName;
        this.method = method;
        this.annotationsByCategory = annotationsByCategory;
        this.annotationsByType = collectAnnotationsByType(annotationsByCategory);
    }

    private static ImmutableMap<Class<? extends Annotation>, Annotation> collectAnnotationsByType(ImmutableMap<AnnotationCategory, Annotation> annotations) {
        ImmutableMap.Builder<Class<? extends Annotation>, Annotation> builder = ImmutableMap.builderWithExpectedSize(annotations.size());
        for (Annotation value : annotations.values()) {
            builder.put(value.annotationType(), value);
        }
        return builder.build();
    }

    @Override
    public Method getMethod() {
        return method;
    }

    @Override
    public String getPropertyName() {
        return propertyName;
    }

    @Override
    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return annotationsByType.containsKey(annotationType);
    }

    @Override
    public <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationType) {
        return Optional.ofNullable(Cast.uncheckedCast(annotationsByType.get(annotationType)));
    }

    @Override
    public ImmutableMap<AnnotationCategory, Annotation> getAnnotations() {
        return annotationsByCategory;
    }

    @Override
    public int compareTo(@Nonnull PropertyAnnotationMetadata o) {
        return method.getName().compareTo(o.getMethod().getName());
    }

    @Override
    public String toString() {
        return String.format("%s / %s()", propertyName, method.getName());
    }
}
