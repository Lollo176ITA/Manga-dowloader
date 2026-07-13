package com.lorenzo.mangadownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBlocksTest {

    @Test
    fun reconcile_emptyStored_returnsDefaultOrder() {
        assertEquals(DEFAULT_HOME_BLOCK_ORDER, reconcileHomeBlocks(emptyList()))
    }

    @Test
    fun reconcile_appendsMissingBlocksPreservingStoredOrder() {
        val stored = listOf(HomeBlock.DISCOVER, HomeBlock.RESUME)
        val result = reconcileHomeBlocks(stored)
        // Gli storati mantengono l'ordine, i mancanti (nell'ordine di default) vanno in coda.
        assertEquals(
            listOf(
                HomeBlock.DISCOVER,
                HomeBlock.RESUME,
                HomeBlock.FAVORITE_UPDATES,
                HomeBlock.RECENT_FAVORITES,
                HomeBlock.STATS,
                HomeBlock.HISTORY,
                HomeBlock.TO_FINISH,
            ),
            result,
        )
    }

    @Test
    fun reconcile_oldStoredOrder_appendsNewBlocksAtEnd() {
        val stored = listOf(
            HomeBlock.DISCOVER, HomeBlock.RESUME,
            HomeBlock.FAVORITE_UPDATES, HomeBlock.RECENT_FAVORITES,
        )
        assertEquals(
            stored + listOf(HomeBlock.STATS, HomeBlock.HISTORY, HomeBlock.TO_FINISH),
            reconcileHomeBlocks(stored),
        )
    }

    @Test
    fun reconcile_dropsDuplicates() {
        val stored = listOf(HomeBlock.RESUME, HomeBlock.RESUME)
        assertEquals(DEFAULT_HOME_BLOCK_ORDER, reconcileHomeBlocks(stored))
    }

    @Test
    fun move_up_swapsWithVisibleNeighbor() {
        val order = listOf(HomeBlock.RESUME, HomeBlock.FAVORITE_UPDATES, HomeBlock.RECENT_FAVORITES, HomeBlock.DISCOVER)
        assertEquals(
            listOf(HomeBlock.FAVORITE_UPDATES, HomeBlock.RESUME, HomeBlock.RECENT_FAVORITES, HomeBlock.DISCOVER),
            moveHomeBlockInOrder(order, HomeBlock.FAVORITE_UPDATES, up = true) { false },
        )
    }

    @Test
    fun move_atEdge_isNoOp() {
        val order = DEFAULT_HOME_BLOCK_ORDER
        assertEquals(order, moveHomeBlockInOrder(order, HomeBlock.RESUME, up = true) { false })
        assertEquals(order, moveHomeBlockInOrder(order, HomeBlock.TO_FINISH, up = false) { false })
    }

    @Test
    fun move_skipsBlockHiddenFromView() {
        // DISCOVER è nascosto dalla vista (es. controllo parentale): spostando FAVORITE_UPDATES su,
        // scambia col vicino VISIBILE (RESUME), non col DISCOVER interposto — niente tap morto.
        val order = listOf(HomeBlock.RESUME, HomeBlock.DISCOVER, HomeBlock.FAVORITE_UPDATES, HomeBlock.RECENT_FAVORITES)
        assertEquals(
            listOf(HomeBlock.FAVORITE_UPDATES, HomeBlock.DISCOVER, HomeBlock.RESUME, HomeBlock.RECENT_FAVORITES),
            moveHomeBlockInOrder(order, HomeBlock.FAVORITE_UPDATES, up = true) { it == HomeBlock.DISCOVER },
        )
    }

    @Test
    fun greeting_variesByHour() {
        assertEquals("Buongiorno", homeGreeting(9))
        assertEquals("Buon pomeriggio", homeGreeting(15))
        assertEquals("Buonasera", homeGreeting(21))
        assertEquals("Buonasera", homeGreeting(3))
    }
}
