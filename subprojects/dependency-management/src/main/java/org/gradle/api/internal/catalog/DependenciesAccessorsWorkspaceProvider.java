/*
 * Copyright 2021 the original author or authors.
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
package org.gradle.api.internal.catalog;

import org.gradle.api.internal.cache.StringInterner;
import org.gradle.cache.internal.CleanupActionDecorator;
import org.gradle.api.cache.CacheConfigurations;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.cache.scopes.BuildTreeScopedCache;
import org.gradle.internal.execution.workspace.WorkspaceProvider;
import org.gradle.internal.execution.workspace.impl.DefaultImmutableWorkspaceProvider;
import org.gradle.internal.file.FileAccessTimeJournal;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;

import java.io.Closeable;

public class DependenciesAccessorsWorkspaceProvider implements WorkspaceProvider, Closeable {
    private final DefaultImmutableWorkspaceProvider delegate;

    public DependenciesAccessorsWorkspaceProvider(
        BuildTreeScopedCache scopedCache,
        FileAccessTimeJournal fileAccessTimeJournal,
        InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory,
        StringInterner stringInterner,
        ClassLoaderHierarchyHasher classLoaderHasher,
        CleanupActionDecorator cleanupActionDecorator,
        CacheConfigurations cacheConfigurations
    ) {
        this.delegate = DefaultImmutableWorkspaceProvider.withBuiltInHistory(
            scopedCache
                .cache("dependencies-accessors")
                .withDisplayName("dependencies-accessors"),
            fileAccessTimeJournal,
            inMemoryCacheDecoratorFactory,
            stringInterner,
            classLoaderHasher,
            cleanupActionDecorator,
            cacheConfigurations
        );
    }

    @Override
    public <T> T withWorkspace(String path, WorkspaceAction<T> action) {
        return delegate.withWorkspace(path, action);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
