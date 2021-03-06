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
package org.openrewrite.xml.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.openrewrite.RecipeTest
import org.openrewrite.TreePrinter
import org.openrewrite.marker.SearchResult
import org.openrewrite.xml.XmlParser

class FindTagsTest : RecipeTest {
    override val parser = XmlParser.builder().build()

    override val treePrinter: TreePrinter<*>?
        get() = SearchResult.PRINTER

    @Test
    fun simpleElement() = assertChanged(
            parser,
            FindTags("/dependencies/dependency"),
            before = """
                <?xml version="1.0" encoding="UTF-8"?>
                <dependencies>
                    <dependency>
                        <artifactId scope="compile">org.openrewrite</artifactId>
                    </dependency>
                </dependency>
            """,
            after = """
                <?xml version="1.0" encoding="UTF-8"?>
                <dependencies>
                    ~~><dependency>
                        <artifactId scope="compile">org.openrewrite</artifactId>
                    </dependency>
                </dependency>
            """
    )

    @Test
    fun wildcard() = assertChanged(
            parser,
            FindTags("/dependencies/*"),
            before = """
                <?xml version="1.0" encoding="UTF-8"?>
                <dependencies>
                    <dependency>
                        <artifactId scope="compile">org.openrewrite</artifactId>
                    </dependency>
                </dependency>
            """,
            after = """
                <?xml version="1.0" encoding="UTF-8"?>
                <dependencies>
                    ~~><dependency>
                        <artifactId scope="compile">org.openrewrite</artifactId>
                    </dependency>
                </dependency>
            """
    )

    @Test
    fun noMatch() = assertUnchanged(
            parser,
            FindTags("/dependencies/dne"),
            before = """
                <?xml version="1.0" encoding="UTF-8"?>
                <dependencies>
                    <dependency>
                        <artifactId scope="compile">org.openrewrite</artifactId>
                    </dependency>
                </dependency>
            """
    )

    @Test
    fun staticFind() {
        val before = """
                <?xml version="1.0" encoding="UTF-8"?>
                <dependencies>
                    <dependency>
                        <artifactId scope="compile">org.openrewrite</artifactId>
                    </dependency>
                </dependency>
            """
        val source = parser.parse(*(arrayOf(before.trimIndent()))).iterator().next()
        val matchingTags = FindTags.find(source, "/dependencies/dependency")
        assertThat(matchingTags).isNotNull.isNotEmpty
    }

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    @Test
    fun checkValidation() {
        var recipe = FindTags(null)
        var valid = recipe.validate()
        assertThat(valid.isValid).isFalse()
        assertThat(valid.failures()).hasSize(1)
        assertThat(valid.failures()[0].property).isEqualTo("xPath")

        recipe = FindTags("/dependencies/dependency")
        valid = recipe.validate()
        assertThat(valid.isValid).isTrue()
    }
}
