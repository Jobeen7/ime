// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 脚本种子（WO52 8.1）：4 条内置脚本，逻辑逐字取自元书包内
 * `assets/seeds/scripts.json`（由 iOS 源码生成），与 9.1 的 AI 种子
 * 同一做法（种子落库用 INSERT OR IGNORE，不盖用户改过的数据）。
 *
 * 两处改动（刻意，非遗漏）：
 * - 种子头注释里的第三方输入法产品名按仓库规矩改掉（发版前扫
 *   第三方名，见 AGENTS.md），只改品牌字样、不改逻辑；
 * - “JavaScriptCore 没有 btoa”改为“QuickJS 核心没有 btoa”——
 *   Jime 的引擎是 QuickJS 核心，同样没有 btoa／atob／TextEncoder，
 *   手写编解码的理由不变。
 * 其余代码（含输入优先级 `$searchText → $selectedText →
 * $pasteboardContent` 与示例输入／输出）一字不改。
 *
 * 落库：[insertAll] 用绑定参数逐行插；迁移 16→17 与新库 onCreate
 * 共用同一函数。uuid 固定（INSERT OR IGNORE 的幂等键）。
 */
object ScriptSeeds {

    /** 默认新建脚本内容（元书 ch4.c 原文）。 */
    const val DEFAULT_NEW_SCRIPT_CONTENT =
        "async function output() { return [\"Hello, \" + \$searchText] }"

    const val UNICODE_QUERY_UUID = "00000000-0000-4000-8000-000000000301"
    const val BASE64_ENCODE_UUID = "00000000-0000-4000-8000-000000000302"
    const val BASE64_DECODE_UUID = "00000000-0000-4000-8000-000000000303"
    const val ROLL_UUID = "00000000-0000-4000-8000-000000000304"

    // 内容常量在下面（raw 字符串含 ${'$'} 模板，非编译期常量，只能是
    // val 不能是 const val）；scripts 用 lazy，避开 val 的前向引用限制。
    val scripts: List<ScriptItem> by lazy {
        listOf(
            ScriptItem(
                uuid = UNICODE_QUERY_UUID,
            iconName = "text.magnifyingglass",
            iconType = 0,
            name = "Unicode 查询",
            content = UNICODE_QUERY_CONTENT,
            isFavorite = false,
            sort = 0,
            date = 0L,
            allowPasteboard = true,
            resultInsertState = false,
            closeWindowState = false,
            downloadURL = null,
        ),
        ScriptItem(
            uuid = BASE64_ENCODE_UUID,
            iconName = "chevron.left.forwardslash.chevron.right",
            iconType = 0,
            name = "Base64 编码",
            content = BASE64_ENCODE_CONTENT,
            isFavorite = false,
            sort = 1,
            date = 0L,
            allowPasteboard = true,
            resultInsertState = false,
            closeWindowState = false,
            downloadURL = null,
        ),
        ScriptItem(
            uuid = BASE64_DECODE_UUID,
            iconName = "curlybraces",
            iconType = 0,
            name = "Base64 解码",
            content = BASE64_DECODE_CONTENT,
            isFavorite = false,
            sort = 2,
            date = 0L,
            allowPasteboard = true,
            resultInsertState = false,
            closeWindowState = false,
            downloadURL = null,
        ),
        ScriptItem(
            uuid = ROLL_UUID,
            iconName = "die.face.5",
            iconType = 0,
            name = "Roll",
            content = ROLL_CONTENT,
            isFavorite = false,
            sort = 3,
            date = 0L,
            allowPasteboard = true,
            resultInsertState = false,
            closeWindowState = false,
            downloadURL = null,
        ),
    ) }

    /**
     * 种子落库（迁移与新库 onCreate 共用）：已存在同 uuid 行时跳过
     * （INSERT OR IGNORE），可重复调、不会把用户改过的种子再盖回去。
     * date 落库时取当前时间（种子对象里的 0 只是占位）。
     */
    fun insertAll(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        for (s in scripts) {
            db.execSQL(
                "INSERT OR IGNORE INTO `ScriptItem` (`uuid`,`iconName`,`iconType`," +
                    "`name`,`content`,`isFavorite`,`sort`,`date`,`allowPasteboard`," +
                    "`resultInsertState`,`closeWindowState`,`downloadURL`) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    s.uuid, s.iconName, s.iconType, s.name, s.content,
                    if (s.isFavorite) 1 else 0, s.sort, now,
                    if (s.allowPasteboard) 1 else 0,
                    if (s.resultInsertState) 1 else 0,
                    if (s.closeWindowState) 1 else 0, s.downloadURL,
                ),
            )
        }
    }

    val UNICODE_QUERY_CONTENT = """/**
 * Jime · Unicode 查询
 *
 * 输入来源（按优先级）：脚本输入框 ${'$'}searchText → 键盘选中文本 ${'$'}selectedText → 剪贴板 ${'$'}pasteboardContent
 *
 * 输入：中文
 * 输出：U+4E2D U+6587
 */

function hex(cp) {
  var text = cp.toString(16).toUpperCase();
  var width = cp > 0xffff ? 5 : 4;
  while (text.length < width) {
    text = "0" + text;
  }
  return text;
}

function pickInput() {
  var sources = [
    typeof ${'$'}searchText === "string" ? ${'$'}searchText : "",
    typeof ${'$'}selectedText === "string" ? ${'$'}selectedText : "",
    typeof ${'$'}pasteboardContent === "string" ? ${'$'}pasteboardContent : "",
  ];
  for (var i = 0; i < sources.length; i++) {
    if (sources[i] && sources[i].trim().length > 0) {
      return sources[i].trim();
    }
  }
  return "";
}

async function output() {
  var input = pickInput();
  if (!input) {
    return ["请输入要查询的文字，或选中文本后再运行"];
  }

  // Array.from 按码位切分，补充平面字符的代理对不会被拆散
  var codePoints = Array.from(input).map(function (ch) {
    return "U+" + hex(ch.codePointAt(0));
  });

  ${'$'}log("查询 " + codePoints.length + " 个码位：" + input);

  return [codePoints.join(" ")];
}"""

    val BASE64_ENCODE_CONTENT = """/**
 * Jime · Base64 编码
 *
 * 输入来源（按优先级）：脚本输入框 ${'$'}searchText → 键盘选中文本 ${'$'}selectedText → 剪贴板 ${'$'}pasteboardContent
 *
 * 输入：中文 abc
 * 输出：5Lit5paHIGFiYw==
 */

var BASE64_CHARS =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

// QuickJS 核心没有 btoa / TextEncoder，按码位手写 UTF-8 编码
function utf8Bytes(text) {
  var bytes = [];
  // Array.from 按码位切分，emoji 等平面外字符的代理对不会被拆散
  var points = Array.from(text);
  for (var i = 0; i < points.length; i++) {
    var cp = points[i].codePointAt(0);
    if (cp < 0x80) {
      bytes.push(cp);
    } else if (cp < 0x800) {
      bytes.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
    } else if (cp < 0x10000) {
      bytes.push(
        0xe0 | (cp >> 12),
        0x80 | ((cp >> 6) & 0x3f),
        0x80 | (cp & 0x3f)
      );
    } else {
      bytes.push(
        0xf0 | (cp >> 18),
        0x80 | ((cp >> 12) & 0x3f),
        0x80 | ((cp >> 6) & 0x3f),
        0x80 | (cp & 0x3f)
      );
    }
  }
  return bytes;
}

function base64(bytes) {
  var out = "";
  for (var i = 0; i < bytes.length; i += 3) {
    var b0 = bytes[i];
    var b1 = bytes[i + 1];
    var b2 = bytes[i + 2];
    var hasB1 = b1 !== undefined;
    var hasB2 = b2 !== undefined;

    out += BASE64_CHARS[b0 >> 2];
    out += BASE64_CHARS[((b0 & 0x03) << 4) | (hasB1 ? b1 >> 4 : 0)];
    out += hasB1
      ? BASE64_CHARS[((b1 & 0x0f) << 2) | (hasB2 ? b2 >> 6 : 0)]
      : "=";
    out += hasB2 ? BASE64_CHARS[b2 & 0x3f] : "=";
  }
  return out;
}

function pickInput() {
  var sources = [
    typeof ${'$'}searchText === "string" ? ${'$'}searchText : "",
    typeof ${'$'}selectedText === "string" ? ${'$'}selectedText : "",
    typeof ${'$'}pasteboardContent === "string" ? ${'$'}pasteboardContent : "",
  ];
  for (var i = 0; i < sources.length; i++) {
    // 只用 trim 判断是否为空，编码时保留原文首尾空白
    if (sources[i] && sources[i].trim().length > 0) {
      return sources[i];
    }
  }
  return "";
}

async function output() {
  var input = pickInput();
  if (!input) {
    return ["请输入要编码的文字，或选中文本后再运行"];
  }

  var bytes = utf8Bytes(input);
  ${'$'}log("编码 " + bytes.length + " 字节：" + input);

  return [base64(bytes)];
}"""

    val BASE64_DECODE_CONTENT = """/**
 * Jime · Base64 解码
 *
 * 输入来源（按优先级）：脚本输入框 ${'$'}searchText → 键盘选中文本 ${'$'}selectedText → 剪贴板 ${'$'}pasteboardContent
 *
 * 输入：5Lit5paHIGFiYw==
 * 输出：中文 abc
 */

var BASE64_CHARS =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

// QuickJS 核心没有 atob / TextDecoder，按字符查表手写解码
function base64Bytes(text) {
  // 兼容 URL Safe 变体，并忽略换行、空格与结尾填充
  var normalized = text
    .replace(/-/g, "+")
    .replace(/_/g, "/")
    .replace(/[\s=]/g, "");
  var bytes = [];
  var buffer = 0;
  var bits = 0;
  for (var i = 0; i < normalized.length; i++) {
    var value = BASE64_CHARS.indexOf(normalized[i]);
    if (value < 0) {
      return null;
    }
    buffer = (buffer << 6) | value;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((buffer >> bits) & 0xff);
    }
  }
  return bytes;
}

// 按 UTF-8 规则还原码位，String.fromCodePoint 负责补回代理对
function utf8Text(bytes) {
  var out = "";
  var i = 0;
  while (i < bytes.length) {
    var b0 = bytes[i++];
    var cp;
    var extra;
    if (b0 < 0x80) {
      cp = b0;
      extra = 0;
    } else if ((b0 & 0xe0) === 0xc0) {
      cp = b0 & 0x1f;
      extra = 1;
    } else if ((b0 & 0xf0) === 0xe0) {
      cp = b0 & 0x0f;
      extra = 2;
    } else if ((b0 & 0xf8) === 0xf0) {
      cp = b0 & 0x07;
      extra = 3;
    } else {
      return null;
    }

    for (var j = 0; j < extra; j++) {
      var bn = bytes[i++];
      if (bn === undefined || (bn & 0xc0) !== 0x80) {
        return null;
      }
      cp = (cp << 6) | (bn & 0x3f);
    }
    out += String.fromCodePoint(cp);
  }
  return out;
}

function pickInput() {
  var sources = [
    typeof ${'$'}searchText === "string" ? ${'$'}searchText : "",
    typeof ${'$'}selectedText === "string" ? ${'$'}selectedText : "",
    typeof ${'$'}pasteboardContent === "string" ? ${'$'}pasteboardContent : "",
  ];
  for (var i = 0; i < sources.length; i++) {
    if (sources[i] && sources[i].trim().length > 0) {
      return sources[i].trim();
    }
  }
  return "";
}

async function output() {
  var input = pickInput();
  if (!input) {
    return ["请输入要解码的 Base64 文本，或选中文本后再运行"];
  }

  var bytes = base64Bytes(input);
  if (bytes === null) {
    return ["不是合法的 Base64 文本"];
  }

  var text = utf8Text(bytes);
  if (text === null) {
    return ["解码结果不是合法的 UTF-8 文本"];
  }

  ${'$'}log("解码 " + bytes.length + " 字节");

  return [text];
}"""

    val ROLL_CONTENT = """/**
 * Jime · Roll
 *
 * 无需输入，每次运行返回一个 1 - 100 的随机数
 *
 * 输出：42
 */

async function output() {
  var value = Math.floor(Math.random() * 100) + 1;

  ${'$'}log("roll: " + value);

  return [String(value)];
}"""
}
