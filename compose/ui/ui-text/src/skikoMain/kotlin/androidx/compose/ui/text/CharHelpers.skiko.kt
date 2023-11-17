/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.text

import kotlin.jvm.JvmInline
import org.jetbrains.skia.BreakIterator

internal actual fun String.findPrecedingBreak(index: Int): Int {
    val it = BreakIterator.makeCharacterInstance()
    it.setText(this)
    return it.preceding(index)
}

internal actual fun String.findFollowingBreak(index: Int): Int {
    val it = BreakIterator.makeCharacterInstance()
    it.setText(this)
    return it.following(index)
}

/**
 * See https://www.unicode.org/reports/tr9/
 */
@JvmInline
internal value class StrongDirectionType private constructor(val value: Int) {
    companion object {
        val None = StrongDirectionType(0)
        val Ltr = StrongDirectionType(1)
        val Rtl = StrongDirectionType(2)
    }
}

// TODO Remove once it's available in common stdlib https://youtrack.jetbrains.com/issue/KT-23251
internal typealias CodePoint = Int

/**
 * Converts a surrogate pair to a unicode code point.
 */
private fun Char.Companion.toCodePoint(high: Char, low: Char): CodePoint =
    (((high - MIN_HIGH_SURROGATE) shl 10) or (low - MIN_LOW_SURROGATE)) + 0x10000

/**
 * The minimum value of a supplementary code point, `\u0x10000`.
 */
private const val MIN_SUPPLEMENTARY_CODE_POINT: Int = 0x10000

/**
 * The maximum value of a Unicode code point.
 */
private const val MAX_CODE_POINT = 0X10FFFF

internal fun CodePoint.charCount(): Int = if (this >= MIN_SUPPLEMENTARY_CODE_POINT) 2 else 1

/**
 * Checks if the codepoint specified is a supplementary codepoint or not.
 */
internal fun CodePoint.isSupplementaryCodePoint(): Boolean =
    this in MIN_SUPPLEMENTARY_CODE_POINT..MAX_CODE_POINT

internal expect fun CodePoint.strongDirectionType(): StrongDirectionType
internal expect fun CodePoint.isNeutralDirection(): Boolean

/**
 * Determine direction based on the first strong directional character.
 * Only considers the characters outside isolate pairs.
 */
internal fun String.firstStrongDirectionType(): StrongDirectionType {
    for (codePoint in codePointsOutsideDirectionalIsolate) {
        return when (val strongDirectionType = codePoint.strongDirectionType()) {
            StrongDirectionType.None -> continue
            else -> strongDirectionType
        }
    }
    return StrongDirectionType.None
}

/**
 * U+2066 LEFT-TO-RIGHT ISOLATE (LRI)
 * U+2067 RIGHT-TO-LEFT ISOLATE (RLI)
 * U+2068 FIRST STRONG ISOLATE (FSI)
 */
private val PUSH_DIRECTIONAL_ISOLATE_RANGE: IntRange = 0x2066..0x2068

/**
 * U+2069 POP DIRECTIONAL ISOLATE (PDI)
 */
private const val POP_DIRECTIONAL_ISOLATE_CODE_POINT: Int = 0x2069

private val String.codePointsOutsideDirectionalIsolate get() = sequence {
    var openIsolateCount = 0
    for (codePoint in codePoints) {
        if (codePoint in PUSH_DIRECTIONAL_ISOLATE_RANGE) {
            openIsolateCount++
        } else if (codePoint == POP_DIRECTIONAL_ISOLATE_CODE_POINT) {
            if (openIsolateCount > 0) {
                openIsolateCount--
            }
        } else if (openIsolateCount == 0) {
            yield(codePoint)
        }
    }
}

internal val String.codePoints get() = sequence {
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        yield(codePoint)
        index += codePoint.charCount()
    }
}

/**
 * Returns the character (Unicode code point) at the specified index.
 */
internal fun String.codePointAt(index: Int): CodePoint {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < this.length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return Char.toCodePoint(high, low)
        }
    }
    return high.code
}

/**
 * Returns the character (Unicode code point) before the specified index.
 */
internal fun String.codePointBefore(index: Int): CodePoint {
    val low = this[index]
    if (low.isLowSurrogate() && index - 1 >= 0) {
        val high = this[index - 1]
        if (high.isHighSurrogate()) {
            return Char.toCodePoint(high, low)
        }
    }
    return low.code
}

/**
 * Finds the offset of the next non-whitespace symbols subsequence (word) in the given text
 * starting from the specified caret offset.
 *
 * @param offset The offset where to start looking for the next word.
 * @param currentText The current text in which to search for the next word.
 * @return The offset of the next non-whitespace symbols subsequence (word), or the end of the string
 *         if no such word is found.
 */
@ExperimentalTextApi
fun findNextNonWhitespaceSymbolsSubsequenceStartOffset(
    offset: Int,
    currentText: String
): Int {
    /* Assume that next non whitespaces symbols subsequence (word) is when current char is whitespace and next character is not.
     * Emoji (compound incl.) should be treated as a new word.
     */
    val charIterator = BreakIterator.makeCharacterInstance() // wordInstance doesn't consider symbols sequence as word
    charIterator.setText(currentText)

    var currentOffset: Int
    var nextOffset = charIterator.next()
    while (nextOffset < offset) { nextOffset = charIterator.next() }
    currentOffset = nextOffset

    while (nextOffset != BreakIterator.DONE) {
        nextOffset = charIterator.next()
        if (currentText.codePointAt(currentOffset).isWhitespace() && !currentText.codePointAt(nextOffset).isWhitespace()) {
            return currentOffset
        } else {
            currentOffset = nextOffset
        }
    }
    return currentOffset
}

/**
 * Determines whether the character at the specified offset in the string is a whitespace Unicode character.
 *
 * @param offset The index of the character to check.
 * @return `true` if the character at the specified offset is a whitespace character, `false` otherwise.
 */
@ExperimentalTextApi
fun String.isWhitespace(offset: Int): Boolean {
    return this.codePointAt(offset).isWhitespace()
}

/**
 * Checks if the character at the specified offset in the string is a punctuation Unicode character.
 *
 * @param offset The offset of the character to check.
 * @return true if the character at the specified offset is a punctuation character, false otherwise.
 */
@ExperimentalTextApi
fun String.isPunctuation(offset: Int): Boolean {
    return this.codePointAt(offset).isPunctuation()
}

@ExperimentalTextApi
fun String.halfSymbolsOffset(): Int {
    val symbolsCount = this.codePoints.count()
    val charIterator = BreakIterator.makeCharacterInstance()
    charIterator.setText(this)
    var currentOffset = 0
    for (i in 0..symbolsCount / 2) {
        currentOffset = charIterator.next()
    }
    return currentOffset
}

private fun CodePoint.isWhitespace(): Boolean {
    // TODO: Extend this behavior when (if) Unicode will have compound whitespace characters.
    if (this.charCount() != 1) { return false }
    return this.toChar().isWhitespace()
}

private fun CodePoint.isPunctuation(): Boolean {
    // TODO: Extend this behavior when (if) Unicode will have compound punctuation characters.
    if (this.charCount() != 1) { return false }
    val punctuationSet = setOf(
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION
    )
    punctuationSet.forEach {
        if (it.contains(this.toChar())) {
            return true
        }
    }
    return false
}