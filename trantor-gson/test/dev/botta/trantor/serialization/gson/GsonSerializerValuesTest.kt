@file:Suppress("ClassName")

package dev.botta.trantor.serialization.gson

import dev.botta.json.Json
import dev.botta.trantor.domain.Email
import dev.botta.trantor.domain.Id
import dev.botta.trantor.domain.InvalidEmailError
import dev.botta.trantor.domain.Money
import dev.botta.trantor.primitives.serialization.deserializeList
import dev.botta.trantor.primitives.serialization.deserializeMap
import dev.botta.trantor.serialization.gson.adapters.StringValueSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.util.*

class GsonSerializerValuesTest {
    @Nested
    inner class `dates and times` {
        @Test
        fun `a date is written as ISO 8601, so anything can read it`() {
            assertThat(fieldOf(Booking(date = LocalDate.of(2026, 9, 20)))).isEqualTo("2026-09-20")
        }

        @Test
        fun `and read back as the same date`() {
            assertThat(read<Booking>("date", "2026-09-20").date).isEqualTo(LocalDate.of(2026, 9, 20))
        }

        @Test
        fun `a date and time keeps both halves`() {
            val at = LocalDateTime.of(2026, 9, 20, 12, 30, 15)

            assertThat(roundTrip(Booking(at = at)).at).isEqualTo(at)
        }

        @Test
        fun `a time of day keeps its minutes`() {
            assertThat(roundTrip(Booking(from = LocalTime.of(9, 30))).from).isEqualTo(LocalTime.of(9, 30))
        }

        @Test
        fun `a year and month is a month, not a day in it`() {
            assertThat(fieldOf(Booking(period = YearMonth.of(2026, 9)), "period")).isEqualTo("2026-09")
            assertThat(roundTrip(Booking(period = YearMonth.of(2026, 9))).period).isEqualTo(YearMonth.of(2026, 9))
        }

        @Test
        fun `a date that was never set stays null`() {
            assertThat(roundTrip(Booking()).date).isNull()
        }
    }

    @Nested
    inner class `money` {
        @Test
        fun `is written as a plain number, never in scientific notation`() {
            assertThat(fieldOf(Invoice(total = Money("1000000.55")), "total")).isEqualTo("1000000.55")
        }

        @Test
        fun `comes back as the same amount`() {
            assertThat(roundTrip(Invoice(total = Money("150.25"))).total).isEqualTo(Money("150.25"))
        }

        @Test
        fun `keeps the cents, which a double would round away`() {
            assertThat(roundTrip(Invoice(total = Money("0.07"))).total).isEqualTo(Money("0.07"))
        }
    }

    @Nested
    inner class `email` {
        @Test
        fun `is written as the address itself`() {
            assertThat(fieldOf(Customer(email = Email("nico@example.com")), "email")).isEqualTo("nico@example.com")
        }

        @Test
        fun `comes back as the same address`() {
            assertThat(roundTrip(Customer(email = Email("nico@example.com"))).email)
                .isEqualTo(Email("nico@example.com"))
        }

        @Test
        fun `an address that does not exist is refused on the way in, not stored broken`() {
            assertThatThrownBy { read<Customer>("email", "not an address") }
                .isInstanceOf(InvalidEmailError::class.java)
        }
    }

    @Nested
    inner class `ids` {
        @Test
        fun `are written as their uuid`() {
            val raw = UUID.randomUUID()

            assertThat(fieldOf(Order(id = OrderId(raw)), "id")).isEqualTo(raw.toString())
        }

        @Test
        fun `come back as the same id of the same kind`() {
            val id = OrderId(UUID.randomUUID())

            val back = roundTrip(Order(id = id)).id

            assertThat(back).isEqualTo(id)
            assertThat(back).isInstanceOf(OrderId::class.java)
        }

        @Test
        fun `an id that was never set stays null`() {
            assertThat(roundTrip(Order()).id).isNull()
        }
    }

    @Nested
    inner class `collections` {
        @Test
        fun `a list comes back as a list`() {
            val list = serializer.deserializeList("""[{"total":"10"},{"total":"20"}]""", Invoice::class.java)

            assertThat(list).hasSize(2)
            assertThat(list[0].total).isEqualTo(Money("10"))
        }

        @Test
        fun `a set drops the repeats`() {
            val set = serializer.deserializeSet("""["a","b","a"]""", String::class.java)

            assertThat(set).containsExactlyInAnyOrder("a", "b")
        }

        @Test
        fun `a map keeps its keys`() {
            val map = serializer.deserializeMap<String, Int>("""{"a":1,"b":2}""")

            assertThat(map).containsEntry("a", 1).containsEntry("b", 2)
        }

        @Test
        fun `a list of numbers works, although Int is a primitive on the jvm`() {
            assertThat(serializer.deserializeList<Int>("[1,2,3]")).containsExactly(1, 2, 3)
        }
    }

    @Nested
    inner class `an adapter of the application` {
        @Test
        fun `is used for the type it was registered for`() {
            serializer.registerTypeAdapter<Money>(StringValueSerializer({ Money(it) }, { "ARS ${it.plainString()}" }))

            assertThat(fieldOf(Invoice(total = Money("150")), "total")).isEqualTo("ARS 150")
        }
    }

    private fun fieldOf(value: Any, field: String = "date") =
        Json.parse(serializer.serialize(value)).asObject()?.get(field)?.asString()

    private inline fun <reified T> roundTrip(value: T): T = serializer.deserialize(serializer.serialize(value))

    private inline fun <reified T> read(field: String, value: String): T =
        serializer.deserialize(Json.obj(field to value).toString())

    class Booking(
        val date: LocalDate? = null,
        val at: LocalDateTime? = null,
        val from: LocalTime? = null,
        val period: YearMonth? = null,
    )

    class Invoice(val total: Money? = null)

    class Customer(val email: Email? = null)

    class Order(val id: OrderId? = null)

    class OrderId(raw: UUID): Id(raw)

    private val serializer = GsonSerializer()
}
