/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.internal.service.scopes;

import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.api.internal.DefaultClassPathProvider;
import org.gradle.api.internal.DefaultClassPathRegistry;
import org.gradle.api.internal.cache.CacheConfigurationsInternal;
import org.gradle.api.internal.cache.DefaultCacheConfigurations;
import org.gradle.api.internal.changedetection.state.DefaultFileAccessTimeJournal;
import org.gradle.api.internal.changedetection.state.GradleUserHomeScopeFileTimeStampInspector;
import org.gradle.api.internal.classpath.ModuleRegistry;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.temp.GradleUserHomeTemporaryFileProvider;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.api.internal.initialization.loadercache.ClassLoaderCache;
import org.gradle.api.internal.initialization.loadercache.DefaultClassLoaderCache;
import org.gradle.api.model.ObjectFactory;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.GlobalCache;
import org.gradle.cache.GlobalCacheLocations;
import org.gradle.cache.internal.CacheFactory;
import org.gradle.cache.internal.CrossBuildInMemoryCacheFactory;
import org.gradle.cache.internal.DefaultCacheRepository;
import org.gradle.cache.internal.DefaultFileContentCacheFactory;
import org.gradle.cache.internal.DefaultGeneratedGradleJarCache;
import org.gradle.cache.internal.DefaultGlobalCacheLocations;
import org.gradle.cache.internal.FileContentCacheFactory;
import org.gradle.cache.internal.GradleUserHomeCacheCleanupActionDecorator;
import org.gradle.cache.internal.GradleUserHomeCleanupServices;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.cache.internal.scopes.DefaultCacheScopeMapping;
import org.gradle.cache.internal.scopes.DefaultGlobalScopedCache;
import org.gradle.cache.scopes.GlobalScopedCache;
import org.gradle.groovy.scripts.internal.CrossBuildInMemoryCachingScriptClassCache;
import org.gradle.groovy.scripts.internal.DefaultScriptSourceHasher;
import org.gradle.groovy.scripts.internal.RegistryAwareClassLoaderHierarchyHasher;
import org.gradle.groovy.scripts.internal.ScriptSourceHasher;
import org.gradle.initialization.ClassLoaderRegistry;
import org.gradle.initialization.ClassLoaderScopeRegistry;
import org.gradle.initialization.ClassLoaderScopeRegistryListenerManager;
import org.gradle.initialization.DefaultClassLoaderScopeRegistry;
import org.gradle.initialization.GradleUserHomeDirProvider;
import org.gradle.initialization.layout.GlobalCacheDir;
import org.gradle.internal.classloader.ClasspathHasher;
import org.gradle.internal.classloader.DefaultHashingClassLoaderFactory;
import org.gradle.internal.classloader.HashingClassLoaderFactory;
import org.gradle.internal.classpath.ClasspathBuilder;
import org.gradle.internal.classpath.ClasspathWalker;
import org.gradle.internal.classpath.DefaultCachedClasspathTransformer;
import org.gradle.internal.classpath.DefaultClasspathTransformerCacheFactory;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.event.DefaultListenerManager;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.execution.timeout.TimeoutHandler;
import org.gradle.internal.execution.timeout.impl.DefaultTimeoutHandler;
import org.gradle.internal.file.FileAccessTimeJournal;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.id.LongIdGenerator;
import org.gradle.internal.jvm.JavaModuleDetector;
import org.gradle.internal.jvm.inspection.JvmVersionDetector;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.operations.CurrentBuildOperationRef;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.remote.MessagingServer;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.vfs.FileSystemAccess;
import org.gradle.process.internal.ExecFactory;
import org.gradle.process.internal.JavaExecHandleFactory;
import org.gradle.process.internal.health.memory.MemoryManager;
import org.gradle.process.internal.worker.DefaultWorkerProcessFactory;
import org.gradle.process.internal.worker.WorkerProcessFactory;
import org.gradle.process.internal.worker.child.WorkerProcessClassPathProvider;
import org.gradle.util.GradleVersion;

import java.util.List;

/**
 * Defines the shared services scoped to a particular Gradle user home directory. These services are reused across multiple builds and operations.
 */
public class GradleUserHomeScopeServices extends WorkerSharedUserHomeScopeServices {
    private final ServiceRegistry globalServices;

    public GradleUserHomeScopeServices(ServiceRegistry globalServices) {
        this.globalServices = globalServices;
    }

    public void configure(ServiceRegistration registration) {
        registration.add(GlobalCacheDir.class);
        registration.addProvider(new GradleUserHomeCleanupServices());
        registration.add(ClasspathWalker.class);
        registration.add(ClasspathBuilder.class);
        registration.add(GradleUserHomeTemporaryFileProvider.class);
        registration.add(DefaultClasspathTransformerCacheFactory.class);
        registration.add(GradleUserHomeScopeFileTimeStampInspector.class);
        registration.add(DefaultCachedClasspathTransformer.class);
        for (PluginServiceRegistry plugin : globalServices.getAll(PluginServiceRegistry.class)) {
            plugin.registerGradleUserHomeServices(registration);
        }
    }

    GradleUserHomeCacheCleanupActionDecorator createCacheCleanupEnablement(GradleUserHomeDirProvider gradleUserHomeDirProvider) {
        return new GradleUserHomeCacheCleanupActionDecorator(gradleUserHomeDirProvider);
    }

    CacheRepository createCacheRepository(GlobalCacheDir globalCacheDir, CacheFactory cacheFactory) {
        return new DefaultCacheRepository(new DefaultCacheScopeMapping(globalCacheDir.getDir(), GradleVersion.current()), cacheFactory);
    }

    DefaultGlobalScopedCache createGlobalScopedCache(GlobalCacheDir globalCacheDir, CacheRepository cacheRepository) {
        return new DefaultGlobalScopedCache(globalCacheDir.getDir(), cacheRepository);
    }

    DefaultListenerManager createListenerManager(DefaultListenerManager parent) {
        return parent.createChild(Scopes.UserHome.class);
    }

    ScriptSourceHasher createScriptSourceHasher() {
        return new DefaultScriptSourceHasher();
    }

    CrossBuildInMemoryCachingScriptClassCache createCachingScriptCompiler(CrossBuildInMemoryCacheFactory cacheFactory) {
        return new CrossBuildInMemoryCachingScriptClassCache(cacheFactory);
    }

    ClassLoaderHierarchyHasher createClassLoaderHierarchyHasher(ClassLoaderRegistry registry, HashingClassLoaderFactory classLoaderFactory) {
        return new RegistryAwareClassLoaderHierarchyHasher(registry, classLoaderFactory);
    }

    HashingClassLoaderFactory createClassLoaderFactory(ClasspathHasher classpathHasher) {
        return new DefaultHashingClassLoaderFactory(classpathHasher);
    }

    ClassLoaderCache createClassLoaderCache(HashingClassLoaderFactory classLoaderFactory, ClasspathHasher classpathHasher, ListenerManager listenerManager) {
        DefaultClassLoaderCache cache = new DefaultClassLoaderCache(classLoaderFactory, classpathHasher);
        listenerManager.addListener(cache);
        return cache;
    }

    protected ClassLoaderScopeRegistryListenerManager createClassLoaderScopeRegistryListenerManager(ListenerManager listenerManager) {
        return new ClassLoaderScopeRegistryListenerManager(listenerManager);
    }

    protected ClassLoaderScopeRegistry createClassLoaderScopeRegistry(
        ClassLoaderRegistry classLoaderRegistry,
        ClassLoaderCache classLoaderCache,
        ClassLoaderScopeRegistryListenerManager listenerManager
    ) {
        return new DefaultClassLoaderScopeRegistry(classLoaderRegistry, classLoaderCache, listenerManager.getBroadcaster());
    }

    GlobalCacheLocations createGlobalCacheLocations(List<GlobalCache> globalCaches) {
        return new DefaultGlobalCacheLocations(globalCaches);
    }

    ExecFactory createExecFactory(ExecFactory parent, FileResolver fileResolver, FileCollectionFactory fileCollectionFactory, Instantiator instantiator, ObjectFactory objectFactory, JavaModuleDetector javaModuleDetector) {
        return parent.forContext()
            .withFileResolver(fileResolver)
            .withFileCollectionFactory(fileCollectionFactory)
            .withInstantiator(instantiator)
            .withObjectFactory(objectFactory)
            .withJavaModuleDetector(javaModuleDetector)
            .build();
    }

    WorkerProcessFactory createWorkerProcessFactory(
        LoggingManagerInternal loggingManagerInternal, MessagingServer messagingServer, ClassPathRegistry classPathRegistry,
        TemporaryFileProvider temporaryFileProvider, JavaExecHandleFactory execHandleFactory, JvmVersionDetector jvmVersionDetector,
        MemoryManager memoryManager, GradleUserHomeDirProvider gradleUserHomeDirProvider, OutputEventListener outputEventListener
    ) {
        return new DefaultWorkerProcessFactory(
            loggingManagerInternal,
            messagingServer,
            classPathRegistry,
            new LongIdGenerator(),
            gradleUserHomeDirProvider.getGradleUserHomeDirectory(),
            temporaryFileProvider,
            execHandleFactory,
            jvmVersionDetector,
            outputEventListener,
            memoryManager
        );
    }

    ClassPathRegistry createClassPathRegistry(ModuleRegistry moduleRegistry, WorkerProcessClassPathProvider workerProcessClassPathProvider) {
        return new DefaultClassPathRegistry(
            new DefaultClassPathProvider(moduleRegistry),
            workerProcessClassPathProvider
        );
    }

    WorkerProcessClassPathProvider createWorkerProcessClassPathProvider(GlobalScopedCache cacheRepository, ModuleRegistry moduleRegistry) {
        return new WorkerProcessClassPathProvider(cacheRepository, moduleRegistry);
    }

    protected JavaModuleDetector createJavaModuleDetector(FileContentCacheFactory cacheFactory, FileCollectionFactory fileCollectionFactory) {
        return new JavaModuleDetector(cacheFactory, fileCollectionFactory);
    }

    DefaultGeneratedGradleJarCache createGeneratedGradleJarCache(GlobalScopedCache cacheRepository) {
        String gradleVersion = GradleVersion.current().getVersion();
        return new DefaultGeneratedGradleJarCache(cacheRepository, gradleVersion);
    }

    FileContentCacheFactory createFileContentCacheFactory(ListenerManager listenerManager, FileSystemAccess fileSystemAccess, GlobalScopedCache cacheRepository, InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory) {
        return new DefaultFileContentCacheFactory(listenerManager, fileSystemAccess, cacheRepository, inMemoryCacheDecoratorFactory);
    }

    FileAccessTimeJournal createFileAccessTimeJournal(GlobalScopedCache cacheRepository, InMemoryCacheDecoratorFactory cacheDecoratorFactory) {
        return new DefaultFileAccessTimeJournal(cacheRepository, cacheDecoratorFactory);
    }

    TimeoutHandler createTimeoutHandler(ExecutorFactory executorFactory, CurrentBuildOperationRef currentBuildOperationRef) {
        return new DefaultTimeoutHandler(executorFactory.createScheduled("execution timeouts", 1), currentBuildOperationRef);
    }

    protected CacheConfigurationsInternal createCachesConfiguration(ObjectFactory objectFactory) {
        return new DefaultCacheConfigurations(objectFactory);
    }
}
