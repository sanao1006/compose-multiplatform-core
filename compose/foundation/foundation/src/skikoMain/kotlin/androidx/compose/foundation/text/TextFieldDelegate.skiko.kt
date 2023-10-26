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
 * Sets the cursor offset when the TextField is focused in a Cupertino style.
 * Cursor offset depends on position of the cursor and content of the textfield.
 *
 * See **determineCursorDesiredPosition()** for more details.
 *
 * @param position The position of the cursor.
 * @param textLayoutResult The result of the text layout.
 * @param editProcessor The edit processor.
 * @param offsetMapping The offset mapping.
 * @param showContextMenu A callback function to show the context menu at a given rect.
 * @param onValueChange A callback function to handle the change in TextField value.
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

    val caretOffsetPosition: Int
    when (caretDesiredPosition) {
        TextCursorDesiredPosition.Same -> {
            /* Menu with context actions should be opened by tap on the caret,
         * caret should remain on the same position.
         */
            showContextMenu(textLayoutResult.value.getCursorRect(offset))
            caretOffsetPosition = offset
        }
        TextCursorDesiredPosition.BeforeWord -> {
            val wordBoundary = textLayoutResult.value.getWordBoundary(offset)
            caretOffsetPosition = wordBoundary.start
        }
        TextCursorDesiredPosition.AfterWord -> {
            val wordBoundary = textLayoutResult.value.getWordBoundary(offset)
            caretOffsetPosition = wordBoundary.end
        }
        TextCursorDesiredPosition.BeforeNextWord -> {
            val nextWordBoundary = findNextWordBoundary(offset, currentText, textLayoutResult)
            caretOffsetPosition = nextWordBoundary.start
        }
        TextCursorDesiredPosition.LineStart -> {
            val lineNumber = textLayoutResult.value.getLineForOffset(offset)
            caretOffsetPosition = textLayoutResult.value.getLineStart(lineNumber)
        }
        TextCursorDesiredPosition.LineEnd -> {
            val lineNumber = textLayoutResult.value.getLineForOffset(offset)
            caretOffsetPosition = textLayoutResult.value.getLineEnd(lineNumber)
        }
    }

    onValueChange(editProcessor.toTextFieldValue().copy(selection = TextRange(caretOffsetPosition)))
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
 *
 * @param offset The offset of the tapped position.
 * @param currentValue The current TextFieldValue.
 * @param textLayoutResult The TextLayoutResultProxy object representing the text layout.
 * @param currentText The current text string.
 *
 * @return The CaretMovementPosition indicating the desired caret movement position.
 */
internal fun determineCursorDesiredPosition(
    offset: Int,
    currentValue: TextFieldValue,
    textLayoutResult: TextLayoutResultProxy,
    currentText: String
): TextCursorDesiredPosition {
    if (isCaretTapped(offset, currentValue.selection.start)) {
        return TextCursorDesiredPosition.Same
    } else if (textLayoutResult.isLeftEdgeTapped(offset)) {
        return TextCursorDesiredPosition.LineStart
    } else if (textLayoutResult.isRightEdgeTapped(offset)) {
        return TextCursorDesiredPosition.LineEnd
    } else if (isPunctuationOrSpaceBeforeWordTapped(offset, currentText)) {
        return TextCursorDesiredPosition.BeforeNextWord
    } else if (textLayoutResult.isFirstLetterOfWordTapped(offset)) {
        return TextCursorDesiredPosition.BeforeWord
    }
    return TextCursorDesiredPosition.AfterWord
}

/**
 * Enum class representing the desired position of the text cursor.
 *
 * The possible positions are:
 * - Same: The cursor remains at the same position.
 * - BeforeWord: The cursor is placed before the current word.
 * - AfterWord: The cursor is placed after the current word.
 * - BeforeNextWord: The cursor is placed before the next word.
 * - LineStart: The cursor is placed at the start of the current line.
 * - LineEnd: The cursor is placed at the end of the current line.
 *
 * The rules for determining can be found in **determineCursorDesiredPosition()** description
 */
internal enum class TextCursorDesiredPosition {
    Same,
    BeforeWord,
    AfterWord,
    BeforeNextWord,
    LineStart,
    LineEnd
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
 *
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