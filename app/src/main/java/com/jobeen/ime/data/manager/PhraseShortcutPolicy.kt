// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

/**
 * 常用语输入码候选注入的纯规则（WO52 6.1，04 §2.2）：
 * 完整匹配输入码时，在候选前显示常用语，仅点击上屏（不参与 Rime 组字）。
 *
 * - [exactMatches]：组字码与输入码逐字相等（小写归一）才命中；
 *   同码多条按置顶／手动序／新建序全返回，由注入侧按序拼到候选前。
 * - 输入码本身须合法（2–10 位 a–z，可留空不命中）：非法码不查库、
 *   不注入，热路径上先以此短路（`isExactMatchCandidate`）。
 * 本对象不碰数据库与引擎：查库走 [PhraseManager.getByShortcut]，
 * 拼候选走 [com.jobeen.ime.engine.RimeEngine.restoreCandidates]。
 */
object PhraseShortcutPolicy {

    /** 组字码有没有可能是一条输入码的完整匹配（非法码直接短路，不查库）。 */
    fun isExactMatchCandidate(code: String): Boolean {
        val normalized = code.lowercase()
        return PhraseManager.validateShortcut(normalized) == null && normalized.isNotEmpty()
    }

    /** 从已取到的常用语里挑出与组字码完整匹配的那些（小写归一，等值比较）。 */
    fun exactMatches(code: String, phrases: List<PhraseManager.Phrase>): List<PhraseManager.Phrase> {
        val normalized = code.lowercase()
        if (!isExactMatchCandidate(normalized)) return emptyList()
        return phrases.filter { it.shortcut.lowercase() == normalized }
    }
}
