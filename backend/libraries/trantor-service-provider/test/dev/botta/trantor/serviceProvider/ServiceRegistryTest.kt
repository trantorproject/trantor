@file:Suppress("ClassName")

package dev.botta.trantor.serviceProvider

import dev.botta.trantor.serviceProvider.ServiceLifetimes.*
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class ServiceRegistryTest {
    @Nested
    inner class `add transient service` {
        @Test
        fun `with factory and without key`() {
            registry.addTransient(MyService::class.java, { MyClass() })

            assertThat(registry[0].key).isNull()
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Transient)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key`() {
            registry.addTransient(MyService::class.java, { MyClass() }, "my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Transient)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and without key`() {
            registry.addTransient(MyService::class.java, MyClass::class.java)

            assertThat(registry[0].key).isNull()
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Transient)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key`() {
            registry.addTransient(MyService::class.java, MyClass::class.java, "my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Transient)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types`() {
            registry.addTransient<MyService, MyClass>("my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Transient)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with single generic type`() {
            registry.addTransient<MyClass>("my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyClass::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Transient)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `override service`() {
            registry.addTransient<MyService>({ MyClass() })
            registry.addTransient<MyService>({ MyClass2() })

            assertThat(registry.size).isEqualTo(1)
            assertThat(registry[0].key).isNull()
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }

        @Test
        fun `override service with same key`() {
            registry.addTransient<MyService>("key1", { MyClass() })
            registry.addTransient<MyService>("key2", { MyClass() })
            registry.addTransient<MyService>("key1", { MyClass2() })

            assertThat(registry.size).isEqualTo(2)
            assertThat(registry[0].key).isEqualTo("key2")
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry[1].key).isEqualTo("key1")
            assertThat(registry[1].implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }
    }

    @Nested
    inner class `add scoped service` {
        @Test
        fun `with factory and without key`() {
            registry.addScoped(MyService::class.java, { MyClass() })

            assertThat(registry[0].key).isNull()
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Scoped)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key`() {
            registry.addScoped(MyService::class.java, { MyClass() }, "my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Scoped)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and without key`() {
            registry.addScoped(MyService::class.java, MyClass::class.java)

            assertThat(registry[0].key).isNull()
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Scoped)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key`() {
            registry.addScoped(MyService::class.java, MyClass::class.java, "my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Scoped)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types`() {
            registry.addScoped<MyService, MyClass>("my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Scoped)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with single generic type`() {
            registry.addScoped<MyClass>("my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyClass::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Scoped)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `override service`() {
            registry.addScoped<MyService>({ MyClass() })
            registry.addScoped<MyService>({ MyClass2() })

            assertThat(registry.size).isEqualTo(1)
            assertThat(registry[0].key).isNull()
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }

        @Test
        fun `override service with same key`() {
            registry.addScoped<MyService>("key1", { MyClass() })
            registry.addScoped<MyService>("key2", { MyClass() })
            registry.addScoped<MyService>("key1", { MyClass2() })

            assertThat(registry.size).isEqualTo(2)
            assertThat(registry[0].key).isEqualTo("key2")
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry[1].key).isEqualTo("key1")
            assertThat(registry[1].implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }
    }

    @Nested
    inner class `add singleton service` {
        @Test
        fun `with factory and without key`() {
            registry.addSingleton(MyService::class.java, { MyClass() })

            assertThat(registry[0].key).isNull()
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Singleton)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with factory and with key`() {
            registry.addSingleton(MyService::class.java, { MyClass() }, "my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Singleton)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with instance and without key`() {
            val obj = MyClass()
            registry.addSingleton<MyService>(obj)

            assertThat(registry[0].key).isNull()
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Singleton)
            assertThat(registry[0].implementationFactory(provider)).isEqualTo(obj)
        }

        @Test
        fun `with instance and with key`() {
            val obj = MyClass()
            registry.addSingleton<MyService>("my key", obj)

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Singleton)
            assertThat(registry[0].implementationFactory(provider)).isEqualTo(obj)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and without key`() {
            registry.addSingleton(MyService::class.java, MyClass::class.java)

            assertThat(registry[0].key).isNull()
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Singleton)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with implementation type and with key`() {
            registry.addSingleton(MyService::class.java, MyClass::class.java, "my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Singleton)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with generic types`() {
            registry.addSingleton<MyService, MyClass>("my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyService::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Singleton)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `with single generic type`() {
            registry.addSingleton<MyClass>("my key")

            assertThat(registry[0].key).isEqualTo("my key")
            assertThat(registry[0].serviceType).isEqualTo(MyClass::class.java)
            assertThat(registry[0].lifetime).isEqualTo(Singleton)
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `override service`() {
            registry.addSingleton<MyService>({ MyClass() })
            registry.addSingleton<MyService>({ MyClass2() })

            assertThat(registry.size).isEqualTo(1)
            assertThat(registry[0].key).isNull()
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }

        @Test
        fun `override service with same key`() {
            registry.addSingleton<MyService>("key1", { MyClass() })
            registry.addSingleton<MyService>("key2", { MyClass() })
            registry.addSingleton<MyService>("key1", { MyClass2() })

            assertThat(registry.size).isEqualTo(2)
            assertThat(registry[0].key).isEqualTo("key2")
            assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
            assertThat(registry[1].key).isEqualTo("key1")
            assertThat(registry[1].implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
        }
    }

    @Test
    fun `override service different lifetime`() {
        registry.addTransient<MyService>({ MyClass() })
        registry.addSingleton<MyService>({ MyClass2() })

        assertThat(registry.size).isEqualTo(1)
        assertThat(registry[0].key).isNull()
        assertThat(registry[0].lifetime).isEqualTo(Singleton)
        assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass2::class.java)
    }

    @Test
    fun `don't fails when adding type with constructor with default args`() {
        assertDoesNotThrow {
            registry.addTransient<ClassWithConstructorWithDefaults>()
        }
    }

    @Test
    fun `tryAdd only adds descriptor if not already declared`() {
        registry.addSingleton<MyService>({ MyClass() })
        registry.tryAdd(ServiceDescriptor.singleton<MyService>({ MyClass2() }))

        assertThat(registry.size).isEqualTo(1)
        assertThat(registry[0].key).isNull()
        assertThat(registry[0].lifetime).isEqualTo(Singleton)
        assertThat(registry[0].implementationFactory(provider)).isInstanceOf(MyClass::class.java)
    }

    private val registry = ServiceRegistry()
    private val provider = mockk<ServiceProvider>()

    interface MyService {
        fun sum(a: Int, b: Int): Int
    }

    class MyClass: MyService {
        override fun sum(a: Int, b: Int) = a + b
    }

    class MyClass2: MyService {
        override fun sum(a: Int, b: Int) = a + b
    }

    class ClassWithoutEmptyConstructor(val param: String): MyService {
        override fun sum(a: Int, b: Int) = a + b
    }

    class ClassWithConstructorWithDefaults(val param: String = "value"): MyService {
        override fun sum(a: Int, b: Int) = a + b
    }
}
