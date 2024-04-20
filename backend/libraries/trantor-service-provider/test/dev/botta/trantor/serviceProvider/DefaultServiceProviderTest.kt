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

    @Test
    fun `get transient service returns implementation class instance`() {
        registry.addTransient<MyService, MyClass>()

        val obj = provider.get<MyService>()

        assertThat(obj).isInstanceOf(MyClass::class.java)
    }

    @Test
    fun `get transient service called multiple times returns different instances`() {
        registry.addTransient<MyService, MyClass>()

        val obj1 = provider.get<MyService>()
        val obj2 = provider.get<MyService>()

        assertThat(obj1).isInstanceOf(MyClass::class.java)
        assertThat(obj2).isInstanceOf(MyClass::class.java)
        assertThat(obj1 !== obj2).isTrue()
    }

    @Test
    fun `get singleton service returns implementation class instance`() {
        registry.addSingleton<MyService, MyClass>()

        val obj = provider.get<MyService>()

        assertThat(obj).isInstanceOf(MyClass::class.java)
    }

    @Test
    fun `get singleton service called multiple times returns same instance`() {
        registry.addSingleton<MyService, MyClass>()

        val obj1 = provider.get<MyService>()
        val obj2 = provider.get<MyService>()

        assertThat(obj1).isInstanceOf(MyClass::class.java)
        assertThat(obj1 === obj2).isTrue()
    }

    @Test
    fun `get singleton service with keys`() {
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
    fun `scoped service fails when not in scope`() {
        registry.addScoped<MyService, MyClass>()

        assertThrows<ServiceNotRegisteredError> { provider.get<MyService>() }
    }

    @Test
    fun `scoped service fails after leaving scope`() {
        registry.addScoped<MyService, MyClass>()
        provider.enterScope()
        provider.leaveScope()

        assertThrows<ServiceNotRegisteredError> { provider.get<MyService>() }
    }

    @Test
    fun `scoped service returns same instance when in scope`() {
        registry.addScoped<MyService, MyClass>()
        provider.enterScope()

        val obj1 = provider.get<MyService>()
        val obj2 = provider.get<MyService>()

        assertThat(obj1).isInstanceOf(MyClass::class.java)
        assertThat(obj1 === obj2).isTrue()
    }

    @Test
    fun `scoped service with key`() {
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

    private val registry = ServiceRegistry()
    private val provider = DefaultServiceProvider(registry)

    interface MyService {
        fun sum(a: Int, b: Int): Int
    }

    class MyClass: MyService {
        override fun sum(a: Int, b: Int) = a + b
    }
}
