/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.openrewrite.Validated;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.*;
import java.util.stream.Collectors;

import static org.openrewrite.Formatting.EMPTY;
import static org.openrewrite.Tree.randomId;
import static org.openrewrite.Validated.required;

public class ChangeMethodTargetToStatic extends JavaRefactorVisitor {
    private MethodMatcher methodMatcher;
    private String targetType;

    public void setMethod(String method) {
        this.methodMatcher = new MethodMatcher(method);
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    @Override
    public Validated validate() {
        return required("method", methodMatcher)
                .and(required("target.type", targetType));
    }

    @Override
    public J visitNewClass(J.NewClass newClass) {
        if(methodMatcher.matches(newClass)) {
            andThen(new ConstructorScope(newClass, targetType));
        }
        return super.visitNewClass(newClass);
    }

    @Override
    public J visitMethodInvocation(J.MethodInvocation method) {
        if(methodMatcher.matches(method)) {
            andThen(new MethodScope(method, targetType));
        }
        return super.visitMethodInvocation(method);
    }

    private static class ConstructorScope extends JavaRefactorVisitor {
        private final J.NewClass scope;
        private final String targetType;

        private ConstructorScope(J.NewClass scope, String targetType) {
            this.scope = scope;
            this.targetType = targetType;
        }

        @Override
        public Iterable<Tag> getTags() {
            return Tags.of("to", targetType);
        }

        @Override
        public J visitNewClass(J.NewClass newClass) {
            if(scope.isScope(newClass)) {
                maybeAddImport(targetType);
                JavaType.FullyQualified fullyQualifiedTargetType = JavaType.Class.build(targetType);

                // Translate the arguments to the constructor into arguments to the method, on the assumption that
                // the constructor and its static method replacement accept the same arguments.
                // If this assumption does not hold it is up to the recipe using this one as a building block to handle
                Optional<J.NewClass.Arguments> constructorArgs = Optional.ofNullable(newClass.getArgs());
                J.MethodInvocation.Arguments methodArgs = new J.MethodInvocation.Arguments(
                        randomId(),
                        constructorArgs.map(J.NewClass.Arguments::getArgs).orElse(null),
                        constructorArgs.map(J.NewClass.Arguments::getFormatting).orElse(null));

                J.MethodInvocation typelessTransformedMethod = new J.MethodInvocation(
                        randomId(),
                        null,
                        null,
                        J.Ident.build(randomId(), fullyQualifiedTargetType.getClassName(), fullyQualifiedTargetType, EMPTY),
                        methodArgs,
                        null,
                        newClass.getFormatting()
                );

                // I'm not sure how to turn J.NewClass.getType()'s result into a FullyQualifiedType suitable to be used
                // with maybeRemoveImport(), so it's possible this transformation leaves behind an unused import
                Set<Flag> tags = new LinkedHashSet<>();
                tags.add(Flag.Static);
                JavaType.Method methodType = JavaType.Method.build(
                        fullyQualifiedTargetType,
                        "name",
                        null,
                        new JavaType.Method.Signature(
                                newClass.getType(),
                                methodArgs.getArgs().stream().map(Expression::getType).collect(Collectors.toList())),
                        new ArrayList<>(), // This doesn't seem right but I'm not sure where to get the parameter's names from
                        tags);
                return typelessTransformedMethod.withType(methodType);
            }
            return super.visitNewClass(newClass);
        }
    }

    private static class MethodScope extends JavaRefactorVisitor {
        private final J.MethodInvocation scope;
        private final String targetType;

        public MethodScope(J.MethodInvocation scope, String clazz) {
            this.scope = scope;
            this.targetType = clazz;
        }

        @Override
        public Iterable<Tag> getTags() {
            return Tags.of("to", targetType);
        }

        @Override
        public J visitMethodInvocation(J.MethodInvocation method) {
            if (scope.isScope(method)) {
                JavaType.FullyQualified classType = JavaType.Class.build(targetType);
                J.MethodInvocation m = method.withSelect(
                        J.Ident.build(randomId(), classType.getClassName(), classType,
                                method.getSelect() == null ? EMPTY : method.getSelect().getFormatting()));

                maybeAddImport(targetType);

                JavaType.Method transformedType = null;
                if (method.getType() != null) {
                    maybeRemoveImport(method.getType().getDeclaringType());
                    transformedType = method.getType().withDeclaringType(classType);
                    if (!method.getType().hasFlags(Flag.Static)) {
                        Set<Flag> flags = new LinkedHashSet<>(method.getType().getFlags());
                        flags.add(Flag.Static);
                        transformedType = transformedType.withFlags(flags);
                    }
                }

                return m.withType(transformedType);
            }

            return super.visitMethodInvocation(method);
        }
    }
}
