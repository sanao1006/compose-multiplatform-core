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

package androidx.compose.foundation.text

import kotlin.text.CharCategory.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.EditProcessor
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Sets and adjusts the cursor offset when the TextField is focused in a Cupertino style.
 * Cursor final offset depends on position of the cursor and content of the textfield.
 *
 * See **determineCursorDesiredPosition()** for more details.
 * @param position The position of the cursor in the TextField.
 * @param textLayoutResult The TextLayoutResultProxy object that contains the layout information of the TextField text.
 * @param editProcessor The EditProcessor object that manages the editing operations of the TextField.
 * @param offsetMapping The OffsetMapping object that maps the transformed offset to the original offset.
 * @param showContextMenu The function that displays the context menu at the given rectangular area.
 * @param onValueChange The function that is called when the TextField value changes.
 */
internal fun TextFieldDelegate.Companion.cupertinoSetCursorOffsetFocused(
    position: Offset,
    textLayoutResult: TextLayoutResultProxy,
    editProcessor: EditProcessor,
    offsetMapping: OffsetMapping,
    showContextMenu: (Rect) -> Unit,
    onValueChange: (TextFieldValue) -> Unit
) {
    val offset =
        offsetMapping.transformedToOriginal(textLayoutResult.getOffsetForPosition(position))
    val currentValue = editProcessor.toTextFieldValue()
    val currentText = currentValue.text

    val caretDesiredPosition = determineCursorDesiredPosition(
        offset,
        currentValue,
        textLayoutResult,
        currentText
    )

    if (caretDesiredPosition == offset) {
        showContextMenu(textLayoutResult.value.getCursorRect(offset))
    }

    onValueChange(
        editProcessor.toTextFieldValue().copy(selection = TextRange(caretDesiredPosition))
    )
}

/**
 * Determines the desired cursor position based on the given parameters.
 *
 * The rules for determining position of the caret are as follows:
 * - When you make a single tap on a word, the caret moves to the end of this word.
 * - If there’s a punctuation mark after the word, the caret is between the word and the punctuation mark.
 * - If you tap on a whitespace, the caret is placed before the word. Same for many whitespaces in a row. (and punctuation marks)
 * - If there’s a punctuation mark before the word, the caret is between the punctuation mark and the word.
 * - When you make a single tap on the first letter of the word, the caret is placed before this word.
 * - If you tap on the left edge of the TextField, the caret is placed before the first word on this line. The same is for the right edge.
 * - If you tap at the caret, that is placed in the middle of the word, it will jump to the end of the word.
 * @param offset The current offset position.
 * @param currentValue The current TextFieldValue.
 * @param textLayoutResult The TextLayoutResultProxy representing the layout of the text.
 * @param currentText The current text in the TextField.
 * @return The desired cursor position after evaluating the given parameters.
 */
internal fun determineCursorDesiredPosition(
    offset: Int,
    currentValue: TextFieldValue,
    textLayoutResult: TextLayoutResultProxy,
    currentText: String
): Int {
    val caretOffsetPosition: Int
    if (isCaretTapped(offset, currentValue.selection.start)) {
        caretOffsetPosition = offset
    } else if (textLayoutResult.isLeftEdgeTapped(offset)) {
        val lineNumber = textLayoutResult.value.getLineForOffset(offset)
        caretOffsetPosition = textLayoutResult.value.getLineStart(lineNumber)
    } else if (textLayoutResult.isRightEdgeTapped(offset)) {
        val lineNumber = textLayoutResult.value.getLineForOffset(offset)
        caretOffsetPosition = textLayoutResult.value.getLineEnd(lineNumber)
    } else if (isPunctuationOrSpaceBeforeWordTapped(offset, currentText)) {
        val nextWordBoundary = findNextWordBoundary(offset, currentText, textLayoutResult)
        caretOffsetPosition = nextWordBoundary.start
    } else if (textLayoutResult.isFirstLetterOfWordTapped(offset)) {
        val wordBoundary = textLayoutResult.value.getWordBoundary(offset)
        caretOffsetPosition = wordBoundary.start
    } else {
        val wordBoundary = textLayoutResult.value.getWordBoundary(offset)
        caretOffsetPosition = wordBoundary.end
    }
    return caretOffsetPosition
}

private fun isPunctuationOrSpaceBeforeWordTapped(
    caretOffset: Int,
    currentText: String
): Boolean {
    /* From TextLayoutResultProxy.value.getWordBoundary(caretOffset) it is clear
    * that for whitespace or punctuation mark method will return empty range.
    * Thus, if empty range was returned, and the caretOffset greater than start and less than last index of text
    * then offset should be somewhere between words.
    * */
    val char = currentText[caretOffset]
    val isGreaterThanFirst = caretOffset >= 0
    val isLessThanLast = caretOffset <= currentText.lastIndex
    return char.isPunctuationOrWhitespace() && isGreaterThanFirst && isLessThanLast
}

private fun TextLayoutResultProxy.isFirstLetterOfWordTapped(caretOffset: Int): Boolean {
    return value.getWordBoundary(caretOffset).start == caretOffset
}

private fun isCaretTapped(
    caretOffset: Int,
    previousCaretOffset: Int
): Boolean {
    return previousCaretOffset == caretOffset
}

private fun TextLayoutResultProxy.isLeftEdgeTapped(caretOffset: Int): Boolean {
    val lineNumber = value.getLineForOffset(caretOffset)
    val lineStartOffset = value.getLineStart(lineNumber)
    return lineStartOffset == caretOffset
}

private fun TextLayoutResultProxy.isRightEdgeTapped(caretOffset: Int): Boolean {
    val lineNumber = value.getLineForOffset(caretOffset)
    val lineEndOffset = value.getLineEnd(lineNumber)
    return lineEndOffset == caretOffset
}

/**
 * Recursively finds the next word boundary position starting from the given caret offset in the current text.
 * The main difference between this and **getWordBoundary()** in *Paragraph.kt* is that
 * this method finds next word boundary in case if caret positioned in between two words
 * (so caret is pointing at punctuation or whitespace), whereas **getWordBoundary()** will return
 * empty TextRange with the beginning on caret position
 *
 * @param caretOffset the offset of the caret position
 * @param currentText the current text
 * @param textLayoutResult the TextLayoutResultProxy object that provides text layout information
 * @return the TextRange representing the next word boundary position
 */
private tailrec fun findNextWordBoundary(
    caretOffset: Int,
    currentText: String,
    textLayoutResult: TextLayoutResultProxy
): TextRange {
    val wordRange = textLayoutResult.value.getWordBoundary(caretOffset)
    val currentChar = currentText[caretOffset]
    return if (!currentChar.isPunctuationOrWhitespace()) {
        wordRange
    } else if (caretOffset >= currentText.lastIndex) {
        TextRange(currentText.lastIndex)
    } else {
        findNextWordBoundary(caretOffset + 1, currentText, textLayoutResult)
    }
}

private fun Char.isPunctuationOrWhitespace(): Boolean {
    return this.isPunctuation() || this.isWhitespace()
}

private fun Char.isPunctuation(): Boolean {
    val punctuationSet = setOf(
        DASH_PUNCTUATION,
        START_PUNCTUATION,
        END_PUNCTUATION,
        CONNECTOR_PUNCTUATION,
        OTHER_PUNCTUATION
    )
    punctuationSet.forEach {
        if (it.contains(this)) {
            return true
        }
    }
    return false
}