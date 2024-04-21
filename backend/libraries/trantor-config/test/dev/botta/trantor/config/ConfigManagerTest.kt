@file:Suppress("ClassName")

package dev.botta.trantor.config

import dev.botta.trantor.config.providers.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class ConfigManagerTest {
    @Nested
    inner class `with single provider` {
        @Test
        fun `retrieve existing key returns value`() {
            manager.addMemoryCollection("key" to "value")

            assertThat(manager["key"]).isEqualTo("value")
        }

        @Test
        fun `retrieve unexistent key returns null`() {
            manager.addMemoryCollection("key" to "value")

            assertThat(manager["other_key"]).isNull()
        }

        @Test
        fun `retrieve existing key with null value`() {
            manager.addMemoryCollection("key" to null)

            assertThat(manager["key"]).isNull()
        }

        @Test
        fun `retrieve path returns value`() {
            manager.addMemoryCollection("key" to "value", "key:sub_key" to "sub_value")

            assertThat(manager["key"]).isEqualTo("value")
            assertThat(manager["key:sub_key"]).isEqualTo("sub_value")
        }

        @Test
        fun `retrieve unexisting path returns null`() {
            manager.addMemoryCollection("key" to "value", "key:sub_key" to "sub_value")

            assertThat(manager["key:other_key"]).isNull()
        }

        @Test
        fun `retrieve section with given key`() {
            manager.addMemoryCollection("key" to "value", "key:sub_key" to "sub_value")

            val section = manager.getSection("key")

            assertThat(section.key).isEqualTo("key")
            assertThat(section.path).isEqualTo("key")
            assertThat(section.value).isEqualTo("value")
        }

        @Test
        fun `retrieve section with given path`() {
            manager.addMemoryCollection("key" to "value", "key:sub_key" to "sub_value")

            val section = manager.getSection("key:sub_key")

            assertThat(section.key).isEqualTo("sub_key")
            assertThat(section.path).isEqualTo("key:sub_key")
            assertThat(section.value).isEqualTo("sub_value")
        }

        @Test
        fun `retrieve unexistent section returns empty section`() {
            manager.addMemoryCollection("key" to "value", "key:sub_key" to "sub_value")

            val section = manager.getSection("other")

            assertThat(section.key).isEqualTo("other")
            assertThat(section.path).isEqualTo("other")
            assertThat(section.value).isNull()
        }

        @Test
        fun `getChildren returns root sections`() {
            manager.addMemoryCollection("key1" to "value1", "key2" to "value2")

            val sections = manager.getChildren()

            assertThat(sections.size).isEqualTo(2)
            assertThat(sections[0].key).isEqualTo("key1")
            assertThat(sections[0].path).isEqualTo("key1")
            assertThat(sections[0].value).isEqualTo("value1")
            assertThat(sections[1].key).isEqualTo("key2")
            assertThat(sections[1].path).isEqualTo("key2")
            assertThat(sections[1].value).isEqualTo("value2")
        }

        @Test
        fun `section getChildren returns section sub-sections`() {
            manager.addMemoryCollection("parent:key1" to "value1", "parent:key2" to "value2")

            val sections = manager.getSection("parent").getChildren()

            assertThat(sections.size).isEqualTo(2)
            assertThat(sections[0].key).isEqualTo("key1")
            assertThat(sections[0].path).isEqualTo("parent:key1")
            assertThat(sections[0].value).isEqualTo("value1")
            assertThat(sections[1].key).isEqualTo("key2")
            assertThat(sections[1].path).isEqualTo("parent:key2")
            assertThat(sections[1].value).isEqualTo("value2")
        }
    }

    @Nested
    inner class `with multiple providers` {
        @Test
        fun `retrieve existing key returns value from last added provider`() {
            manager.addMemoryCollection("key" to "value")
            manager.addMemoryCollection("key" to "value2")

            assertThat(manager["key"]).isEqualTo("value2")
        }

        @Test
        fun `retrieve existing key returns value from last added matching provider`() {
            manager.addMemoryCollection("key" to "value", "other" to "other value")
            manager.addMemoryCollection("key" to "value2")

            assertThat(manager["other"]).isEqualTo("other value")
        }

        @Test
        fun `retrieve existing key with null value from last added provider`() {
            manager.addMemoryCollection("key" to "value")
            manager.addMemoryCollection("key" to null)

            assertThat(manager["key"]).isNull()
        }

        @Test
        fun `returns null when key is not found in all providers`() {
            manager.addMemoryCollection("key" to "value")
            manager.addMemoryCollection("key" to "value2")

            assertThat(manager["other"]).isNull()
        }

        @Test
        fun `getChildren returns root sections from all providers`() {
            manager.addMemoryCollection("key1" to "value1", "key2" to "value2")
            manager.addMemoryCollection("key2" to "value2", "key3" to "value3")

            val sections = manager.getChildren()

            assertThat(sections.size).isEqualTo(3)
            assertThat(sections[0].key).isEqualTo("key1")
            assertThat(sections[0].path).isEqualTo("key1")
            assertThat(sections[0].value).isEqualTo("value1")
            assertThat(sections[1].key).isEqualTo("key2")
            assertThat(sections[1].path).isEqualTo("key2")
            assertThat(sections[1].value).isEqualTo("value2")
            assertThat(sections[2].key).isEqualTo("key3")
            assertThat(sections[2].path).isEqualTo("key3")
            assertThat(sections[2].value).isEqualTo("value3")
        }
    }

    @Nested
    inner class `config section` {
        @Test
        fun `get returns child path`() {
            manager.addMemoryCollection(
                "section:key1" to "value1",
                "section:key2" to "value2",
            )

            val section = manager.getSection("section")

            assertThat(section["key1"]).isEqualTo("value1")
            assertThat(section["key2"]).isEqualTo("value2")
        }

        @Test
        fun `getSection returns child section`() {
            manager.addMemoryCollection(
                "section:sub-section:key1" to "value1",
                "section:sub-section:key2" to "value2",
            )
            val section = manager.getSection("section")

            val subSection = section.getSection("sub-section")

            assertThat(subSection["key1"]).isEqualTo("value1")
            assertThat(subSection["key2"]).isEqualTo("value2")
        }

        @Test
        fun `getChildren returns child sections`() {
            manager.addMemoryCollection(
                "section:sub-section:key1" to "value1",
                "section:sub-section:key2" to "value2",
                "section:sub-section2:key1" to "value3",
                "section:sub-section2:key2" to "value4",
            )
            val section = manager.getSection("section")

            val subSections = section.getChildren()

            assertThat(subSections[0].key).isEqualTo("sub-section")
            assertThat(subSections[0].path).isEqualTo("section:sub-section")
            assertThat(subSections[1].key).isEqualTo("sub-section2")
            assertThat(subSections[1].path).isEqualTo("section:sub-section2")
        }
    }

    private val manager = ConfigManager()
}
