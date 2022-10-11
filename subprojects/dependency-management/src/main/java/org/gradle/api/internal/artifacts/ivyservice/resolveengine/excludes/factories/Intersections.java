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
package org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.factories;

import com.google.common.collect.Sets;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeAnyOf;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeNothing;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.GroupExclude;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.GroupSetExclude;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ModuleExclude;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ModuleIdExclude;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ModuleIdSetExclude;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ModuleSetExclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

class Intersections {
    private final ExcludeFactory factory;
    private final List<Intersection<? extends ExcludeSpec, ? extends ExcludeSpec>> intersections = new ArrayList<>();

    public Intersections(ExcludeFactory factory) {
        this.factory = factory;

        // For the Any intersections, be sure to add the more specific type first, so it gets used if applicable
        intersections.add(new IntersectAnyWithAny(factory));
        intersections.add(new IntersectAnyWithBaseSpec(factory));

        intersections.add(new IntersectGroupWithGroup(factory));
        intersections.add(new IntersectGroupWithModuleId(factory));
        intersections.add(new IntersectGroupWithGroupSet(factory));
        intersections.add(new IntersectGroupWithModuleIdSet(factory));
        intersections.add(new IntersectGroupWithModule(factory));
        intersections.add(new IntersectGroupWithModuleSet(factory));

        intersections.add(new IntersectGroupSetWithGroupSet(factory));
        intersections.add(new IntersectGroupSetWithModuleId(factory));
        intersections.add(new IntersectGroupSetWithModuleIdSet(factory));

        intersections.add(new IntersectModuleWithModule(factory));
        intersections.add(new IntersectModuleWithModuleId(factory));
        intersections.add(new IntersectModuleWithModuleSet(factory));
        intersections.add(new IntersectModuleWithModuleIdSet(factory));

        intersections.add(new IntersectModuleIdWithModuleId(factory));
        intersections.add(new IntersectModuleIdWithModuleIdSet(factory));

        intersections.add(new IntersectModuleIdSetWithModuleIdSet(factory));
        intersections.add(new IntersectModuleIdSetWithModuleSet(factory));

        intersections.add(new IntersectModuleSetWithModuleSet(factory));
    }

    @SuppressWarnings("unchecked")
    ExcludeSpec tryIntersect(ExcludeSpec left, ExcludeSpec right) {
        if (left.equals(right)) {
            return left;
        } else {
            return intersections.stream()
                .filter(i -> i.applies(left, right))
                .findFirst()
                .map(i -> i.intersect(left, right, factory))
                .orElse(null);
        }
    }

    private final class IntersectAnyWithAny extends AbstractIntersection<ExcludeAnyOf, ExcludeAnyOf> {
        public IntersectAnyWithAny(ExcludeFactory factory) {
            super(ExcludeAnyOf.class, ExcludeAnyOf.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(ExcludeAnyOf left, ExcludeAnyOf right, ExcludeFactory factory) {
            Set<ExcludeSpec> leftComponents = left.getComponents();
            Set<ExcludeSpec> rightComponents = right.getComponents();
            Set<ExcludeSpec> common = Sets.newHashSet(leftComponents);
            common.retainAll(rightComponents);
            if (common.size() >= 1) {
                ExcludeSpec alpha = factory.fromUnion(common);
                if (leftComponents.equals(common) || rightComponents.equals(common)) {
                    return alpha;
                }
                Set<ExcludeSpec> remainderLeft = Sets.newHashSet(leftComponents);
                remainderLeft.removeAll(common);
                Set<ExcludeSpec> remainderRight = Sets.newHashSet(rightComponents);
                remainderRight.removeAll(common);

                ExcludeSpec unionLeft = factory.fromUnion(remainderLeft);
                ExcludeSpec unionRight = factory.fromUnion(remainderRight);
                ExcludeSpec beta = factory.allOf(unionLeft, unionRight);
                return factory.anyOf(alpha, beta);
            } else {
                // slowest path, full distribution
                // (A ∪ B) ∩ (C ∪ D) = (A ∩ C) ∪ (A ∩ D) ∪ (B ∩ C) ∪ (B ∩ D)
                Set<ExcludeSpec> intersections = Sets.newHashSetWithExpectedSize(leftComponents.size() * rightComponents.size());
                for (ExcludeSpec leftSpec : leftComponents) {
                    for (ExcludeSpec rightSpec : rightComponents) {
                        ExcludeSpec merged = tryIntersect(leftSpec, rightSpec);
                        if (merged == null) {
                            merged = factory.allOf(leftSpec, rightSpec);
                        }
                        if (!(merged instanceof ExcludeNothing)) {
                            intersections.add(merged);
                        }
                    }
                }
                return factory.fromUnion(intersections);
            }
        }
    }

    private final class IntersectAnyWithBaseSpec extends AbstractIntersection<ExcludeAnyOf, ExcludeSpec> {
        protected IntersectAnyWithBaseSpec(ExcludeFactory factory) {
            super(ExcludeAnyOf.class, ExcludeSpec.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(ExcludeAnyOf left, ExcludeSpec right, ExcludeFactory factory) {
            Set<ExcludeSpec> leftComponents = left.getComponents();
            // Here, we will distribute A ∩ (B ∪ C) if, and only if, at
            // least one of the distribution operations (A ∩ B) can be simplified
            ExcludeSpec[] excludeSpecs = leftComponents.toArray(new ExcludeSpec[0]);
            ExcludeSpec[] intersections = null;
            for (int i = 0; i < excludeSpecs.length; i++) {
                ExcludeSpec excludeSpec = tryIntersect(excludeSpecs[i], right);
                if (excludeSpec != null) {
                    if (intersections == null) {
                        intersections = new ExcludeSpec[excludeSpecs.length];
                    }
                    intersections[i] = excludeSpec;
                }
            }
            if (intersections != null) {
                Set<ExcludeSpec> simplified = Sets.newHashSetWithExpectedSize(excludeSpecs.length);
                for (int i = 0; i < intersections.length; i++) {
                    ExcludeSpec intersection = intersections[i];
                    if (intersection instanceof ExcludeNothing) {
                        continue;
                    }
                    if (intersection != null) {
                        simplified.add(intersection);
                    } else {
                        simplified.add(factory.allOf(excludeSpecs[i], right));
                    }
                }
                return factory.fromUnion(simplified);
            } else {
                return null;
            }
        }

        @Override
        public boolean applies(ExcludeSpec left, ExcludeSpec right) {
            // We want to use the more specific AnyWithAny intersection if possible
            return (left instanceof ExcludeAnyOf && !(right instanceof ExcludeAnyOf))
                    || (right instanceof ExcludeAnyOf && !(left instanceof ExcludeAnyOf));
        }
    }

    private final class IntersectGroupWithGroup extends AbstractIntersection<GroupExclude, GroupExclude> {
        protected IntersectGroupWithGroup(ExcludeFactory factory) {
            super(GroupExclude.class, GroupExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(GroupExclude left, GroupExclude right, ExcludeFactory factory) {
            String group = left.getGroup();
            // equality has been tested before so we know groups are different
            return factory.nothing();
        }
    }

    private final class IntersectGroupWithModuleId extends AbstractIntersection<GroupExclude, ModuleIdExclude> {
        protected IntersectGroupWithModuleId(ExcludeFactory factory) {
            super(GroupExclude.class, ModuleIdExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(GroupExclude left, ModuleIdExclude right, ExcludeFactory factory) {
            String group = left.getGroup();
            if (right.getModuleId().getGroup().equals(group)) {
                return right;
            } else {
                return factory.nothing();
            }
        }
    }

    private final class IntersectGroupWithGroupSet extends AbstractIntersection<GroupExclude, GroupSetExclude> {
        protected IntersectGroupWithGroupSet(ExcludeFactory factory) {
            super(GroupExclude.class, GroupSetExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(GroupExclude left, GroupSetExclude right, ExcludeFactory factory) {
            String group = left.getGroup();
            if (right.getGroups().stream().anyMatch(g -> g.equals(group))) {
                return left;
            }
            return factory.nothing();
        }
    }

    private final class IntersectGroupWithModuleIdSet extends AbstractIntersection<GroupExclude, ModuleIdSetExclude> {
        protected IntersectGroupWithModuleIdSet(ExcludeFactory factory) {
            super(GroupExclude.class, ModuleIdSetExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(GroupExclude left, ModuleIdSetExclude right, ExcludeFactory factory) {
            String group = left.getGroup();
            Set<ModuleIdentifier> moduleIds = right.getModuleIds().stream().filter(id -> id.getGroup().equals(group)).collect(toSet());
            return factory.fromModuleIds(moduleIds);
        }
    }

    private final class IntersectGroupWithModule extends AbstractIntersection<GroupExclude, ModuleExclude> {
        protected IntersectGroupWithModule(ExcludeFactory factory) {
            super(GroupExclude.class, ModuleExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(GroupExclude left, ModuleExclude right, ExcludeFactory factory) {
            String group = left.getGroup();
            return factory.moduleId(DefaultModuleIdentifier.newId(left.getGroup(), right.getModule()));
        }
    }

    private final class IntersectGroupWithModuleSet extends AbstractIntersection<GroupExclude, ModuleSetExclude> {
        protected IntersectGroupWithModuleSet(ExcludeFactory factory) {
            super(GroupExclude.class, ModuleSetExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(GroupExclude left, ModuleSetExclude right, ExcludeFactory factory) {
            String group = left.getGroup();
            return factory.moduleIdSet(right.getModules()
                    .stream()
                    .map(module -> DefaultModuleIdentifier.newId(left.getGroup(), module))
                    .collect(toSet())
            );
        }
    }

    private final class IntersectGroupSetWithGroupSet extends AbstractIntersection<GroupSetExclude, GroupSetExclude> {
        protected IntersectGroupSetWithGroupSet(ExcludeFactory factory) {
            super(GroupSetExclude.class, GroupSetExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(GroupSetExclude left, GroupSetExclude right, ExcludeFactory factory) {
            Set<String> groups = left.getGroups();
            Set<String> common = Sets.newHashSet(right.getGroups());
            common.retainAll(groups);
            return factory.fromGroups(common);
        }
    }

    private final class IntersectGroupSetWithModuleId extends AbstractIntersection<GroupSetExclude, ModuleIdExclude> {
        protected IntersectGroupSetWithModuleId(ExcludeFactory factory) {
            super(GroupSetExclude.class, ModuleIdExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(GroupSetExclude left, ModuleIdExclude right, ExcludeFactory factory) {
            Set<String> groups = left.getGroups();
            if (groups.contains(right.getModuleId().getGroup())) {
                return right;
            }
            return factory.nothing();
        }
    }

    private final class IntersectGroupSetWithModuleIdSet extends AbstractIntersection<GroupSetExclude, ModuleIdSetExclude> {
        protected IntersectGroupSetWithModuleIdSet(ExcludeFactory factory) {
            super(GroupSetExclude.class, ModuleIdSetExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(GroupSetExclude left, ModuleIdSetExclude right, ExcludeFactory factory) {
            Set<String> groups = left.getGroups();
            Set<ModuleIdentifier> filtered = right.getModuleIds()
                    .stream()
                    .filter(id -> groups.contains(id.getGroup()))
                    .collect(toSet());
            return factory.fromModuleIds(filtered);
        }
    }

    private final class IntersectModuleWithModule extends AbstractIntersection<ModuleExclude, ModuleExclude> {
        protected IntersectModuleWithModule(ExcludeFactory factory) {
            super(ModuleExclude.class, ModuleExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(ModuleExclude left, ModuleExclude right, ExcludeFactory factory) {
            String module = left.getModule();
            if (right.getModule().equals(module)) {
                return left;
            } else {
                return factory.nothing();
            }
        }
    }

    private final class IntersectModuleWithModuleId extends AbstractIntersection<ModuleExclude, ModuleIdExclude> {
        protected IntersectModuleWithModuleId(ExcludeFactory factory) {
            super(ModuleExclude.class, ModuleIdExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(ModuleExclude left, ModuleIdExclude right, ExcludeFactory factory) {
            String module = left.getModule();
            if (right.getModuleId().getName().equals(module)) {
                return right;
            } else {
                return factory.nothing();
            }
        }
    }

    private final class IntersectModuleWithModuleSet extends AbstractIntersection<ModuleExclude, ModuleSetExclude> {
        protected IntersectModuleWithModuleSet(ExcludeFactory factory) {
            super(ModuleExclude.class, ModuleSetExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(ModuleExclude left, ModuleSetExclude right, ExcludeFactory factory) {
            String module = left.getModule();
            if (right.getModules().stream().anyMatch(g -> g.equals(module))) {
                return left;
            }
            return factory.nothing();
        }
    }

    private final class IntersectModuleWithModuleIdSet extends AbstractIntersection<ModuleExclude, ModuleIdSetExclude> {
        protected IntersectModuleWithModuleIdSet(ExcludeFactory factory) {
            super(ModuleExclude.class, ModuleIdSetExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(ModuleExclude left, ModuleIdSetExclude right, ExcludeFactory factory) {
            String module = left.getModule();
            Set<ModuleIdentifier> common = right.getModuleIds().stream().filter(id -> id.getName().equals(module)).collect(toSet());
            return factory.fromModuleIds(common);
        }
    }

    private final class IntersectModuleIdWithModuleId extends AbstractIntersection<ModuleIdExclude, ModuleIdExclude> {
        protected IntersectModuleIdWithModuleId(ExcludeFactory factory) {
            super(ModuleIdExclude.class, ModuleIdExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(ModuleIdExclude left, ModuleIdExclude right, ExcludeFactory factory) {
            if (left.equals(right)) {
                return left;
            }
            return factory.nothing();
        }
    }

    private final class IntersectModuleIdWithModuleIdSet extends AbstractIntersection<ModuleIdExclude, ModuleIdSetExclude> {
        protected IntersectModuleIdWithModuleIdSet(ExcludeFactory factory) {
            super(ModuleIdExclude.class, ModuleIdSetExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(ModuleIdExclude left, ModuleIdSetExclude right, ExcludeFactory factory) {
            Set<ModuleIdentifier> rightModuleIds = right.getModuleIds();
            if (rightModuleIds.contains(left.getModuleId())) {
                return left;
            }
            return factory.nothing();
        }
    }

    private final class IntersectModuleIdSetWithModuleIdSet extends AbstractIntersection<ModuleIdSetExclude, ModuleIdSetExclude> {
        protected IntersectModuleIdSetWithModuleIdSet(ExcludeFactory factory) {
            super(ModuleIdSetExclude.class, ModuleIdSetExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(ModuleIdSetExclude left, ModuleIdSetExclude right, ExcludeFactory factory) {
            Set<ModuleIdentifier> moduleIds = left.getModuleIds();
            Set<ModuleIdentifier> common = Sets.newHashSet(right.getModuleIds());
            common.retainAll(moduleIds);
            return factory.fromModuleIds(common);
        }
    }

    private final class IntersectModuleIdSetWithModuleSet extends AbstractIntersection<ModuleIdSetExclude, ModuleSetExclude> {
        protected IntersectModuleIdSetWithModuleSet(ExcludeFactory factory) {
            super(ModuleIdSetExclude.class, ModuleSetExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(ModuleIdSetExclude left, ModuleSetExclude right, ExcludeFactory factory) {
            Set<ModuleIdentifier> moduleIds = left.getModuleIds();
            Set<String> modules = right.getModules();
            Set<ModuleIdentifier> identifiers = moduleIds.stream()
                    .filter(e -> modules.contains(e.getName()))
                    .collect(toSet());
            if (identifiers.isEmpty()) {
                return factory.nothing();
            }
            if (identifiers.size() == 1) {
                return factory.moduleId(identifiers.iterator().next());
            } else {
                return factory.moduleIdSet(identifiers);
            }
        }
    }

    private final class IntersectModuleSetWithModuleSet extends AbstractIntersection<ModuleSetExclude, ModuleSetExclude> {
        protected IntersectModuleSetWithModuleSet(ExcludeFactory factory) {
            super(ModuleSetExclude.class, ModuleSetExclude.class, factory);
        }

        @Override
        public ExcludeSpec doIntersect(ModuleSetExclude left, ModuleSetExclude right, ExcludeFactory factory) {
            Set<String> modules = Sets.newHashSet(left.getModules());
            modules.retainAll(right.getModules());
            if (modules.isEmpty()) {
                return factory.nothing();
            }
            if (modules.size() == 1) {
                return factory.module(modules.iterator().next());
            }
            return factory.moduleSet(modules);
        }
    }
}
