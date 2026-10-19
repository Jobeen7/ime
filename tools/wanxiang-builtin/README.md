# 内置万象方案（app/src/main/assets/resource.zip）的可复现来源

`app/src/main/assets/resource.zip` 不入版本库（.gitignore 排除），本目录保存其中**经 Jime 适配的四个文件**，使内置方案可审计、可复现：

- `default.yaml`
- `wanxiang_t9.schema.yaml`
- `wanxiang_lite.schema.yaml`
- `wanxiang_english.schema.yaml`

## 重打包步骤

1. 取官方 [rime-wanxiang](https://github.com/amzxyz/rime-wanxiang) 的 **lite** 发布 zip（当前内置对应版本见包内 `shared/version.txt`，现为 18.1.5）。
2. 解压后，用本目录四个文件覆盖 `shared/` 下的同名文件。
3. 将整个目录树（`shared/`、`model/` 等）重新打包为 zip，放到 `app/src/main/assets/resource.zip`。
4. 打包后 App 启动时按 MD5 变化自动解压覆盖旧方案；发版验包须逐项核对下述三处适配都在成品包内。

## 相对官方 lite 包的四处适配（缺一即回归）

1. **方案清单启用三方案**：`default.yaml` 的 `schema_list` 启用 `wanxiang_t9`、`wanxiang_lite`、`wanxiang_english`（官方只启用 lite，其余为注释行）。librime 按此清单给出可用方案，缺谁谁从方案列表消失。
2. **方案 `schema:` 段布局注入**：三个方案的 `schema:` 段带 Jime 定制字段，App 与定制 librime 据此决定键盘布局与候选类型——t9：`layout: "T9"`、`punctuation: "FullWidth"`、`kind: "PinYin"`、`candidateKind: "T9PinYin"`；lite：`"Qwerty"`/`"FullWidth"`/`"PinYin"`/`"PinYin"`；english：`"Qwerty"`/`"HalfWidth"`/`"English"`（无 candidateKind）。缺失时九宫格会退化成 26 键全键盘。
4. **顶层 `options:` 段**：三个方案各有一段 Jime 自有声明（`traditional_chinese_enabled`→`s2t`、`emoji_enabled`→`emoji`、`ascii_mode_enabled`→`ascii_mode`），OptionsApplier 靠它把功能面板的繁体/表情/英文输入三项偏好刷进引擎，缺失时三项开关与引擎脱节。**三段长度相同但 `lock` 标记逐方案不同，必须各自按本目录文件原样使用**：t9/lite 的 ascii 段为 `lock: true`（英文直输由键盘中英键控制）、trad 段为 `lock: false`；english 相反（ascii 段 `lock: false`，面板「英文输入」靠它在英文方案里开关 ascii 直输；trad 段 `lock: true`）。

3. **T9 单字母缩写拼写恢复**（2026-10-20 新增）：`wanxiang_t9.schema.yaml` 的 `speller/algebra` 里，官方 18.1.6 将两行注释掉——`- abbrev/^([a-z]).*/$1/` 与 `- abbrev/^([A-Z]).*/$1/`（呈现为 `#- abbrev/...`）。Jime T9 左列点选按 fcitx5 约定回写「首字母 + 分隔符」（如 `g'`），引擎认这个形态全靠这两条缩写拼写；缺失时点选后该段无候选、组字错乱（每段缀原始数字）。Jime 内置包将这两行恢复为启用状态（与官方 18.1.2 时一致，其余内容不动）。注意副作用口径：恢复后纯数字输入的音节切分恢复旧版行为（如 `45` 的组字由 `gj` 变为 `g j` 两段），属旧版官方行为，非新引入。换新版官方包时先 diff 这两行：若官方已恢复启用，本适配自动失效、可从清单移除；仍是注释状态则继续恢复。

## 历史教训

2026-10-19 换官方 18.1.5 包时曾因整替未保留上述适配连出三轮回归预览版（方案消失、九宫格变全键盘、面板开关失效）。此后换包一律：先查 rime-wanxiang 有无新版，再以官方 lite zip 为基底覆盖本目录四文件重打包，最后逐项验包。
