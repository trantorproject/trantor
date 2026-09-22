@file:Suppress("ClassName")

package dev.botta.trantor.ai

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RunContextTest {
    @Test
    fun `finds a value by its type`() {
        val run = RunContext(Tenant("acme"), Locale("es"))

        assertThat(run.get<Tenant>()).isEqualTo(Tenant("acme"))
        assertThat(run.get<Locale>()).isEqualTo(Locale("es"))
    }

    @Test
    fun `and by an interface it implements`() {
        val run = RunContext(Tenant("acme"))

        assertThat(run.get<Scope>()).isEqualTo(Tenant("acme"))
    }

    @Test
    fun `null when there is nothing of that type`() {
        assertThat(RunContext().get<Tenant>()).isNull()
    }

    @Test
    fun `require fails saying what there is, which is what someone who forgot to pass it needs`() {
        assertThatThrownBy { RunContext(Locale("es")).require<Tenant>() }
            .hasMessage("There is no Tenant in the run context. It has: Locale")
    }

    @Test
    fun `require gives the value when it is there`() {
        assertThat(RunContext(Tenant("acme")).require<Tenant>()).isEqualTo(Tenant("acme"))
    }

    interface Scope

    data class Tenant(val name: String): Scope

    data class Locale(val language: String)
}
