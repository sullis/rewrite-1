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
package org.openrewrite.java

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openrewrite.java.tree.Flag
import org.openrewrite.java.tree.J

interface ChangeMethodTargetToStaticTest {

    @Test
    fun refactorTargetToStatic(jp: JavaParser) {
        val a = """
            package a;
            public class A {
               public void nonStatic() {}
            }
        """.trimIndent()

        val b = """
            package b;
            public class B {
               public static void foo() {}
            }
        """.trimIndent()

        val c = """
            import a.*;
            class C {
               public void test() {
                   new A().nonStatic();
               }
            }
        """.trimIndent()

        val cu = jp.parse(c, a, b)
        val fixed = cu.refactor()
                .visit(ChangeMethodTargetToStatic().apply {
                    setMethod("a.A nonStatic()")
                    setTargetType("b.B")
                })
                .visit(ChangeMethodName().apply {
                    setMethod("b.B nonStatic()")
                    name = "foo"
                })
                .fix().fixed

        assertRefactored(fixed, """
            import b.B;
            
            class C {
               public void test() {
                   B.foo();
               }
            }
        """)

        val refactoredInv = fixed.classes[0].methods[0].body!!.statements[0] as J.MethodInvocation
        assertTrue(refactoredInv.type?.hasFlags(Flag.Static) ?: false)
    }

    @Test
    fun refactorStaticTargetToStatic(jp: JavaParser) {
        val a = """
            package a;
            public class A {
               public static void foo() {}
            }
        """.trimIndent()

        val b = """
            package b;
            public class B {
               public static void foo() {}
            }
        """.trimIndent()

        val c = """
            import static a.A.*;
            class C {
               public void test() {
                   foo();
               }
            }
        """.trimIndent()

        val cu = jp.parse(c, a, b)
        val fixed = cu.refactor()
                .visit(ChangeMethodTargetToStatic().apply {
                    setMethod("a.A foo()")
                    setTargetType("b.B")
                })
                .fix().fixed

        assertRefactored(fixed, """
            import b.B;
            
            class C {
               public void test() {
                   B.foo();
               }
            }
        """)
    }

    @Test
    fun refactorConstructorToStatic(jp: JavaParser) {
        val a = """
            class A {
                public static A factory() {
                    return new A();
                }
            }
        """.trimIndent()

        val b = """
            class B {
                public static void foo() {
                    A a = new A();       
                }
            }
        """.trimIndent()

        val cu = jp.parse(b, a)
        val fixed = cu.refactor()
                .visit(ChangeMethodTargetToStatic().apply {
                    setMethod("A A()")
                    setTargetType("A factory()")
                })
                .fix()
                .fixed

        assertRefactored(fixed, """
            class B {
                public static void foo() {
                    A a = A.factory();
                }
            }
        """.trimIndent())
    }
}
