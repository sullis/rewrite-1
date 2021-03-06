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
package org.openrewrite.xml

import org.junit.jupiter.api.Test
import org.openrewrite.xml.tree.Xml

class AddToTagVisitorTest : XmlVisitorTest() {

    @Test
    fun addElement() = assertChanged(
            visitorMapped = { x -> AddToTagVisitor(x.root, Xml.Tag.build("""<bean id="myBean2"/>""")) },
            before = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans >
                    <bean id="myBean"/>
                </beans>
            """,
            after = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans >
                    <bean id="myBean"/>
                    <bean id="myBean2"/>
                </beans>
            """)

    @Test
    fun addElementToSlashClosedTag() = assertChanged(
            visitorMapped = { x -> AddToTagVisitor(x.root.content[0] as Xml.Tag, Xml.Tag.build("""<property name="myprop" ref="collaborator"/>""")) },
            before = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans >
                    <bean id="myBean" />
                </beans>
            """,
            after = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans >
                    <bean id="myBean">
                        <property name="myprop" ref="collaborator"/>
                    </bean>
                </beans>
            """
    )

    @Test
    fun addElementToEmptyTagOnSameLine() = assertChanged(
            visitorMapped = { x -> AddToTagVisitor(x.root, Xml.Tag.build("""<bean id="myBean"/>""")) },
            before = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans></beans>
            """,
            after = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans>
                    <bean id="myBean"/>
                </beans>
            """
    )

    @Test
    fun addElementInOrder() = assertChanged(
            visitorMapped = { x ->
                AddToTagVisitor(x.root, Xml.Tag.build("""<apple/>"""),
                        Comparator.comparing(Xml.Tag::getName))
            },
            before = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans >
                    <banana/>
                </beans>
            """,
            after = """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans >
                    <apple/>
                    <banana/>
                </beans>
            """
    )
}
