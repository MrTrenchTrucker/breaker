package dev.breaker.dictation.ui.render

/*
 * The pure part of a spread row: how many separators it holds.
 *
 * A spread row puts one empty view with weight one between each two consecutive
 * children, so the free width sits between them, the first child at the start and
 * the last at the end. A row with fewer than two children has nothing between them.
 */

/**
 * The number of separators a spread row of [children] children holds: one less than the
 * number of children, and none for no child and for one child.
 */
internal fun spreadSeparatorCount(children: Int): Int = if (children <= 1) 0 else children - 1
