package dev.breaker.dictation.history

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's adapter runs a block in a transaction that commits when the block finishes and rolls back when
 * it throws.
 *
 * `SqliteHistoryDatabase` runs in no JVM test, so the fakes that test the store prove the store's use of a
 * transaction, never the adapter's. This reads the adapter's `transaction` method as text through
 * [AdapterTextRules.checkTransaction]: `beginTransaction()` before the `try`, the work and then
 * `setTransactionSuccessful()` (once, last) inside it, `endTransaction()` in the `finally`, and no `catch`.
 * That `endTransaction()` without the success mark rolls back is Android's rule, not something run here.
 *
 * The second half runs the same rule on made-up adapters: one written correctly and then reformatted, and
 * each with one mistake planted, so a refusal is seen to be for the mistake and for no other reason.
 */
class AdapterTextTransactionTest {

    // ── the real adapter ─────────────────────────────────────────────────

    @Test
    fun `the adapter's transaction begins before the try, marks success once and last, and ends in a finally`() {
        AdapterTextRules.checkTransaction(AdapterStatements.adapterSource())
    }

    // ── made-up adapters ─────────────────────────────────────────────────

    @Test
    fun `a transaction written correctly is accepted, and so is the same code reformatted`() {
        val variants = mapOf(
            "as the adapter writes it" to transaction(),
            "with the try on the next line" to
                transaction(tryHead = "\n        try\n        {"),
            "with blank lines and a comment between the statements" to
                transaction(afterBegin = "\n\n        // work\n\n"),
            "with the calls spaced out" to
                transaction(success = "database . setTransactionSuccessful ( )"),
            "with the success mark and the return on one line" to
                transaction(tryBody = "val result = block(); database.setTransactionSuccessful(); return result"),
            "with the finally on the next line" to transaction(finallyHead = "\n        finally\n        {"),
            "with a word in a string that is not code" to
                transaction(tryBody = "val note = \"catch try finally\"\n            val result = block()\n" +
                    "            database.setTransactionSuccessful()\n            return result"),
        )
        for ((label, source) in variants) {
            try {
                AdapterTextRules.checkTransaction(source)
            } catch (refused: AdapterShapeError) {
                throw AssertionError("a correct transaction was refused $label: ${refused.message}", refused)
            }
        }
    }

    @Test
    fun `a transaction that is never begun is refused`() {
        refusedFor(transaction(begin = ""), "holds 0 beginTransaction() calls")
    }

    @Test
    fun `a transaction begun inside the try, not before it, is refused`() {
        val source = transaction(
            begin = "",
            tryBody = "database.beginTransaction()\n            val result = block()\n" +
                "            database.setTransactionSuccessful()\n            return result",
        )
        refusedFor(source, "beginTransaction() is after the try")
    }

    @Test
    fun `a transaction that is never marked successful is refused`() {
        refusedFor(transaction(success = ""), "holds 0 setTransactionSuccessful() calls")
    }

    @Test
    fun `a transaction marked successful twice is refused`() {
        val source = transaction(tryBody = "database.setTransactionSuccessful()\n            val result = block()\n" +
            "            database.setTransactionSuccessful()\n            return result")
        refusedFor(source, "holds 2 setTransactionSuccessful() calls")
    }

    @Test
    fun `a transaction marked successful before the block has run is refused`() {
        val source = transaction(tryBody = "database.setTransactionSuccessful()\n            val result = block()\n" +
            "            return result")
        refusedFor(source, "block() is not called in the try before setTransactionSuccessful()")
    }

    @Test
    fun `a call after the success mark is refused`() {
        val source = transaction(tryBody = "val result = block()\n            database.setTransactionSuccessful()\n" +
            "            audit()\n            return result")
        refusedFor(source, "a call follows setTransactionSuccessful()")
    }

    @Test
    fun `a success mark outside the try is refused`() {
        val source = transaction(
            tryBody = "val result = block()\n            return result",
            afterFinally = "\n        database.setTransactionSuccessful()",
        )
        refusedFor(source, "setTransactionSuccessful() is outside the try")
    }

    @Test
    fun `a catch that commits a failed block is refused`() {
        val source = transaction(
            afterTry = " catch (failure: Throwable) {\n            database.setTransactionSuccessful()\n" +
                "            throw failure\n        }",
        )
        refusedFor(source, "transaction has a catch")
    }

    @Test
    fun `a catch that only rethrows is refused too, because the rule says there is none`() {
        val source = transaction(afterTry = " catch (failure: Throwable) {\n            throw failure\n        }")
        refusedFor(source, "transaction has a catch")
    }

    @Test
    fun `a transaction with no finally is refused`() {
        val source = transaction(
            finallyHead = "\n        // no finally\n        ",
            finallyBody = "",
            tryBody = "val result = block()\n            database.setTransactionSuccessful()\n" +
                "            database.endTransaction()\n            return result",
        )
        refusedFor(source, "is not followed by a finally")
    }

    @Test
    fun `an end that is never run is refused`() {
        refusedFor(transaction(finallyBody = ""), "holds 0 endTransaction() calls")
    }

    @Test
    fun `an end in the try instead of the finally is refused`() {
        val source = transaction(
            tryBody = "val result = block()\n            database.setTransactionSuccessful()\n" +
                "            database.endTransaction()\n            return result",
            finallyBody = "",
        )
        refusedFor(source, "endTransaction() is outside the finally")
    }

    @Test
    fun `an end after the finally instead of inside it is refused`() {
        val source = transaction(finallyBody = "", afterFinally = "\n        database.endTransaction()")
        refusedFor(source, "endTransaction() is outside the finally")
    }

    @Test
    fun `calls on different receivers are refused`() {
        val source = transaction(finallyBody = "other.endTransaction()")
        refusedFor(source, "expected one receiver for all three")
    }

    @Test
    fun `a transaction with two try blocks is refused`() {
        val source = transaction(afterFinally = "\n        try {\n        } finally {\n        }")
        refusedFor(source, "holds 2 try blocks")
    }

    @Test
    fun `an adapter without a transaction method is refused`() {
        val refused = assertThrows(AdapterShapeError::class.java) {
            AdapterTextRules.checkTransaction("internal class A {\n    fun other() {\n    }\n}\n")
        }
        assertTrue(refused.message.orEmpty(), refused.message.orEmpty().contains("expected exactly one method named"))
    }

    @Test
    fun `the words try, catch and the calls in a comment are not code`() {
        val source = transaction(
            afterBegin = "\n        // catch (e: Exception) { database.setTransactionSuccessful() }\n" +
                "        /* try { database.endTransaction() } */\n",
        )
        AdapterTextRules.checkTransaction(source)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun refusedFor(source: String, fragment: String) {
        val refused = assertThrows(AdapterShapeError::class.java) { AdapterTextRules.checkTransaction(source) }
        val message = refused.message.orEmpty()
        assertTrue("the refusal should say `$fragment`, it said: $message", message.contains(fragment))
    }

    /** An adapter whose `transaction` is written as the real one is, with one piece replaced at a time. */
    private fun transaction(
        begin: String = "database.beginTransaction()",
        afterBegin: String = "\n        ",
        tryHead: String = "try {",
        tryBody: String = "val result = block()\n            database.setTransactionSuccessful()\n" +
            "            return result",
        success: String? = null,
        afterTry: String = "",
        finallyHead: String = " finally {",
        finallyBody: String = "database.endTransaction()",
        afterFinally: String = "",
    ): String {
        val mark = success ?: "database.setTransactionSuccessful()"
        val body = tryBody.replace("database.setTransactionSuccessful()", mark)
        return "internal class Adapter {\n" +
            "    override fun <T> transaction(block: () -> T): T {\n" +
            "        val database = helper.writableDatabase\n" +
            "        $begin$afterBegin$tryHead\n" +
            "            $body\n" +
            "        }$afterTry$finallyHead\n" +
            "            $finallyBody\n" +
            "        }$afterFinally\n" +
            "    }\n" +
            "}\n"
    }
}
