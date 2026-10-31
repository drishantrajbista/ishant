package dev.buddy

import kotlin.math.abs

/** How opaque an onboarding page is while swiping: 1 on screen, fading to 0 one page away (a cross-fade). */
fun pageFade(distanceInPages: Float): Float = (1f - abs(distanceInPages)).coerceIn(0f, 1f)

/** The small pinned title fades in as the large title scrolls away (between [from] and [to] pixels of scroll). */
fun titleAlpha(scroll: Int, from: Int = 40, to: Int = 120): Float = ((scroll - from).toFloat() / (to - from)).coerceIn(0f, 1f)

/** Index of [value] in [options] for a segmented control; 0 if it isn't there. */
fun <T> segmentIndex(options: List<T>, value: T): Int = options.indexOf(value).coerceAtLeast(0)

/** "Saved: …1234" or "Not set": shows a key without ever showing the key. */
fun keyStatus(key: String): String = if (key.isBlank()) "Not set" else "Saved: ${maskKey(key)}"
