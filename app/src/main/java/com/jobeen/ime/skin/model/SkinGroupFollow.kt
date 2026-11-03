package com.jobeen.ime.skin.model

/**
 * 成套模型按组跟随／脱离（第 5 批 §4，只上开关，不上全套差量合并与哨兵语义）：
 *
 * 对方成套模型是“整套默认 + 每页差量 + 按组跟随/脱离”；Jime
 * 不替换类型模型、不新造合并引擎，只给已有覆盖机制的组加开关：
 *
 * - 已有覆盖机制的组（本批开关覆盖范围）：
 *   - STYLE：类型样式集 [SkinSnapshot.typeStyles]（keyboards.<类型>.styleSet
 *     的解析结果；无条目 = 沿用包级样式，即跟随）。
 *   - AREA：类型区域集 [SkinSnapshot.typeAreas]（keyboards.<类型>.areas
 *     的解析结果；无条目 = 沿用包级区域，即跟随）。
 * - 没有覆盖机制的组（先记为未覆盖，见 [UNCOVERED_GROUPS]，
 *   不得为开关新造一套合并引擎）：geometry、background、fonts、
 *   toolbar、menus、floating、bindings——Jime 现有模型里这些
 *   面只有包级值，没有“类型差量”可去掉／可写入，开关无从附着。
 *
 * 语义（与对方 follow/detach 同款，落在 Jime 快照上）：
 * - 跟随 = 去掉该类型的该组差量（快照拷贝中删条目），生效值
 *   回到包级；脱离 = 把当前生效值（即包级值，因跟随态下生效
 *   值就是包级值）写进该组差量，此后包级变化不再影响该类型。
 * - 对已脱离的类型再脱离：幂等，写回的仍是包级值（不叠加；
 *   若该类型已有差量且调用方想“以当前生效值重写”，生效值在
 *   脱离态下就是差量本身，故本函数在已有差量时原样返回——
 *   调用方不得用本函数把差量“压平”为包级值，那是编辑器的
 *   显式操作，不属跟随开关）。
 *
 * 角色样式命名对照（只作命名对照，Jime 口径不变）：
 * 回车 = accent，单字符／空格 = normal，其余 = function；Jime
 * 回车键仍跟皮肤走、Accent 兜底，不因本对照改解析。
 */
object SkinGroupFollow {

    /** 本批开关覆盖的组：只对应已有覆盖机制。 */
    enum class Group { STYLE, AREA }

    /** 没有覆盖机制的组：未覆盖，不为其造合并引擎。 */
    val UNCOVERED_GROUPS: List<String> = listOf(
        "geometry", "background", "fonts", "toolbar", "menus", "floating", "bindings",
    )

    /** 该类型该组当前是否跟随（无差量条目 = 跟随）。 */
    fun isFollowing(snapshot: SkinSnapshot, type: String, group: Group): Boolean = when (group) {
        Group.STYLE -> snapshot.typeStyles[type] == null
        Group.AREA -> snapshot.typeAreas[type] == null
    }

    /** 跟随：去掉该组差量，返回新快照（原快照不动）。 */
    fun follow(snapshot: SkinSnapshot, type: String, group: Group): SkinSnapshot = when (group) {
        Group.STYLE -> snapshot.copy(typeStyles = snapshot.typeStyles - type)
        Group.AREA -> snapshot.copy(typeAreas = snapshot.typeAreas - type)
    }

    /**
     * 脱离：把当前生效值写进该组差量。跟随态下生效值 = 包级值，
     * 故写入包级样式／区域；已脱离时原样返回（幂等，不压平差量）。
     */
    fun detach(snapshot: SkinSnapshot, type: String, group: Group): SkinSnapshot = when (group) {
        Group.STYLE -> {
            if (snapshot.typeStyles.containsKey(type)) {
                snapshot
            } else {
                snapshot.copy(
                    typeStyles = snapshot.typeStyles + (type to TypeStyleSet(
                        colorScheme = snapshot.colorScheme,
                        darkColorScheme = snapshot.darkColorScheme,
                        keyStyles = snapshot.keyStyles,
                        keyGeometries = snapshot.keyGeometries,
                    )),
                )
            }
        }
        Group.AREA -> {
            if (snapshot.typeAreas.containsKey(type)) {
                snapshot
            } else {
                snapshot.copy(
                    typeAreas = snapshot.typeAreas + (type to TypeAreaSet(
                        toolbarHeight = snapshot.toolbarHeight,
                        preeditHeight = snapshot.preeditHeight,
                        horizontalCandidatesStyle = snapshot.horizontalCandidatesStyle,
                        verticalCandidatesStyle = snapshot.verticalCandidatesStyle,
                        horizontalCandidateStyle = snapshot.horizontalCandidateStyle,
                        verticalCandidateStyle = snapshot.verticalCandidateStyle,
                    )),
                )
            }
        }
    }

    /** 角色样式命名对照：回车 accent、单字符／空格 normal、其余 function。 */
    fun styleRoleName(isEnter: Boolean, isSpace: Boolean, labelLength: Int): String = when {
        isEnter -> "accent"
        isSpace || labelLength <= 1 -> "normal"
        else -> "function"
    }
}
