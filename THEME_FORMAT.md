# 键盘主题数据格式说明

本文件说明键盘自定义主题的存储格式（`themes.json`，可读）与二维码分享格式（紧凑短 key）的所有字段含义。

---

## 1. 用户主题存储文件 `themes.json`

- 位置：`App.themesDir/themes.json`（外置目录 `files/themes/`）
- 格式：JSON 数组，最多 2 个主题（超出部分读取时截断）
- 采用**长字段名 + ARGB 十六进制颜色（`#AARRGGBB`）**，并美化排版，便于阅读与手写
- 读取时兼容三种历史格式：`#AARRGGBB` hex → 长 key Int → 紧凑短 key

```json
[
  {
    "id": "custom_seafoam",
    "name": "海盐蓝",
    "colors": { "...": "#FF1A3F4E" }
  }
]
```

### 1.1 顶层字段

| 字段 | 类型 | 必填 | 含义 |
|------|------|------|------|
| `id` | string | 是 | 主题唯一标识，用于存储、切换与二维码识别 |
| `name` | string | 是 | 主题显示名称 |
| `colors` | object | 是 | 主题配色，见下 |

### 1.2 `colors` 配色字段

颜色值格式：`#AARRGGBB`（`AA`=透明度，`RR/GG/BB`=红绿蓝）；也接受 `#RRGGBB`（自动按不透明处理）。

| 字段 | 默认值 | 含义 |
|------|--------|------|
| `keyBackground` | - | 主按键背景色 |
| `keyPressed` | - | 主按键按下状态背景色 |
| `keyBorderStroke` | - | 主按键边框描边色 |
| `specialKeyBackground` | - | 功能键（特殊键，如符号/shift 等）背景色 |
| `specialKeyPressed` | - | 功能键按下状态背景色 |
| `specialKeyBorderStroke` | - | 功能键边框描边色 |
| `accentKeyBackground` | - | 强调键（回车、空格等）背景色 |
| `accentKeyPressed` | - | 强调键按下状态背景色 |
| `accentKeyBorderStroke` | - | 强调键边框描边色 |
| `keyText` | - | 主按键文字颜色 |
| `specialKeyText` | - | 功能键文字颜色 |
| `accentKeyText` | - | 强调键文字颜色 |
| `altText` | - | 次级文字颜色（候选注释、标签、次要说明等） |
| `background` | - | 键盘整体背景色 |
| `surfaceStyle` | `"Raised"` | 表面风格：`"Raised"`（凸起）/ `"Flat"`（扁平） |
| `panel` | 必填 | 候选/工具栏面板配色，见下方 `panel` |
| `pinner` | 必填 | 顶部拼音悬浮条配色，见下方 `pinner` |
| `toastBackground` | `specialKeyBackground` | Toast 提示背景色（可省略） |
| `toastText` | `keyText` | Toast 提示文字色（可省略） |

### 1.3 `colors.panel`（候选/工具栏面板）

| 字段 | 含义 |
|------|------|
| `background` | 面板背景色 |
| `toolbarText` | 工具栏文字颜色 |
| `toolbarActived` | 工具栏激活/高亮项颜色 |
| `toolbarIcon` | 工具栏图标颜色 |
| `candidateBackground` | 候选词区域背景色 |
| `candidateText` | 候选词文字颜色 |
| `candidateIndex` | 候选词序号颜色 |
| `candidateDivider` | 候选词之间分隔线颜色 |
| `toolbarPressed` | 工具栏按下状态颜色 |

### 1.4 `colors.pinner`（拼音悬浮条）

| 字段 | 含义 |
|------|------|
| `background` | 悬浮条背景色 |
| `textColor` | 主文字颜色 |
| `secondaryTextColor` | 次级/辅助文字颜色 |

---

## 2. 二维码分享格式（紧凑短 key）

分享时把**单个主题**编码为：

```
IMEKBTHEME:{紧凑JSON}
```

- 前缀：固定 `IMEKBTHEME:`（区分本应用主题）
- 内容：`CompactTheme` 紧凑 JSON，字段名压缩到最短，便于二维码容纳
- 颜色以 **ARGB 数字（Int）** 表示
- 编码时指定 UTF-8 字符集，保证中文名称正确

### 2.1 紧凑字段映射

| 紧凑 key | 对应可读字段 | 含义 |
|----------|--------------|------|
| `i` | `id` | 主题唯一标识 |
| `n` | `name` | 主题名称 |
| `c` | `colors` | 配色对象（见下） |

`c` 内字段：

| 紧凑 key | 对应可读字段 |
|----------|--------------|
| `kb` | `keyBackground` |
| `kp` | `keyPressed` |
| `kbs` | `keyBorderStroke` |
| `skb` | `specialKeyBackground` |
| `skp` | `specialKeyPressed` |
| `skbs` | `specialKeyBorderStroke` |
| `akb` | `accentKeyBackground` |
| `akp` | `accentKeyPressed` |
| `akbs` | `accentKeyBorderStroke` |
| `kt` | `keyText` |
| `skt` | `specialKeyText` |
| `akt` | `accentKeyText` |
| `alt` | `altText` |
| `bg` | `background` |
| `ss` | `surfaceStyle` |
| `p` | `panel` |
| `pn` | `pinner` |
| `tb` | `toastBackground` |
| `tt` | `toastText` |

`p`（panel）内：

| 紧凑 key | 对应字段 |
|----------|----------|
| `bg` | `background` |
| `tt` | `toolbarText` |
| `ta` | `toolbarActived` |
| `ti` | `toolbarIcon` |
| `cb` | `candidateBackground` |
| `ct` | `candidateText` |
| `ci` | `candidateIndex` |
| `cd` | `candidateDivider` |
| `tp` | `toolbarPressed` |

`pn`（pinner）内：

| 紧凑 key | 对应字段 |
|----------|----------|
| `bg` | `background` |
| `tc` | `textColor` |
| `stc` | `secondaryTextColor` |

---

## 3. 导入规则

- 扫码解析出完整主题后：
  - 存在 **相同 `id`** 的主题 → 原位覆盖并直接导入
  - 用户主题**未满（< 2）** → 直接追加导入
  - 已满（= 2）→ 弹窗选择要覆盖的槽位
- 导入成功后写入 `themes.json`（可读长 key 格式）、刷新列表并以该主题作为当前主题

## 4. 导出规则

- 分享前弹出“选择要导出的主题”弹层，**仅列出用户主题**（最多 2 个）
- 选中后按紧凑短 key 格式生成二维码，UTF-8 编码中文名称