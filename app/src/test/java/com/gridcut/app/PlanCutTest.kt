package com.gridcut.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanCutTest {
    @Test
    fun squareGridIsSquare() {
        val plan = planCut(1080, 1080, 3, CutMode.Grid)
        assertEquals(3, plan.rows)
        assertEquals(9, plan.tiles.size)
        assertEquals(1, plan.tiles.last().order)
        assertEquals(9, plan.tiles.first().order)
        plan.tiles.forEach { assertEquals(360, it.width); assertEquals(360, it.height) }
    }

    @Test
    fun tallGridIsAlwaysColumnsByColumns() {
        val plan = planCut(900, 1200, 3, CutMode.Grid)
        assertEquals(3, plan.rows)
        assertEquals(9, plan.tiles.size)
        assertEquals(150, plan.top)
        assertEquals(1050, plan.bottom)
        plan.tiles.forEach { assertEquals(300, it.width); assertEquals(300, it.height) }
    }

    @Test
    fun wideGridCropsSidesEvenly() {
        val plan = planCut(3000, 500, 2, CutMode.Grid)
        assertEquals(2, plan.rows)
        assertEquals(1250, plan.left)
        assertEquals(1750, plan.right)
        plan.tiles.forEach { assertEquals(250, it.width); assertEquals(250, it.height) }
    }

    @Test
    fun carouselUsesFullHeight() {
        val plan = planCut(1001, 700, 4, CutMode.Carousel)
        assertEquals(1, plan.rows)
        assertEquals(listOf(1, 2, 3, 4), plan.tiles.map { it.order })
        assertEquals(1001, plan.tiles.sumOf { it.width })
        plan.tiles.forEach { assertEquals(700, it.height) }
    }

    @Test
    fun fillGridUsesWholeImage() {
        val plan = planCut(1500, 600, 3, CutMode.Fill)
        assertEquals(3, plan.rows)
        assertEquals(0, plan.left)
        assertEquals(0, plan.top)
        assertEquals(1500, plan.right)
        assertEquals(600, plan.bottom)
        plan.tiles.forEach { assertEquals(500, it.width); assertEquals(200, it.height) }
        assertEquals(1, plan.tiles.last().order)
    }
}
