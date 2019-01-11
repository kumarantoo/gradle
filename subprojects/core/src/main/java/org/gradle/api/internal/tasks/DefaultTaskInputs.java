/*
 * Copyright 2010 the original author or authors.
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
package org.gradle.api.internal.tasks;

import com.google.common.collect.Lists;
import org.gradle.api.Describable;
import org.gradle.api.NonNullApi;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.FilePropertyContainer;
import org.gradle.api.internal.TaskInputsInternal;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.file.CompositeFileCollection;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.collections.FileCollectionResolveContext;
import org.gradle.api.internal.tasks.properties.FileTreeValue;
import org.gradle.api.internal.tasks.properties.GetInputFilesVisitor;
import org.gradle.api.internal.tasks.properties.GetInputPropertiesVisitor;
import org.gradle.api.internal.tasks.properties.InputFilePropertyType;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.internal.tasks.properties.PropertyWalker;
import org.gradle.api.tasks.FileNormalizer;
import org.gradle.api.tasks.TaskInputPropertyBuilder;
import org.gradle.api.tasks.TaskInputs;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@NonNullApi
public class DefaultTaskInputs implements TaskInputsInternal {
    private final FileCollection allInputFiles;
    private final FileCollection allSourceFiles;
    private final TaskInternal task;
    private final TaskMutator taskMutator;
    private final PropertyWalker propertyWalker;
    private final FileResolver fileResolver;
    private final List<DeclaredTaskInputProperty> registeredProperties = Lists.newArrayList();
    private final FilePropertyContainer<DeclaredTaskInputFileProperty> registeredFileProperties = FilePropertyContainer.create();
    private final TaskInputs deprecatedThis;

    public DefaultTaskInputs(TaskInternal task, TaskMutator taskMutator, PropertyWalker propertyWalker, FileResolver fileResolver) {
        this.task = task;
        this.taskMutator = taskMutator;
        this.propertyWalker = propertyWalker;
        this.fileResolver = fileResolver;
        String taskDisplayName = task.toString();
        this.allInputFiles = new TaskInputUnionFileCollection(taskDisplayName, "input", false, task, propertyWalker, fileResolver);
        this.allSourceFiles = new TaskInputUnionFileCollection(taskDisplayName, "source", true, task, propertyWalker, fileResolver);
        this.deprecatedThis = new TaskInputsDeprecationSupport();
    }

    @Override
    public boolean getHasInputs() {
        HasInputsVisitor visitor = new HasInputsVisitor();
        TaskPropertyUtils.visitProperties(propertyWalker, task, visitor);
        return visitor.hasInputs();
    }

    @Override
    public void visitRegisteredProperties(PropertyVisitor visitor) {
        for (DeclaredTaskInputFileProperty fileProperty : registeredFileProperties) {
            visitor.visitInputFileProperty(
                fileProperty.getPropertyName(),
                fileProperty.isOptional(),
                fileProperty.isSkipWhenEmpty(),
                fileProperty.getNormalizer(),
                fileProperty.getValue(),
                fileProperty.getFilePropertyType());
        }
        for (DeclaredTaskInputProperty inputProperty : registeredProperties) {
            visitor.visitInputProperty(inputProperty.getPropertyName(), inputProperty.getValidatingValue(), inputProperty.isOptional());
        }
    }

    @Override
    public FileCollection getFiles() {
        return allInputFiles;
    }

    @Override
    public TaskInputFilePropertyBuilderInternal files(final Object... paths) {
        return taskMutator.mutate("TaskInputs.files(Object...)", new Callable<TaskInputFilePropertyBuilderInternal>() {
            @Override
            public TaskInputFilePropertyBuilderInternal call() {
                StaticValue value = new StaticValue(unpackVarargs(paths));
                DeclaredTaskInputFileProperty fileSpec = createDeclaredTaskInputFilePropertySpec(value, InputFilePropertyType.FILES);
                registeredFileProperties.add(fileSpec);
                return fileSpec;
            }
        });
    }

    private static Object unpackVarargs(Object[] args) {
        if (args.length == 1) {
            return args[0];
        }
        return args;
    }

    @Override
    public TaskInputFilePropertyBuilderInternal file(final Object path) {
        return taskMutator.mutate("TaskInputs.file(Object)", new Callable<TaskInputFilePropertyBuilderInternal>() {
            @Override
            public TaskInputFilePropertyBuilderInternal call() {
                StaticValue value = new StaticValue(path);
                DeclaredTaskInputFileProperty fileSpec = createDeclaredTaskInputFilePropertySpec(value, InputFilePropertyType.FILE);
                registeredFileProperties.add(fileSpec);
                return fileSpec;
            }
        });
    }

    @Override
    public TaskInputFilePropertyBuilderInternal dir(final Object dirPath) {
        return taskMutator.mutate("TaskInputs.dir(Object)", new Callable<TaskInputFilePropertyBuilderInternal>() {
            @Override
            public TaskInputFilePropertyBuilderInternal call() {
                StaticValue value = new StaticValue(dirPath);
                DeclaredTaskInputFileProperty dirSpec = createDeclaredTaskInputFilePropertySpec(value, InputFilePropertyType.DIRECTORY);
                registeredFileProperties.add(dirSpec);
                return dirSpec;
            }
        });
    }

    private DeclaredTaskInputFileProperty createDeclaredTaskInputFilePropertySpec(StaticValue value, InputFilePropertyType filePropertyType) {
        return new DefaultDeclaredTaskInputFileProperty(task.toString(), fileResolver, value, filePropertyType);
    }

    @Override
    public boolean getHasSourceFiles() {
        GetInputFilesVisitor visitor = new GetInputFilesVisitor(fileResolver, task.toString());
        TaskPropertyUtils.visitProperties(propertyWalker, task, visitor);
        return visitor.hasSourceFiles();
    }

    @Override
    public FileCollection getSourceFiles() {
        return allSourceFiles;
    }

    @Override
    public Map<String, Object> getProperties() {
        GetInputPropertiesVisitor visitor = new GetInputPropertiesVisitor(task.getName());
        TaskPropertyUtils.visitProperties(propertyWalker, task, visitor);
        //noinspection ConstantConditions
        return visitor.getPropertyValuesFactory().create();
    }

    @Override
    public TaskInputPropertyBuilder property(final String name, @Nullable final Object value) {
        return taskMutator.mutate("TaskInputs.property(String, Object)", new Callable<TaskInputPropertyBuilder>() {
            @Override
            public TaskInputPropertyBuilder call() {
                StaticValue staticValue = new StaticValue(value);
                DeclaredTaskInputProperty inputPropertySpec = createDeclaredTaskInputProperty(name, staticValue);
                registeredProperties.add(inputPropertySpec);
                return inputPropertySpec;
            }
        });
    }

    @Override
    public TaskInputs properties(final Map<String, ?> newProps) {
        taskMutator.mutate("TaskInputs.properties(Map)", new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<String, ?> entry : newProps.entrySet()) {
                    StaticValue staticValue = new StaticValue(entry.getValue());
                    String name = entry.getKey();
                    registeredProperties.add(createDeclaredTaskInputProperty(name, staticValue));
                }
            }
        });
        return deprecatedThis;
    }

    private DeclaredTaskInputProperty createDeclaredTaskInputProperty(String propertyName, ValidatingValue value) {
        return new DefaultTaskInputPropertySpec(propertyName, value);
    }

    private static class TaskInputUnionFileCollection extends CompositeFileCollection implements Describable {
        private final boolean skipWhenEmptyOnly;
        private final String taskDisplayName;
        private final String type;
        private final TaskInternal task;
        private final PropertyWalker propertyWalker;
        private final FileResolver fileResolver;

        TaskInputUnionFileCollection(String taskDisplayName, String type, boolean skipWhenEmptyOnly, TaskInternal task, PropertyWalker propertyWalker, FileResolver fileResolver) {
            this.taskDisplayName = taskDisplayName;
            this.type = type;
            this.skipWhenEmptyOnly = skipWhenEmptyOnly;
            this.task = task;
            this.propertyWalker = propertyWalker;
            this.fileResolver = fileResolver;
        }

        @Override
        public String getDisplayName() {
            return taskDisplayName + " " + type + " files";
        }

        @Override
        public void visitContents(final FileCollectionResolveContext context) {
            TaskPropertyUtils.visitProperties(propertyWalker, task, new PropertyVisitor.Adapter() {
                @Override
                public void visitInputFileProperty(final String propertyName, boolean optional, boolean skipWhenEmpty, Class<? extends FileNormalizer> fileNormalizer, ValidatingValue value, InputFilePropertyType filePropertyType) {
                    if (!TaskInputUnionFileCollection.this.skipWhenEmptyOnly || skipWhenEmpty) {
                        ValidatingValue actualValue = filePropertyType == InputFilePropertyType.DIRECTORY ? FileTreeValue.create(fileResolver, value) : value;
                        context.add(new PropertyFileCollection(task.toString(), new Supplier<String>() {
                            @Override
                            public String get() {
                                return propertyName;
                            }
                        }, "input", fileResolver, actualValue));
                    }
                }
            });
        }
    }

    private static class HasInputsVisitor extends PropertyVisitor.Adapter {
        private boolean hasInputs;

        public boolean hasInputs() {
            return hasInputs;
        }

        @Override
        public void visitInputFileProperty(String propertyName, boolean optional, boolean skipWhenEmpty, Class<? extends FileNormalizer> fileNormalizer, ValidatingValue value, InputFilePropertyType filePropertyType) {
            hasInputs = true;
        }

        @Override
        public void visitInputProperty(String propertyName, ValidatingValue value, boolean optional) {
            hasInputs = true;
        }
    }

}
