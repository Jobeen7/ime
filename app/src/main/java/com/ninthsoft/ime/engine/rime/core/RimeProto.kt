// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

data class CommitProto(
    val text: String?,
)

data class CandidateProto(
    val text: String,
    val comment: String,
    val label: String,
)

data class SyllableProto(
    val rawInput: String = "",
    val spelling: String = "",
    val text: String = "",
    val textSyllableStart: Int = -1,
    val textSyllableEnd: Int = -1,
)

data class CompositionProto(
    val length: Int = 0,
    val cursorPos: Int = 0,
    val selStart: Int = 0,
    val selEnd: Int = 0,
    val preedit: String? = null,
    val commitTextPreview: String? = null,
    val syllables: Array<SyllableProto> = arrayOf(),
) {
    internal constructor(text: String) : this(
        length = text.length,
        cursorPos = text.length,
        selStart = text.length,
        selEnd = text.length,
        preedit = text,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CompositionProto

        if (length != other.length) return false
        if (cursorPos != other.cursorPos) return false
        if (selStart != other.selStart) return false
        if (selEnd != other.selEnd) return false
        if (preedit != other.preedit) return false
        if (commitTextPreview != other.commitTextPreview) return false
        if (!syllables.contentEquals(other.syllables)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = length
        result = 31 * result + cursorPos
        result = 31 * result + selStart
        result = 31 * result + selEnd
        result = 31 * result + (preedit?.hashCode() ?: 0)
        result = 31 * result + (commitTextPreview?.hashCode() ?: 0)
        result = 31 * result + syllables.contentHashCode()
        return result
    }
}

data class MenuProto(
    val pageSize: Int = 0,
    val pageNumber: Int = 0,
    val isLastPage: Boolean = false,
    val highlightedCandidateIndex: Int = 0,
    val candidates: Array<CandidateProto> = arrayOf(),
    val selectKeys: String? = null,
    val selectLabels: Array<String> = arrayOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MenuProto) return false
        return pageSize == other.pageSize &&
                pageNumber == other.pageNumber &&
                isLastPage == other.isLastPage &&
                highlightedCandidateIndex == other.highlightedCandidateIndex &&
                candidates.contentEquals(other.candidates) &&
                selectKeys == other.selectKeys &&
                selectLabels.contentEquals(other.selectLabels)
    }

    override fun hashCode(): Int {
        var result = pageSize
        result = 31 * result + pageNumber
        result = 31 * result + isLastPage.hashCode()
        result = 31 * result + highlightedCandidateIndex
        result = 31 * result + candidates.contentHashCode()
        result = 31 * result + (selectKeys?.hashCode() ?: 0)
        result = 31 * result + selectLabels.contentHashCode()
        return result
    }
}

data class ContextProto(
    val composition: CompositionProto = CompositionProto(),
    val menu: MenuProto = MenuProto(),
    val input: String = "",
    val caretPos: Int = 0,
)

data class StatusProto(
    val schemaId: String = "",
    val schemaName: String = "",
    val isDisabled: Boolean = true,
    val isComposing: Boolean = false,
    val isAsciiMode: Boolean = true,
    val isFullShape: Boolean = false,
    val isSimplified: Boolean = false,
    val isTraditional: Boolean = false,
    val isAsciiPunct: Boolean = true,
)
