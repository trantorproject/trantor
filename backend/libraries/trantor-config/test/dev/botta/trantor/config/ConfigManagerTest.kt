@file:Suppress("ClassName")

package dev.botta.trantor.config

import dev.botta.json.Json
import dev.botta.trantor.config.providers.addMemoryCollection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class ConfigManagerTest {
    @Nested
    inner class `with single provider` {
        @Test
        fun `retrieve existing key returns value`() {
            config.addMemoryCollection("key" to "value")

            assertThat(config["key"]).isEqualTo("value")
        }

        @Test
        fun `retrieve existing key different case returns value`() {
            config.addMemoryCollection("key" to "value")

            assertThat(config["KEY"]).isEqualTo("value")
        }

        @Test
        fun `retrieve unexistent key returns null`() {
            config.addMemoryCollection("key" to "value")

            assertThat(config["other_key"]).isNull()
        }

        @Test
        fun `retrieve existing key with null value`() {
            config.addMemoryCollection("key" to null)

            assertThat(config["key"]).isNull()
        }

        @Test
        fun `retrieve path returns value`() {
            config.addMemoryCollection("key" to "value", "key.sub_key" to "sub_value")

            assertThat(config["key"]).isEqualTo("value")
            assertThat(config["key.sub_key"]).isEqualTo("sub_value")
        }

        @Test
        fun `retrieve path different case returns value`() {
            config.addMemoryCollection("key" to "value", "key.sub_key" to "sub_value")

            assertThat(config["KEY"]).isEqualTo("value")
            assertThat(config["KEY.SUB_KEY"]).isEqualTo("sub_value")
        }

        @Test
        fun `retrieve unexisting path returns null`() {
            config.addMemoryCollection("key" to "value", "key.sub_key" to "sub_value")

            assertThat(config["key.other_key"]).isNull()
        }

        @Test
        fun `retrieve section with given key`() {
            config.addMemoryCollection("key" to "value", "key.sub_key" to "sub_value")

            val section = config.getSection("key")

            assertThat(section.key).isEqualTo("key")
            assertThat(section.path).isEqualTo("key")
            assertThat(section.value).isEqualTo("value")
        }

        @Test
        fun `retrieve section different case with given key`() {
            config.addMemoryCollection("KEY" to "value", "KEY.SUB_KEY" to "sub_value")

            val section = config.getSection("key")

            assertThat(section.key).isEqualTo("key")
            assertThat(section.path).isEqualTo("key")
            assertThat(section.value).isEqualTo("value")
        }

        @Test
        fun `retrieve section with given path`() {
            config.addMemoryCollection("key" to "value", "key.sub_key" to "sub_value")

            val section = config.getSection("key.sub_key")

            assertThat(section.key).isEqualTo("sub_key")
            assertThat(section.path).isEqualTo("key.sub_key")
            assertThat(section.value).isEqualTo("sub_value")
        }

        @Test
        fun `retrieve unexistent section returns empty section`() {
            config.addMemoryCollection("key" to "value", "key.sub_key" to "sub_value")

            val section = config.getSection("other")

            assertThat(section.key).isEqualTo("other")
            assertThat(section.path).isEqualTo("other")
            assertThat(section.value).isNull()
        }

        @Test
        fun `getChildren returns root sections`() {
            config.addMemoryCollection("key1" to "value1", "key2" to "value2")

            val sections = config.getChildren()

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
            config.addMemoryCollection("parent.key1" to "value1", "parent.key2" to "value2")

            val sections = config.getSection("parent").getChildren()

            assertThat(sections.size).isEqualTo(2)
            assertThat(sections[0].key).isEqualTo("key1")
            assertThat(sections[0].path).isEqualTo("parent.key1")
            assertThat(sections[0].value).isEqualTo("value1")
            assertThat(sections[1].key).isEqualTo("key2")
            assertThat(sections[1].path).isEqualTo("parent.key2")
            assertThat(sections[1].value).isEqualTo("value2")
        }

        @Test
        fun `sub-section getChildren returns grand sub-sections`() {
            config.addMemoryCollection(
                "section.sub-section.key1" to "value1",
                "section.sub-section.key2" to "value2",
            )
            val sections = config.getSection("section").getChildren()

            val subSections = sections.first().getChildren()

            assertThat(subSections.size).isEqualTo(2)
            assertThat(subSections[0].key).isEqualTo("key1")
            assertThat(subSections[0].path).isEqualTo("section.sub-section.key1")
            assertThat(subSections[0].value).isEqualTo("value1")
            assertThat(subSections[1].key).isEqualTo("key2")
            assertThat(subSections[1].path).isEqualTo("section.sub-section.key2")
            assertThat(subSections[1].value).isEqualTo("value2")
        }

        @Test
        fun `section getChildren returns section sub-sections ignoring case`() {
            config.addMemoryCollection("PARENT.KEY1" to "value1", "PARENT.KEY2" to "value2")

            val sections = config.getSection("parent").getChildren()

            assertThat(sections.size).isEqualTo(2)
            assertThat(sections[0].key).isEqualTo("KEY1")
            assertThat(sections[0].path).isEqualTo("parent.KEY1")
            assertThat(sections[0].value).isEqualTo("value1")
            assertThat(sections[1].key).isEqualTo("KEY2")
            assertThat(sections[1].path).isEqualTo("parent.KEY2")
            assertThat(sections[1].value).isEqualTo("value2")
        }
    }

    @Nested
    inner class `with multiple providers` {
        @Test
        fun `retrieve existing key returns value from last added provider`() {
            config.addMemoryCollection("key" to "value")
            config.addMemoryCollection("key" to "value2")

            assertThat(config["key"]).isEqualTo("value2")
        }

        @Test
        fun `retrieve existing key returns value from last added matching provider`() {
            config.addMemoryCollection("key" to "value", "other" to "other value")
            config.addMemoryCollection("key" to "value2")

            assertThat(config["other"]).isEqualTo("other value")
        }

        @Test
        fun `retrieve existing key with null value from last added provider`() {
            config.addMemoryCollection("key" to "value")
            config.addMemoryCollection("key" to null)

            assertThat(config["key"]).isNull()
        }

        @Test
        fun `returns null when key is not found in all providers`() {
            config.addMemoryCollection("key" to "value")
            config.addMemoryCollection("key" to "value2")

            assertThat(config["other"]).isNull()
        }

        @Test
        fun `getChildren returns root sections from all providers`() {
            config.addMemoryCollection("key1" to "value1", "key2" to "value2")
            config.addMemoryCollection("key2" to "value2", "key3" to "value3")

            val sections = config.getChildren()

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
        fun `get root section`() {
            config.addMemoryCollection(
                "key1" to "value1",
                "key2" to "value2",
            )

            val section = config.getSection("")

            assertThat(section["key1"]).isEqualTo("value1")
            assertThat(section["key2"]).isEqualTo("value2")
        }

        @Test
        fun `get returns child path`() {
            config.addMemoryCollection(
                "section.key1" to "value1",
                "section.key2" to "value2",
            )

            val section = config.getSection("section")

            assertThat(section["key1"]).isEqualTo("value1")
            assertThat(section["key2"]).isEqualTo("value2")
        }

        @Test
        fun `getSection returns child section`() {
            config.addMemoryCollection(
                "section.sub-section.key1" to "value1",
                "section.sub-section.key2" to "value2",
            )
            val section = config.getSection("section")

            val subSection = section.getSection("sub-section")

            assertThat(subSection["key1"]).isEqualTo("value1")
            assertThat(subSection["key2"]).isEqualTo("value2")
        }

        @Test
        fun `getChildren returns child sections`() {
            config.addMemoryCollection(
                "section.sub-section.key1" to "value1",
                "section.sub-section.key2" to "value2",
                "section.sub-section2.key1" to "value3",
                "section.sub-section2.key2" to "value4",
            )
            val section = config.getSection("section")

            val subSections = section.getChildren()

            assertThat(subSections[0].key).isEqualTo("sub-section")
            assertThat(subSections[0].path).isEqualTo("section.sub-section")
            assertThat(subSections[1].key).isEqualTo("sub-section2")
            assertThat(subSections[1].path).isEqualTo("section.sub-section2")
        }
    }

    @Nested
    inner class toJson {
        @Test
        fun `simple string value to json`() {
            config.addMemoryCollection("key1" to "value")

            val json = config.getSection("key1").toJson()

            assertThat(json).isEqualTo(Json.value("value"))
        }

        @Test
        fun `simple null value to json`() {
            config.addMemoryCollection("key1" to null)

            val json = config.getSection("key1").toJson()

            assertThat(json).isEqualTo(Json.value(null))
        }

        @Test
        fun `simple number value to json`() {
            config.addMemoryCollection("key1" to "5")

            val json = config.getSection("key1").toJson()

            assertThat(json).isEqualTo(Json.value("5"))
        }

        @Test
        fun `missing value to json`() {
            config.addMemoryCollection()

            val json = config.getSection("key1").toJson()

            assertThat(json).isEqualTo(Json.value(null))
        }

        @Test
        fun `section to object json`() {
            config.addMemoryCollection(
                "section.sub-section.key1" to "value1",
                "section.sub-section.key2" to "value2",
            )

            val json = config.getSection("section").toJson()

            assertThat(json).isEqualTo(Json.obj(
                "sub-section" to Json.obj(
                    "key1" to "value1",
                    "key2" to "value2",
                )
            ))
        }

        @Test
        fun `array section to json`() {
            config.addMemoryCollection(
                "section.0" to "value1",
                "section.1" to "value2",
                "section.size" to "2",
                "section.__config_type__" to "array",
            )

            val json = config.getSection("section").toJson()

            assertThat(json).isEqualTo(Json.array("value1", "value2"))
        }

        @Test
        fun `manager toJson`() {
            config.addMemoryCollection(
                "section.key1" to "value1",
                "section.key2" to "value2",
            )

            val json = config.toJson()

            assertThat(json).isEqualTo(Json.obj(
                "section" to Json.obj(
                    "key1" to "value1",
                    "key2" to "value2",
                )
            ))
        }
    }

    private val config = ConfigManager()
}
