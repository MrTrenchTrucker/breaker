package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Test

class UsernameVectorsTest {
    @Test
    fun `every vector gets the answer the table gives`() {
        for (v in UsernameVectorData.all) {
            assertEquals("vector " + v.name, v.valid, AccountRules.isValidUsername(v.input))
        }
    }

    @Test
    fun `the table holds both valid and refused names`() {
        assertEquals(8, UsernameVectorData.all.count { it.valid })
        assertEquals(16, UsernameVectorData.all.count { !it.valid })
    }
}
