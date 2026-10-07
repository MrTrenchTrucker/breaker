package dev.breaker.server.syncapi.db

import java.sql.SQLException
import java.util.Locale
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

internal class SchemaV1Test : TempDatabaseTest() {

    private lateinit var temp: TempDatabase

    @Before
    fun openDatabase() {
        temp = openTemp()
    }

    private fun insert(row: Map<String, Any>) {
        inTransaction(temp) { connection -> TestDatabases.insertAccountRow(connection, row) }
    }

    private fun rowCount(): Long =
        inTransaction(temp) { connection -> TestDatabases.longs(connection, "SELECT count(*) FROM accounts").single() }

    // username_lower is derived the way the column rule demands, so a bad username
    // is the only defect in the row and not also a lower-case mismatch.
    private fun rowWithUsername(name: String, others: Map<String, Any> = emptyMap()): Map<String, Any> {
        val names: Map<String, Any> = mapOf("username" to name, "username_lower" to name.lowercase(Locale.ROOT))
        return TestDatabases.validAccountRow(names + others)
    }

    private fun assertRefused(case: String, row: Map<String, Any>) {
        val before = rowCount()
        val refusal: SQLException? = try {
            insert(row)
            null
        } catch (failure: SQLException) {
            failure
        }
        assertNotNull("sync-api: the database accepted a row with $case", refusal)
        assertTrue(
            "sync-api: $case was refused for a reason other than a constraint: ${refusal?.message}",
            refusal?.message.orEmpty().contains("constraint", ignoreCase = true),
        )
        assertEquals("sync-api: a refused row with $case was stored", before, rowCount())
    }

    private fun assertAccepted(case: String, row: Map<String, Any>) {
        val before = rowCount()
        try {
            insert(row)
        } catch (failure: SQLException) {
            fail("sync-api: the database refused a valid row with $case: ${failure.message}")
        }
        assertEquals("sync-api: the accepted row with $case was not stored", before + 1, rowCount())
    }

    @Test
    fun `the valid control row is accepted`() {
        assertAccepted("the valid control row", TestDatabases.validAccountRow())
    }

    @Test
    fun `a mixed case username is accepted when username_lower is its lower case`() {
        assertAccepted("username Alice and username_lower alice", rowWithUsername("Alice"))
    }

    @Test
    fun `the accepted username characters and lengths are accepted`() {
        val names = listOf("a", "a".repeat(64), "A.b_c-9")
        for ((index, name) in names.withIndex()) {
            // Distinct lower-case names so the rows do not collide with each other.
            val unique = if (name.length == 64) name else name + index
            assertAccepted("username '${unique.take(12)}' of ${unique.length} characters", rowWithUsername(unique))
        }
    }

    @Test
    fun `a username with a space is refused`() {
        assertRefused("a space in the username", rowWithUsername("ali ce"))
    }

    @Test
    fun `a username with a non ascii letter is refused`() {
        assertRefused("a non ascii letter in the username", rowWithUsername("al\u00efce"))
    }

    @Test
    fun `a username with a control character is refused`() {
        assertRefused("a control character in the username", rowWithUsername("ali\u0007ce"))
    }

    @Test
    fun `a username with a trailing newline is refused`() {
        assertRefused("a trailing newline in the username", rowWithUsername("alice\n"))
    }

    @Test
    fun `a username of 65 characters is refused`() {
        assertRefused("a 65 character username", rowWithUsername("a".repeat(65)))
    }

    @Test
    fun `an empty username is refused`() {
        assertRefused("an empty username", rowWithUsername(""))
    }

    @Test
    fun `a username_lower that is not the lower case of the username is refused`() {
        assertRefused(
            "username alice and username_lower bob",
            TestDatabases.validAccountRow(mapOf("username_lower" to "bob")),
        )
    }

    @Test
    fun `a second username that differs only by case is refused`() {
        insert(rowWithUsername("alice"))
        assertRefused("username ALICE next to an existing alice", rowWithUsername("ALICE"))
    }

    @Test
    fun `a role outside the allow list is refused`() {
        assertRefused("role root", TestDatabases.validAccountRow(mapOf("role" to "root")))
    }

    @Test
    fun `a salt that is not 16 bytes is refused`() {
        for (size in listOf(15, 17)) {
            assertRefused("a salt of $size bytes", TestDatabases.validAccountRow(mapOf("salt" to ByteArray(size))))
        }
    }

    @Test
    fun `kdf memory outside 64 to 256 MiB is refused and the edges are accepted`() {
        for (value in listOf(65535, 262145)) {
            assertRefused("kdf_memory_kib $value", TestDatabases.validAccountRow(mapOf("kdf_memory_kib" to value)))
        }
        for ((index, value) in listOf(65536, 262144).withIndex()) {
            assertAccepted("kdf_memory_kib $value", rowWithUsername("memory$index", mapOf("kdf_memory_kib" to value)))
        }
    }

    @Test
    fun `kdf iterations outside 3 to 10 are refused and the edges are accepted`() {
        for (value in listOf(2, 11)) {
            assertRefused("kdf_iterations $value", TestDatabases.validAccountRow(mapOf("kdf_iterations" to value)))
        }
        for ((index, value) in listOf(3, 10).withIndex()) {
            assertAccepted("kdf_iterations $value", rowWithUsername("iter$index", mapOf("kdf_iterations" to value)))
        }
    }

    @Test
    fun `kdf parallelism outside 1 to 4 is refused and the edges are accepted`() {
        for (value in listOf(0, 5)) {
            assertRefused("kdf_parallelism $value", TestDatabases.validAccountRow(mapOf("kdf_parallelism" to value)))
        }
        for ((index, value) in listOf(1, 4).withIndex()) {
            assertAccepted("kdf_parallelism $value", rowWithUsername("par$index", mapOf("kdf_parallelism" to value)))
        }
    }

    @Test
    fun `a kdf version other than 1 is refused`() {
        for (value in listOf(0, 2, 1000, -1)) {
            assertRefused("kdf_version $value", TestDatabases.validAccountRow(mapOf("kdf_version" to value)))
        }
    }

    @Test
    fun `a verifier iteration count of 0 is refused`() {
        assertRefused("verifier_iters 0", TestDatabases.validAccountRow(mapOf("verifier_iters" to 0)))
    }

    @Test
    fun `a verifier salt that is not 16 bytes is refused`() {
        for (size in listOf(15, 17)) {
            assertRefused(
                "a verifier_salt of $size bytes",
                TestDatabases.validAccountRow(mapOf("verifier_salt" to ByteArray(size))),
            )
        }
    }

    @Test
    fun `a verifier hash that is not 32 bytes is refused`() {
        for (size in listOf(31, 33)) {
            assertRefused(
                "a verifier_hash of $size bytes",
                TestDatabases.validAccountRow(mapOf("verifier_hash" to ByteArray(size))),
            )
        }
    }
}
