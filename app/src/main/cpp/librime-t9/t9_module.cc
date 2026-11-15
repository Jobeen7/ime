// SPDX-License-Identifier: Apache-2.0
//
// T9 会话访问器插件（WO52 1.5，自研）。
//
// 以 librime 插件结构注册（RIME_REGISTER_MODULE，与 lua/octagram/
// predict 同形；rime_engine.cc 的 requireModules() 调
// rime_require_module_t9() 拉入本对象，保证模块初始化器随 .so
// 加载执行）。本插件不向引擎管线注册 processor/translator/
// filter：数字→拼音的转换归属仍在既有 Kotlin BehaviorHost
// （Jobeen 日用的 T9 主链路，行为已验证）；插件只提供
// t9PinyinList/t9SetPinyin 两个会话访问器（自研实现），
// 供 Kotlin 侧取列表/定拼音。组字串不再另设访问器：
// 快照 composition.preedit 即组字串（C-L-18，t9Preedit 已删）。

#include <rime_api.h>

namespace {

void rime_t9_initialize() {
    // 无引擎组件可注册：访问器是会话外的纯函数（t9_session.h），
    // 不拦截按键、不改管线。模块注册本身即插件的落点——日后若需
    // 引擎内组件（如方案注入的 t9 processor），在此处注册。
}

void rime_t9_finalize() {}

}  // namespace

RIME_REGISTER_MODULE(t9)
