@file:Suppress("ClassName")

package dev.botta.trantor.di

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.di.ServiceLifetimes.*
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class ServiceRegistryTest {
    @Nested
    inner class `add transient service` {
        @Test
        fun `with factory and without key`() {
            registry.addTransient(MyService::class.java, { MyClass() })

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key`() {
            registry.addTransient(MyService::class.java, { MyClass() }, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and without key`() {
            registry.addTransient(MyService::class.java, MyClass::class.java)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key`() {
            registry.addTransient(MyService::class.java, MyClass::class.java, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types`() {
            registry.addTransient<MyService, MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with single generic type`() {
            registry.addTransient<MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyClass::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `declare multiple services for same type`() {
            registry.addTransient<MyService, MyClass>()
            registry.addTransient<MyService, MyClass2>()

            assertThat(registry.size).isGreaterThanOrEqualTo(2)
            assertThat(registry.secondLast().key).isNull()
            assertThat(registry.secondLast().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry.last().key).isNull()
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }

        @Test
        fun `declare multiple services with same key`() {
            registry.addTransient<MyService, MyClass>("key1")
            registry.addTransient<MyService, MyClass>("key2")
            registry.addTransient<MyService, MyClass2>("key1")

            assertThat(registry.size).isGreaterThanOrEqualTo(3)
            assertThat(registry.thirdLast().key).isEqualTo("key1")
            assertThat(registry.thirdLast().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry.secondLast().key).isEqualTo("key2")
            assertThat(registry.secondLast().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry.last().key).isEqualTo("key1")
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }
    }

    @Nested
    inner class `add transient service if missing` {
        @Test
        fun `with factory and without key and no previous definition`() {
            registry.addTransientIfMissing(MyService::class.java, { MyClass() })

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and without key and previous definition`() {
            registry.addTransient(MyService::class.java, { MyClass() })

            registry.addTransientIfMissing(MyService::class.java, { MyClass2() })

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key and no previous definition`() {
            registry.addTransientIfMissing(MyService::class.java, { MyClass() }, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key and previous definition`() {
            registry.addTransient(MyService::class.java, { MyClass() }, "my key")

            registry.addTransientIfMissing(MyService::class.java, { MyClass2() }, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and without key and no previous definition`() {
            registry.addTransientIfMissing(MyService::class.java, MyClass::class.java)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and without key and previous definition`() {
            registry.addTransient(MyService::class.java, MyClass::class.java)

            registry.addTransientIfMissing(MyService::class.java, MyClass2::class.java)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key and no previous definition`() {
            registry.addTransientIfMissing(MyService::class.java, MyClass::class.java, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key and previous definition`() {
            registry.addTransient(MyService::class.java, MyClass::class.java, "my key")

            registry.addTransientIfMissing(MyService::class.java, MyClass2::class.java, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types and no previous definition`() {
            registry.addTransientIfMissing<MyService, MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types and previous definition`() {
            registry.addTransient<MyService, MyClass>("my key")

            registry.addTransientIfMissing<MyService, MyClass2>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with single generic type and no previous definition`() {
            registry.addTransientIfMissing<MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyClass::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Transient)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }
    }

    @Nested
    inner class `add scoped service` {
        @Test
        fun `with factory and without key`() {
            registry.addScoped(MyService::class.java, { MyClass() })

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key`() {
            registry.addScoped(MyService::class.java, { MyClass() }, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and without key`() {
            registry.addScoped(MyService::class.java, MyClass::class.java)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key`() {
            registry.addScoped(MyService::class.java, MyClass::class.java, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types`() {
            registry.addScoped<MyService, MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with single generic type`() {
            registry.addScoped<MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyClass::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `declare multiple services for same type`() {
            registry.addScoped<MyService, MyClass>()
            registry.addScoped<MyService, MyClass2>()

            assertThat(registry.size).isGreaterThanOrEqualTo(2)
            assertThat(registry.secondLast().key).isNull()
            assertThat(registry.secondLast().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry.last().key).isNull()
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }

        @Test
        fun `declare multiple services with same key`() {
            registry.addScoped<MyService, MyClass>("key1")
            registry.addScoped<MyService, MyClass>("key2")
            registry.addScoped<MyService, MyClass2>("key1")

            assertThat(registry.size).isGreaterThanOrEqualTo(3)
            assertThat(registry.thirdLast().key).isEqualTo("key1")
            assertThat(registry.thirdLast().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry.secondLast().key).isEqualTo("key2")
            assertThat(registry.secondLast().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry.last().key).isEqualTo("key1")
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }
    }

    @Nested
    inner class `add scoped service if missing` {
        @Test
        fun `with factory and without key and no previous definition`() {
            registry.addScopedIfMissing(MyService::class.java, { MyClass() })

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and without key and previous definition`() {
            registry.addScoped(MyService::class.java, { MyClass() })

            registry.addScopedIfMissing(MyService::class.java, { MyClass2() })

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key and no previous definition`() {
            registry.addScopedIfMissing(MyService::class.java, { MyClass() }, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key and previous definition`() {
            registry.addScoped(MyService::class.java, { MyClass() }, "my key")

            registry.addScopedIfMissing(MyService::class.java, { MyClass2() }, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and without key and no previous definition`() {
            registry.addScopedIfMissing(MyService::class.java, MyClass::class.java)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }


        @Test
        fun `with implementation type and without key and previous definition`() {
            registry.addScoped(MyService::class.java, MyClass::class.java)

            registry.addScopedIfMissing(MyService::class.java, MyClass2::class.java)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key and no previous definition`() {
            registry.addScopedIfMissing(MyService::class.java, MyClass::class.java, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key and previous definition`() {
            registry.addScoped(MyService::class.java, MyClass::class.java, "my key")

            registry.addScopedIfMissing(MyService::class.java, MyClass::class.java, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types and no previous definition`() {
            registry.addScopedIfMissing<MyService, MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types and previous definition`() {
            registry.addScoped<MyService, MyClass>("my key")

            registry.addScopedIfMissing<MyService, MyClass2>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with single generic type and no previous definition`() {
            registry.addScopedIfMissing<MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyClass::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Scoped)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }
    }

    @Nested
    inner class `add singleton service` {
        @Test
        fun `with factory and without key`() {
            registry.addSingleton(MyService::class.java, { MyClass() })

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key`() {
            registry.addSingleton(MyService::class.java, { MyClass() }, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with instance and without key`() {
            val obj = MyClass()
            registry.addSingleton<MyService>(obj)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isEqualTo(obj)
        }

        @Test
        fun `with instance and with key`() {
            val obj = MyClass()
            registry.addSingleton<MyService>("my key", obj)

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isEqualTo(obj)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and without key`() {
            registry.addSingleton(MyService::class.java, MyClass::class.java)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key`() {
            registry.addSingleton(MyService::class.java, MyClass::class.java, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types`() {
            registry.addSingleton<MyService, MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with single generic type`() {
            registry.addSingleton<MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyClass::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }


        @Test
        fun `declare multiple services for same type`() {
            registry.addSingleton<MyService, MyClass>()
            registry.addSingleton<MyService, MyClass2>()

            assertThat(registry.size).isGreaterThanOrEqualTo(2)
            assertThat(registry.secondLast().key).isNull()
            assertThat(registry.secondLast().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry.last().key).isNull()
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }

        @Test
        fun `declare multiple services with same key`() {
            registry.addSingleton<MyService, MyClass>("key1")
            registry.addSingleton<MyService, MyClass>("key2")
            registry.addSingleton<MyService, MyClass2>("key1")

            assertThat(registry.size).isGreaterThanOrEqualTo(3)
            assertThat(registry.thirdLast().key).isEqualTo("key1")
            assertThat(registry.thirdLast().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry.secondLast().key).isEqualTo("key2")
            assertThat(registry.secondLast().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry.last().key).isEqualTo("key1")
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }
    }

    @Nested
    inner class `add singleton service if missing` {
        @Test
        fun `with factory and without key and no previous definition`() {
            registry.addSingletonIfMissing(MyService::class.java, { MyClass() })

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and without key and previous definition`() {
            registry.addSingleton(MyService::class.java, { MyClass() })

            registry.addSingletonIfMissing(MyService::class.java, { MyClass2() })

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key and no previous definition`() {
            registry.addSingletonIfMissing(MyService::class.java, { MyClass() }, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key and previous definition`() {
            registry.addSingleton(MyService::class.java, { MyClass() }, "my key")

            registry.addSingletonIfMissing(MyService::class.java, { MyClass2() }, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with instance and without key and no previous definition`() {
            val obj = MyClass()
            registry.addSingletonIfMissing<MyService>(obj)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isEqualTo(obj)
        }

        @Test
        fun `with instance and without key and previous definition`() {
            val obj = MyClass()
            val obj2 = MyClass()
            registry.addSingleton<MyService>(obj)

            registry.addSingletonIfMissing<MyService>(obj2)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isEqualTo(obj)
        }

        @Test
        fun `with instance and with key and no previous definition`() {
            val obj = MyClass()
            registry.addSingletonIfMissing<MyService>("my key", obj)

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isEqualTo(obj)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with instance and with key and previous definition`() {
            val obj = MyClass()
            val obj2 = MyClass()
            registry.addSingleton<MyService>("my key", obj)

            registry.addSingletonIfMissing<MyService>("my key", obj2)

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isEqualTo(obj)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and without key and no previous definition`() {
            registry.addSingletonIfMissing(MyService::class.java, MyClass::class.java)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and without key and previous definition`() {
            registry.addSingleton(MyService::class.java, MyClass::class.java)

            registry.addSingletonIfMissing(MyService::class.java, MyClass2::class.java)

            assertThat(registry.last().key).isNull()
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key and no previous definition`() {
            registry.addSingletonIfMissing(MyService::class.java, MyClass::class.java, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key and previous definition`() {
            registry.addSingleton(MyService::class.java, MyClass::class.java, "my key")

            registry.addSingletonIfMissing(MyService::class.java, MyClass2::class.java, "my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types and no previous definition`() {
            registry.addSingletonIfMissing<MyService, MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types and previous definition`() {
            registry.addSingleton<MyService, MyClass>("my key")

            registry.addSingletonIfMissing<MyService, MyClass2>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyService::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with single generic type and no previous definition`() {
            registry.addSingletonIfMissing<MyClass>("my key")

            assertThat(registry.last().key).isEqualTo("my key")
            assertThat(registry.last().serviceType).isEqualTo(MyClass::class.java)
            assertThat(registry.last().lifetime).isEqualTo(Singleton)
            assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }
    }

    @Nested
    inner class `service configurations` {
        @Test
        fun add() {
            registry.addSingleton<MyService, MyClass>()
            val configuration: (MyService, ServiceProvider) -> Unit = { instance, _ -> instance.name = "new name" }
            registry.configure<MyService>(configuration)

            assertThat(registry.getConfigurations<MyService>()).containsExactly(configuration)
        }

        @Test
        fun `add multiple`() {
            registry.addSingleton<MyService, MyClass>()
            val configuration1: (MyService, ServiceProvider) -> Unit = { instance, _ -> instance.name = "new name" }
            registry.configure<MyService>(configuration1)
            val configuration2: (MyService, ServiceProvider) -> Unit = { instance, _ -> instance.name = "other name" }
            registry.configure<MyService>(configuration2)

            assertThat(registry.getConfigurations<MyService>()).containsExactly(configuration1, configuration2)
        }

        @Test
        fun `add with key`() {
            registry.addSingleton<MyService, MyClass>("some key")
            val configuration: (MyService, ServiceProvider) -> Unit = { instance, _ -> instance.name = "new name" }
            registry.configure<MyService>("some key", configuration)

            assertThat(registry.getConfigurations<MyService>()).isEmpty()
            assertThat(registry.getConfigurations<MyService>("some key")).containsExactly(configuration)
        }

        @Test
        fun `add multiple with key`() {
            registry.addSingleton<MyService, MyClass>()
            val configuration1: (MyService, ServiceProvider) -> Unit = { instance, _ -> instance.name = "new name 1" }
            registry.configure<MyService>("some key", configuration1)
            val configuration2: (MyService, ServiceProvider) -> Unit = { instance, _ -> instance.name = "new name 2" }
            registry.configure<MyService>(configuration2)
            val configuration3: (MyService, ServiceProvider) -> Unit = { instance, _ -> instance.name = "new name 3" }
            registry.configure<MyService>("some key", configuration3)

            assertThat(registry.getConfigurations<MyService>()).containsExactly(configuration2)
            assertThat(registry.getConfigurations<MyService>("some key")).containsExactly(
                configuration1,
                configuration3
            )
        }
    }

    @Test
    fun `declare multiple services with different lifetimes`() {
        registry.addTransient<MyService, MyClass>()
        registry.addSingleton<MyService, MyClass2>()

        assertThat(registry.size).isGreaterThanOrEqualTo(2)

        assertThat(registry.secondLast().key).isNull()
        assertThat(registry.secondLast().lifetime).isEqualTo(Transient)
        assertThat(registry.secondLast().implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        assertThat(registry.last().key).isNull()
        assertThat(registry.last().lifetime).isEqualTo(Singleton)
        assertThat(registry.last().implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
    }

    @Test
    fun `don't fails when adding type with constructor with default args`() {
        assertDoesNotThrow {
            registry.addTransient<ClassWithConstructorWithDefaults>()
        }
    }

    private fun ServiceRegistry.secondLast() = registry[registry.lastIndex - 1]

    private fun ServiceRegistry.thirdLast() = registry[registry.lastIndex - 2]

    private val config = ConfigManager()
    private val registry = ServiceRegistry(config)
    private val provider = mockk<ServiceProvider>()

    interface MyService {
        var name: String
        fun sum(a: Int, b: Int): Int
    }

    class MyClass: MyService {
        override var name: String = ""

        override fun sum(a: Int, b: Int) = a + b
    }

    class MyClass2: MyService {
        override var name: String = ""

        override fun sum(a: Int, b: Int) = a + b
    }

    class ClassWithoutEmptyConstructor(val param: String): MyService {
        override var name: String = ""

        override fun sum(a: Int, b: Int) = a + b
    }

    class ClassWithConstructorWithDefaults(val param: String = "value"): MyService {
        override var name: String = ""

        override fun sum(a: Int, b: Int) = a + b
    }
}
