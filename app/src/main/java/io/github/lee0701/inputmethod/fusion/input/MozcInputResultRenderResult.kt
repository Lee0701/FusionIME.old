package io.github.lee0701.inputmethod.fusion.input

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.view.inputmethod.InputConnection
import io.github.lee0701.inputmethod.fusion.FusionIME
import io.github.lee0701.inputmethod.fusion.OldIME
import org.mozc.android.inputmethod.japanese.KeycodeConverter
import org.mozc.android.inputmethod.japanese.MozcLog
import org.mozc.android.inputmethod.japanese.MozcUtil
import org.mozc.android.inputmethod.japanese.model.SelectionTracker
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidates
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands

class MozcInputResultRenderResult(
    private val command: ProtoCommands.Command,
    private val keyEvent: KeycodeConverter.KeyEventInterface,
    private val selectionTracker: SelectionTracker,
): InputResult {

    override fun process(ime: FusionIME) {
        val inputConnection = ime.inputConnection ?: return

        val output = command.output

        inputConnection.beginBatchEdit()
        try {
            maybeDeleteSurroundingText(output, inputConnection)
            maybeCommitText(output, inputConnection)
            setComposingText(inputConnection)
            maybeSetSelection(output, inputConnection)
            selectionTracker.onRender(
                if (output.hasDeletionRange()) output.deletionRange else null,
                if (output.hasResult()) output.result.value else null,
                if (output.hasPreedit()) output.preedit else null
            )
        } finally {
            inputConnection.endBatchEdit()
        }
    }

    private fun setComposingText(inputConnection: InputConnection) {
        val output = command.output
        if (!output.hasPreedit()) {
            // If preedit field is empty, we should clear composing text in the InputConnection
            // because Mozc server asks us to do so.
            // But there is special situation in Android.
            // On onWindowShown, SWITCH_INPUT_MODE command is sent as a step of initialization.
            // In this case we reach here with empty preedit.
            // As described above we should clear the composing text but if we do so
            // texts in selection range (e.g., URL in OmniBox) is always cleared.
            // To avoid from this issue, we don't clear the composing text if the input
            // is SWITCH_INPUT_MODE.
            val input = command.input
            if (input.type != ProtoCommands.Input.CommandType.SEND_COMMAND
                || input.command.type != ProtoCommands.SessionCommand.CommandType.SWITCH_INPUT_MODE
            ) {
                if (!inputConnection.setComposingText("", 0)) {
                    MozcLog.e("Failed to set composing text.")
                }
            }
            return
        }

        // Builds preedit expression.
        val preedit = output.preedit
        val builder = SpannableStringBuilder()
        for (segment in preedit.segmentList) {
            builder.append(segment.value)
        }

        // Set underline for all the preedit text.
        builder.setSpan(
            OldIME.SPAN_UNDERLINE,
            0,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Draw cursor if in composition mode.
        val cursor = preedit.cursor
        val spanFlags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING
        if (output.hasAllCandidateWords()
            && output.allCandidateWords.hasCategory()
            && output.allCandidateWords.category == ProtoCandidates.Category.CONVERSION
        ) {
            var offsetInString = 0
            for (segment in preedit.segmentList) {
                val length = segment.value.length
                builder.setSpan(
                    if (segment.hasAnnotation() && segment.annotation == ProtoCommands.Preedit.Segment.Annotation.HIGHLIGHT) OldIME.SPAN_CONVERT_HIGHLIGHT else CharacterStyle::class.java.cast(
                        BackgroundColorSpan(OldIME.CONVERT_NORMAL_COLOR)
                    ),
                    offsetInString, offsetInString + length, spanFlags
                )
                offsetInString += length
            }
        } else {
            // We cannot show system cursor inside preedit here.
            // Instead we change text style before the preedit's cursor.
            val cursorOffsetInString = builder.toString().offsetByCodePoints(0, cursor)
            if (cursor != builder.length) {
                builder.setSpan(
                    OldIME.SPAN_PARTIAL_SUGGESTION_COLOR,
                    cursorOffsetInString,
                    builder.length,
                    spanFlags
                )
            }
            if (cursor > 0) {
                builder.setSpan(
                    OldIME.SPAN_BEFORE_CURSOR,
                    0,
                    cursorOffsetInString,
                    spanFlags
                )
            }
        }

        // System cursor will be moved to the tail of preedit.
        // It triggers onUpdateSelection again.
        val cursorPosition = if (cursor > 0) MozcUtil.CURSOR_POSITION_TAIL else 0
        if (!inputConnection.setComposingText(builder, cursorPosition)) {
            MozcLog.e("Failed to set composing text.")
        }
    }

    private fun maybeDeleteSurroundingText(output: ProtoCommands.Output, inputConnection: InputConnection) {
        if (!output.hasDeletionRange()) {
            return
        }
        val range = output.deletionRange
        val leftRange = -range.offset
        val rightRange = range.length - leftRange
        if (leftRange < 0 || rightRange < 0) {
            // If the range does not include the current position, do nothing
            // because Android's API does not expect such situation.
            MozcLog.w("Deletion range has unsupported parameters: $range")
            return
        }
        if (!inputConnection.deleteSurroundingText(leftRange, rightRange)) {
            MozcLog.e("Failed to delete surrounding text.")
        }
    }

    private fun maybeCommitText(output: ProtoCommands.Output, inputConnection: InputConnection) {
        if (!output.hasResult()) {
            return
        }
        val outputText = output.result.value
        if (outputText == "") {
            // Do nothing for an empty result string.
            return
        }
        var position = MozcUtil.CURSOR_POSITION_TAIL
        if (output.result.hasCursorOffset()) {
            if (output.result.cursorOffset
                == -outputText.codePointCount(0, outputText.length)
            ) {
                position = MozcUtil.CURSOR_POSITION_HEAD
            } else {
                MozcLog.e("Unsupported position: " + output.result.toString())
            }
        }
        if (!inputConnection.commitText(outputText, position)) {
            MozcLog.e("Failed to commit text.")
        }
    }

    private fun maybeSetSelection(output: ProtoCommands.Output, inputConnection: InputConnection) {
        if (!output.hasPreedit()) {
            return
        }
        val preedit = output.preedit
        val cursor = preedit.cursor
        if (cursor == 0 || cursor == getPreeditLength(preedit)) {
            // The cursor is at the beginning/ending of the preedit. So we don't anything about the
            // caret setting.
            return
        }
        var caretPosition: Int = selectionTracker.preeditStartPosition
        if (output.hasDeletionRange()) {
            caretPosition += output.deletionRange.offset
        }
        if (output.hasResult()) {
            caretPosition += output.result.value.length
        }
        if (output.hasPreedit()) {
            caretPosition += output.preedit.cursor
        }
        if (!inputConnection.setSelection(caretPosition, caretPosition)) {
            MozcLog.e("Failed to set selection.")
        }
    }

    private fun getPreeditLength(preedit: ProtoCommands.Preedit): Int {
        var result = 0
        for (i in 0 until preedit.segmentCount) {
            result += preedit.getSegment(i).valueLength
        }
        return result
    }

}