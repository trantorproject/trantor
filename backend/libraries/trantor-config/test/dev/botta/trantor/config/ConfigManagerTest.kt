@file:Suppress("ClassName")

package dev.botta.trantor.config

import dev.botta.trantor.config.providers.MemoryConfigProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class ConfigManagerTest {
    @Nested
    inner class `with single provider` {
        @Test
        fun `retrieve existing key returns value`() {
            manager.add(MemoryConfigProvider("key" to "value"))

            assertThat(manager["key"]).isEqualTo("value")
        }

        @Test
        fun `retrieve unexistent key returns null`() {
            manager.add(MemoryConfigProvider("key" to "value"))

            assertThat(manager["other_key"]).isNull()
        }

        @Test
        fun `retrieve existing key with null value`() {
            manager.add(MemoryConfigProvider("key" to null))

            assertThat(manager["key"]).isNull()
        }

        @Test
        fun `retrieve path returns value`() {
            manager.add(MemoryConfigProvider("key" to "value", "key:sub_key" to "sub_value"))

            assertThat(manager["key"]).isEqualTo("value")
            assertThat(manager["key:sub_key"]).isEqualTo("sub_value")
        }

        @Test
        fun `retrieve unexisting path returns null`() {
            manager.add(MemoryConfigProvider("key" to "value", "key:sub_key" to "sub_value"))

            assertThat(manager["key:other_key"]).isNull()
        }

        @Test
        fun `retrieve section with given key`() {
            manager.add(MemoryConfigProvider("key" to "value", "key:sub_key" to "sub_value"))

            val section = manager.getSection("key")

            assertThat(section.key).isEqualTo("key")
            assertThat(section.path).isEqualTo("key")
            assertThat(section.value).isEqualTo("value")
        }

        @Test
        fun `retrieve section with given path`() {
            manager.add(MemoryConfigProvider("key" to "value", "key:sub_key" to "sub_value"))

            val section = manager.getSection("key:sub_key")

            assertThat(section.key).isEqualTo("sub_key")
            assertThat(section.path).isEqualTo("key:sub_key")
            assertThat(section.value).isEqualTo("sub_value")
        }

        @Test
        fun `retrieve unexistent section returns empty section`() {
            manager.add(MemoryConfigProvider("key" to "value", "key:sub_key" to "sub_value"))

            val section = manager.getSection("other")

            assertThat(section.key).isEqualTo("other")
            assertThat(section.path).isEqualTo("other")
            assertThat(section.value).isNull()
        }

        @Test
        fun `getChildren returns root sections`() {
            manager.add(MemoryConfigProvider("key1" to "value1", "key2" to "value2"))

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
            manager.add(MemoryConfigProvider("parent:key1" to "value1", "parent:key2" to "value2"))

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
            manager.add(MemoryConfigProvider("key" to "value"))
            manager.add(MemoryConfigProvider("key" to "value2"))

            assertThat(manager["key"]).isEqualTo("value2")
        }

        @Test
        fun `retrieve existing key returns value from last added matching provider`() {
            manager.add(MemoryConfigProvider("key" to "value", "other" to "other value"))
            manager.add(MemoryConfigProvider("key" to "value2"))

            assertThat(manager["other"]).isEqualTo("other value")
        }

        @Test
        fun `retrieve existing key with null value from last added provider`() {
            manager.add(MemoryConfigProvider("key" to "value"))
            manager.add(MemoryConfigProvider("key" to null))

            assertThat(manager["key"]).isNull()
        }

        @Test
        fun `returns null when key is not found in all providers`() {
            manager.add(MemoryConfigProvider("key" to "value"))
            manager.add(MemoryConfigProvider("key" to "value2"))

            assertThat(manager["other"]).isNull()
        }

        @Test
        fun `getChildren returns root sections from all providers`() {
            manager.add(MemoryConfigProvider("key1" to "value1", "key2" to "value2"))
            manager.add(MemoryConfigProvider("key2" to "value2", "key3" to "value3"))

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

    private val manager = ConfigManager()
}
