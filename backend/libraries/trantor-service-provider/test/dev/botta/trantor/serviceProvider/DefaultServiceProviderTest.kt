@file:Suppress("ClassName")

package dev.botta.trantor.serviceProvider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class DefaultServiceProviderTest {
    @Test
    fun `fails when service is not registered`() {
        assertThrows<ServiceNotRegisteredError> { provider.get<MyService>() }
    }

    @Test
    fun `fails when service is not registered with given key`() {
        registry.addTransient<MyService, MyClass>("some key")

        assertThrows<ServiceNotRegisteredError> { provider.get<MyService>("other key") }
    }

    @Nested
    inner class `get transient service` {
        @Test
        fun `returns implementation class instance`() {
            registry.addTransient<MyService, MyClass>()

            val obj = provider.get<MyService>()

            assertThat(obj).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `called multiple times returns different instances`() {
            registry.addTransient<MyService, MyClass>()

            val obj1 = provider.get<MyService>()
            val obj2 = provider.get<MyService>()

            assertThat(obj1).isInstanceOf(MyClass::class.java)
            assertThat(obj2).isInstanceOf(MyClass::class.java)
            assertThat(obj1 !== obj2).isTrue()
        }

        @Test
        fun `applies configuration for each instance`() {
            registry.addTransient<MyService, MyClass>()
            registry.configure<MyService> { it.name = "new name" }

            val obj1 = provider.get<MyService>()
            val obj2 = provider.get<MyService>()

            assertThat(obj1.name).isEqualTo("new name")
            assertThat(obj2.name).isEqualTo("new name")
        }

        @Test
        fun `applies all configurations for each instance`() {
            registry.addTransient<MyService, MyClass>()
            registry.configure<MyService> { it.name = "new name 1" }
            registry.configure<MyService> { it.name = "new name 2" }

            val obj1 = provider.get<MyService>()
            val obj2 = provider.get<MyService>()

            assertThat(obj1.name).isEqualTo("new name 2")
            assertThat(obj2.name).isEqualTo("new name 2")
        }

        @Test
        fun `applies all configurations matching key for each instance`() {
            registry.addTransient<MyService, MyClass>()
            registry.addTransient<MyService, MyClass>("some key")
            registry.configure<MyService>("some key") { it.name = "new name 1" }
            registry.configure<MyService>("some key") { it.name = "new name 2" }
            registry.configure<MyService> { it.name = "new name 3" }

            val obj1 = provider.get<MyService>("some key")
            val obj2 = provider.get<MyService>("some key")
            val obj3 = provider.get<MyService>()

            assertThat(obj1.name).isEqualTo("new name 2")
            assertThat(obj2.name).isEqualTo("new name 2")
            assertThat(obj3.name).isEqualTo("new name 3")
        }
    }

    @Nested
    inner class `get singleton service` {
        @Test
        fun `returns implementation class instance`() {
            registry.addSingleton<MyService, MyClass>()

            val obj = provider.get<MyService>()

            assertThat(obj).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `called multiple times returns same instance`() {
            registry.addSingleton<MyService, MyClass>()

            val obj1 = provider.get<MyService>()
            val obj2 = provider.get<MyService>()

            assertThat(obj1).isInstanceOf(MyClass::class.java)
            assertThat(obj1 === obj2).isTrue()
        }

        @Test
        fun `with keys`() {
            registry.addSingleton<MyService, MyClass>()
            registry.addSingleton<MyService, MyClass>("some key")

            val obj1 = provider.get<MyService>()
            val obj2 = provider.get<MyService>("some key")
            val obj3 = provider.get<MyService>("some key")

            assertThat(obj1).isInstanceOf(MyClass::class.java)
            assertThat(obj2).isInstanceOf(MyClass::class.java)
            assertThat(obj1 !== obj2).isTrue()
            assertThat(obj2 === obj3).isTrue()
        }

        @Test
        fun `applies configuration`() {
            registry.addSingleton<MyService, MyClass>()
            registry.configure<MyService> { it.name = "new name" }

            val obj = provider.get<MyService>()

            assertThat(obj.name).isEqualTo("new name")
        }

        @Test
        fun `applies all configurations`() {
            registry.addSingleton<MyService, MyClass>()
            registry.configure<MyService> { it.name = "new name 1" }
            registry.configure<MyService> { it.name = "new name 2" }

            val obj = provider.get<MyService>()

            assertThat(obj.name).isEqualTo("new name 2")
        }

        @Test
        fun `applies all configurations matching key for each instance`() {
            registry.addSingleton<MyService, MyClass>()
            registry.addSingleton<MyService, MyClass>("some key")
            registry.configure<MyService>("some key") { it.name = "new name 1" }
            registry.configure<MyService>("some key") { it.name = "new name 2" }
            registry.configure<MyService> { it.name = "new name 3" }

            val obj1 = provider.get<MyService>("some key")
            val obj2 = provider.get<MyService>()

            assertThat(obj1.name).isEqualTo("new name 2")
            assertThat(obj2.name).isEqualTo("new name 3")
        }
    }

    @Nested
    inner class `get scoped service` {
        @Test
        fun `fails when not in scope`() {
            registry.addScoped<MyService, MyClass>()

            assertThrows<ServiceNotRegisteredError> { provider.get<MyService>() }
        }

        @Test
        fun `fails after leaving scope`() {
            registry.addScoped<MyService, MyClass>()
            provider.enterScope()
            provider.leaveScope()

            assertThrows<ServiceNotRegisteredError> { provider.get<MyService>() }
        }

        @Test
        fun `returns same instance when in scope`() {
            registry.addScoped<MyService, MyClass>()
            provider.enterScope()

            val obj1 = provider.get<MyService>()
            val obj2 = provider.get<MyService>()

            assertThat(obj1).isInstanceOf(MyClass::class.java)
            assertThat(obj1 === obj2).isTrue()
        }

        @Test
        fun `with key`() {
            registry.addScoped<MyService, MyClass>()
            registry.addScoped<MyService, MyClass>("some key")
            provider.enterScope()

            val obj1 = provider.get<MyService>()
            val obj2 = provider.get<MyService>("some key")
            val obj3 = provider.get<MyService>("some key")

            assertThat(obj1).isInstanceOf(MyClass::class.java)
            assertThat(obj2).isInstanceOf(MyClass::class.java)
            assertThat(obj1 !== obj2).isTrue()
            assertThat(obj2 === obj3).isTrue()
        }

        @Test
        fun `applies configuration`() {
            registry.addScoped<MyService, MyClass>()
            registry.configure<MyService> { it.name = "new name" }
            provider.enterScope()

            val obj = provider.get<MyService>()

            assertThat(obj.name).isEqualTo("new name")
        }

        @Test
        fun `applies all configurations`() {
            registry.addScoped<MyService, MyClass>()
            registry.configure<MyService> { it.name = "new name 1" }
            registry.configure<MyService> { it.name = "new name 2" }
            provider.enterScope()

            val obj = provider.get<MyService>()

            assertThat(obj.name).isEqualTo("new name 2")
        }

        @Test
        fun `applies all configurations matching key for each instance`() {
            registry.addScoped<MyService, MyClass>()
            registry.addScoped<MyService, MyClass>("some key")
            registry.configure<MyService>("some key") { it.name = "new name 1" }
            registry.configure<MyService>("some key") { it.name = "new name 2" }
            registry.configure<MyService> { it.name = "new name 3" }
            provider.enterScope()

            val obj1 = provider.get<MyService>("some key")
            val obj2 = provider.get<MyService>()

            assertThat(obj1.name).isEqualTo("new name 2")
            assertThat(obj2.name).isEqualTo("new name 3")
        }
    }

    @Nested
    inner class getAll {
        @Test
        fun `returns a new instance for each declaration`() {
            registry.addTransient<MyService, MyClass>()
            registry.addSingleton<MyService, MyClass2>()

            val services1 = provider.getAll<MyService>()
            val services2 = provider.getAll<MyService>()

            assertThat(services1.size).isEqualTo(2)
            assertThat(services1[0]).isInstanceOf(MyClass::class.java)
            assertThat(services1[1]).isInstanceOf(MyClass2::class.java)
            assertThat(services2.size).isEqualTo(2)
            assertThat(services2[0]).isInstanceOf(MyClass::class.java)
            assertThat(services2[1]).isInstanceOf(MyClass2::class.java)
            assertThat(services1[0] !== services2[0]).isTrue()
            assertThat(services1[1] === services2[1]).isTrue()
        }

        @Test
        fun `returns a new instance for each declaration with given key`() {
            registry.addSingleton<MyService, MyClass2>()
            registry.addTransient<MyService, MyClass>("some key")
            registry.addSingleton<MyService, MyClass2>("some key")

            val services1 = provider.getAll<MyService>("some key")
            val services2 = provider.getAll<MyService>("some key")

            assertThat(services1.size).isEqualTo(2)
            assertThat(services1[0]).isInstanceOf(MyClass::class.java)
            assertThat(services1[1]).isInstanceOf(MyClass2::class.java)
            assertThat(services2.size).isEqualTo(2)
            assertThat(services2[0]).isInstanceOf(MyClass::class.java)
            assertThat(services2[1]).isInstanceOf(MyClass2::class.java)
            assertThat(services1[0] !== services2[0]).isTrue()
            assertThat(services1[1] === services2[1]).isTrue()
        }

        @Test
        fun `returns each with with all configurations applied`() {
            registry.addTransient<MyService, MyClass>()
            registry.addSingleton<MyService, MyClass2>()
            registry.configure<MyService> { it.name = "new name 1" }
            registry.configure<MyService> { it.name = "new name 2" }

            val services = provider.getAll<MyService>()

            assertThat(services[0].name).isEqualTo("new name 2")
            assertThat(services[1].name).isEqualTo("new name 2")
        }

        @Test
        fun `returns each with with all configurations matching key applied`() {
            registry.addTransient<MyService, MyClass>("some key")
            registry.addSingleton<MyService, MyClass2>("some key")
            registry.configure<MyService>("some key") { it.name = "new name 1" }
            registry.configure<MyService>("some key") { it.name = "new name 2" }
            registry.configure<MyService> { it.name = "new name 3" }

            val services = provider.getAll<MyService>("some key")

            assertThat(services[0].name).isEqualTo("new name 2")
            assertThat(services[1].name).isEqualTo("new name 2")
        }
    }

    @Test
    fun `get with multiple declarations returns last declaration`() {
        registry.addTransient<MyService, MyClass>()
        registry.addSingleton<MyService, MyClass2>()

        val obj1 = provider.get<MyService>()
        val obj2 = provider.get<MyService>()

        assertThat(obj1).isInstanceOf(MyClass2::class.java)
        assertThat(obj1 === obj2).isTrue()
    }

    @Nested
    inner class create {
        @Test
        fun `create class instance without constructor params`() {
            val instance = provider.create<ClassWithoutParams>()

            assertThat(instance).isInstanceOf(ClassWithoutParams::class.java)
        }

        @Test
        fun `create class instance with service param`() {
            registry.addSingleton<MyService, MyClass>()

            val instance = provider.create<ClassWithServiceParam>()

            assertThat(instance).isInstanceOf(ClassWithServiceParam::class.java)
            assertThat(instance.param).isInstanceOf(MyClass::class.java)
        }

        @Test
        fun `create class instance with optional param`() {
            registry.addSingleton<MyService, MyClass>()

            val instance = provider.create<ClassWithOptionalParam>()

            assertThat(instance).isInstanceOf(ClassWithOptionalParam::class.java)
            assertThat(instance.param1).isInstanceOf(MyClass::class.java)
            assertThat(instance.param2).isEqualTo("default")
        }

        @Test
        fun `fail if param not registered`() {
            assertThrows<ServiceNotRegisteredError> {
                provider.create<ClassWithServiceParam>()
            }
        }

        @Test
        fun `create class instance with value resolver param`() {
            registry.addSingleton<ServiceValueResolver, ConfigServiceValueResolver>()

            val instance = provider.create<ClassWithValueResolver>()

            assertThat(instance).isInstanceOf(ClassWithValueResolver::class.java)
            assertThat(instance.param).isEqualTo("config-key")
        }
    }

    private val registry = ServiceRegistry()
    private val provider = DefaultServiceProvider(registry)

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

    class ClassWithoutParams {
    }

    class ClassWithServiceParam(val param: MyService) {
    }

    class ClassWithOptionalParam(val param1: MyService, val param2: String = "default") {
    }

    annotation class ConfigValue(val key: String)

    class ClassWithValueResolver(@param:ConfigValue("config-key") val param: String) {
    }

    class ConfigServiceValueResolver: ServiceValueResolver {
        override val annotationType = ConfigValue::class

        override fun resolve(annotation: Annotation, paramType: Class<*>, services: ServiceProvider): Any {
            return (annotation as ConfigValue).key
        }
    }
}
