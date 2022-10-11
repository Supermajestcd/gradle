/*
 * Copyright 2022 the original author or authors.
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

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.factories;

import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec;

public abstract class AbstractIntersection<L extends ExcludeSpec, R extends ExcludeSpec> implements Intersection<L, R> {
    private final Class<L> leftType;
    private final Class<R> rightType;
    private final ExcludeFactory factory;

    protected AbstractIntersection(Class<L> leftType, Class<R> rightType, ExcludeFactory factory) {
        this.leftType = leftType;
        this.rightType = rightType;
        this.factory = factory;
    }

    @Override
    public Class<L> getLeftType() {
        return leftType;
    }

    @Override
    public Class<R> getRightType() {
        return rightType;
    }

    public ExcludeFactory getFactory() {
        return factory;
    }
}
