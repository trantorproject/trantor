@file:Suppress("ClassName")

package dev.botta.trantor.web.broadcast

import dev.botta.trantor.web.testing.FakeClientSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionManagerTest {
    @Test
    fun `starts with nobody connected`() {
        assertThat(manager.all).isEmpty()
    }

    @Test
    fun `holds what connects`() {
        val session = FakeClientSession()

        manager.add(session)

        assertThat(manager.all).containsExactly(session)
    }

    @Test
    fun `drops what disconnects`() {
        val session = FakeClientSession()
        manager.add(session)

        manager.remove(session)

        assertThat(manager.all).isEmpty()
    }

    @Test
    fun `removing one leaves the others`() {
        val one = FakeClientSession()
        val other = FakeClientSession()
        manager.add(one)
        manager.add(other)

        manager.remove(one)

        assertThat(manager.all).containsExactly(other)
    }

    @Test
    fun `removing one that was never there changes nothing`() {
        val session = FakeClientSession()
        manager.add(session)

        manager.remove(FakeClientSession())

        assertThat(manager.all).containsExactly(session)
    }

    @Test
    fun `the same session added twice is one connection`() {
        val session = FakeClientSession()

        manager.add(session)
        manager.add(session)

        assertThat(manager.all).hasSize(1)
    }

    @Test
    fun `a reconnection with the same id replaces the old one`() {
        val old = FakeClientSession()
        val reconnected = FakeClientSession(id = old.id)
        manager.add(old)

        manager.add(reconnected)

        assertThat(manager.all).containsExactly(reconnected)
    }

    private val manager = SessionManager()
}
