package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The take mark of the take owner: a late hook takes the mark when it fires, and settledIf clears the
 * take only while that same take is still held. Each test says in its KDoc what would break it.
 */
internal class TakeOwnerMarkTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val take = FakeHomeTake(committer, log)
    private val owner = TakeOwner()
    private val home = OwnedTakePort(take, owner, TakeOwnerKind.HOME)
    private val tile = OwnedTakePort(take, owner, TakeOwnerKind.TILE)

    /** Fails if a Home begin that lands after the tile mark was taken is cleared by that mark. */
    @Test
    fun `a home begin after a tile mark keeps the home holder`() {
        tile.begin()
        val mark = owner.mark()
        home.begin()
        owner.settledIf(mark)
        assertEquals("app: a Home take begun after the mark must keep the Home as holder", TakeOwnerKind.HOME, owner.current())
    }

    /** Fails if a tile begin that lands after the Home mark was taken is cleared by that mark. */
    @Test
    fun `a tile begin after a home mark keeps the tile holder`() {
        home.begin()
        val mark = owner.mark()
        tile.begin()
        owner.settledIf(mark)
        assertEquals("app: a tile take begun after the mark must keep the tile as holder", TakeOwnerKind.TILE, owner.current())
    }

    /** Fails if settledIf with the mark of the held take leaves that take held, so a late hook would not clear it. */
    @Test
    fun `the same take is cleared by its own mark`() {
        tile.begin()
        val mark = owner.mark()
        owner.settledIf(mark)
        assertNull("app: settledIf with the mark of the held take must clear it", owner.current())
    }

    /** Fails if settledIf with no mark clears the take that is held, instead of leaving it alone. */
    @Test
    fun `a null mark clears nothing`() {
        home.begin()
        owner.settledIf(null)
        assertEquals("app: a null mark must leave the held take", TakeOwnerKind.HOME, owner.current())
    }

    /** Fails if settledIf with an old mark sets a holder back, or throws, after the user settled the take. */
    @Test
    fun `a user settle leaves an old mark with no holder`() {
        home.begin()
        val mark = owner.mark()
        home.finish()
        owner.settledIf(mark)
        assertNull("app: an old mark after a settle must leave no holder", owner.current())
    }

    /** Fails if a bare settle stops clearing the held take, which would make the port settles conditional. */
    @Test
    fun `a bare settle still clears the held take`() {
        home.begin()
        owner.settled()
        assertNull("app: a bare settle must clear the held take", owner.current())
    }

    /** Fails if mark gives a mark with no take held, or gives none once a take is recording. */
    @Test
    fun `the mark is null with no take and set after a recording begin`() {
        assertNull("app: with no take held there is no mark", owner.mark())
        home.begin()
        assertNotNull("app: a recording begin must give the held take a mark", owner.mark())
    }

    /** Fails if two begins give the same mark object, so a late hook could not tell one take from the next. */
    @Test
    fun `each begin gives a distinct mark`() {
        home.begin()
        val first = owner.mark()
        home.begin()
        val second = owner.mark()
        assertNotSame("app: two begins in a row must give two different marks", first, second)
    }
}
