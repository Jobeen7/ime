// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 脚本种子（WO52 8.1）：4 条内置脚本，均为 Jime 自写实现，与
 * 9.1 的 AI 种子同一落库做法（INSERT OR IGNORE，不盖用户改过
 * 的数据）。
 *
 * 运行环境是 QuickJS 核心，没有浏览器那套 btoa／atob 与
 * TextEncoder／TextDecoder，所以 Base64 编解码与 UTF-8 互转都
 * 用手写实现；输入一律按“脚本输入框 $searchText → 键盘选中
 * 文本 $selectedText → 剪贴板 $pasteboardContent”的顺序取，先
 * 找到非空的用哪个。
 *
 * 落库：[insertAll] 用绑定参数逐行插；迁移 16→17 与新库 onCreate
 * 共用同一函数。uuid 固定（INSERT OR IGNORE 的幂等键）。
 */
object ScriptSeeds {

    /** 默认新建脚本内容（Jime 自写：向输入框文字打招呼的示例）。 */
    const val DEFAULT_NEW_SCRIPT_CONTENT =
        "async function output() { return [\"你好，\" + \$searchText] }"

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
            iconName = "unicode",
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
            iconName = "code",
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
            iconName = "base64",
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
            iconName = "roll",
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
 * Unicode 查询
 *
 * 从哪里取要查的文字：先找到哪个用哪个——脚本输入框 ${'$'}searchText、
 * 键盘选中的文本 ${'$'}selectedText、剪贴板 ${'$'}pasteboardContent。
 *
 * 例子：输入“中文”，输出 U+4E2D U+6587。
 */

// 取第一个非空输入；全局变量可能未注入，typeof 守住再读。
function firstText() {
  var sources = [
    typeof ${'$'}searchText === "string" ? ${'$'}searchText : "",
    typeof ${'$'}selectedText === "string" ? ${'$'}selectedText : "",
    typeof ${'$'}pasteboardContent === "string" ? ${'$'}pasteboardContent : "",
  ];
  for (var i = 0; i < sources.length; i++) {
    if (sources[i].trim() !== "") {
      return sources[i].trim();
    }
  }
  return "";
}

// 单个字符 → U+XXXX（平面外字符 5 位，其余 4 位，不足补 0）。
function codeLabel(ch) {
  var cp = ch.codePointAt(0);
  var digits = cp.toString(16).toUpperCase();
  var width = cp > 0xffff ? 5 : 4;
  return "U+" + digits.padStart(width, "0");
}

async function output() {
  var text = firstText();
  if (text === "") {
    return ["请输入要查询的文字，或选中文本后再运行"];
  }

  // for...of 按码位走，平面外字符的代理对不会被拆成两半。
  var labels = [];
  for (var ch of text) {
    labels.push(codeLabel(ch));
  }

  ${'$'}log("查询 " + labels.length + " 个码位：" + text);

  return [labels.join(" ")];
}"""

    val BASE64_ENCODE_CONTENT = """/**
 * Base64 编码
 *
 * 从哪里取要编的文字：先找到哪个用哪个——脚本输入框 ${'$'}searchText、
 * 键盘选中的文本 ${'$'}selectedText、剪贴板 ${'$'}pasteboardContent。
 * 首尾空白只用来判空，编码时原文照留。
 *
 * 例子：输入“中文 abc”，输出 5Lit5paHIGFiYw==。
 */

var ALPHABET =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

// 文字 → UTF-8 字节：按码位展开，逐个码位拼字节。
function toUtf8(text) {
  var bytes = [];
  for (var ch of text) {
    var cp = ch.codePointAt(0);
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

// 字节 → Base64：逐字节喂进位缓冲，满 6 位吐一个字符，末尾补 =。
function toBase64(bytes) {
  var out = "";
  var acc = 0;
  var nbits = 0;
  for (var i = 0; i < bytes.length; i++) {
    acc = (acc << 8) | bytes[i];
    nbits += 8;
    while (nbits >= 6) {
      nbits -= 6;
      out += ALPHABET[(acc >> nbits) & 0x3f];
    }
  }
  if (nbits > 0) {
    out += ALPHABET[(acc << (6 - nbits)) & 0x3f];
  }
  while (out.length % 4 !== 0) {
    out += "=";
  }
  return out;
}

function firstText() {
  var sources = [
    typeof ${'$'}searchText === "string" ? ${'$'}searchText : "",
    typeof ${'$'}selectedText === "string" ? ${'$'}selectedText : "",
    typeof ${'$'}pasteboardContent === "string" ? ${'$'}pasteboardContent : "",
  ];
  for (var i = 0; i < sources.length; i++) {
    if (sources[i].trim() !== "") {
      return sources[i];
    }
  }
  return "";
}

async function output() {
  var input = firstText();
  if (input === "") {
    return ["请输入要编码的文字，或选中文本后再运行"];
  }

  var bytes = toUtf8(input);
  ${'$'}log("编码 " + bytes.length + " 字节：" + input);

  return [toBase64(bytes)];
}"""

    val BASE64_DECODE_CONTENT = """/**
 * Base64 解码
 *
 * 从哪里取要解的文本：先找到哪个用哪个——脚本输入框 ${'$'}searchText、
 * 键盘选中的文本 ${'$'}selectedText、剪贴板 ${'$'}pasteboardContent。
 * URL Safe 写法（- _）照收，换行、空格与结尾的 = 直接忽略。
 *
 * 例子：输入 5Lit5paHIGFiYw==，输出“中文 abc”。
 */

var ALPHABET =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

var LOOKUP = null;

function lookup() {
  if (LOOKUP !== null) {
    return LOOKUP;
  }
  LOOKUP = {};
  for (var i = 0; i < ALPHABET.length; i++) {
    LOOKUP[ALPHABET[i]] = i;
  }
  return LOOKUP;
}

// Base64 文本 → 字节：每 4 个字符一组拼 3 个字节；
// 字符表外的字符直接判非法，末组只有 1 个字符也判非法。
function fromBase64(text) {
  var normalized = text.replace(/-/g, "+").replace(/_/g, "/");
  if (/[^A-Za-z0-9+/=\s]/.test(normalized)) {
    return null;
  }
  var clean = normalized.replace(/[\s=]/g, "");
  var table = lookup();
  var bytes = [];
  for (var i = 0; i < clean.length; i += 4) {
    var group = clean.slice(i, i + 4);
    if (group.length === 1) {
      return null;
    }
    var values = [];
    for (var j = 0; j < group.length; j++) {
      var v = table[group[j]];
      if (v === undefined) {
        return null;
      }
      values.push(v);
    }
    var packed =
      (values[0] << 18) |
      ((values[1] || 0) << 12) |
      ((values[2] || 0) << 6) |
      (values[3] || 0);
    bytes.push((packed >> 16) & 0xff);
    if (values.length > 2) {
      bytes.push((packed >> 8) & 0xff);
    }
    if (values.length > 3) {
      bytes.push(packed & 0xff);
    }
  }
  return bytes;
}

// 字节 → 文字：按首字节定长度，再逐个收续字节拼码位。
function fromUtf8(bytes) {
  var chars = [];
  var i = 0;
  while (i < bytes.length) {
    var first = bytes[i];
    var size;
    if (first < 0x80) {
      size = 1;
    } else if (first >= 0xc0 && first < 0xe0) {
      size = 2;
    } else if (first >= 0xe0 && first < 0xf0) {
      size = 3;
    } else if (first >= 0xf0 && first < 0xf8) {
      size = 4;
    } else {
      return null;
    }
    if (i + size > bytes.length) {
      return null;
    }
    var mask = size === 1 ? 0x7f : size === 2 ? 0x1f : size === 3 ? 0x0f : 0x07;
    var cp = first & mask;
    for (var k = 1; k < size; k++) {
      var next = bytes[i + k];
      if ((next & 0xc0) !== 0x80) {
        return null;
      }
      cp = (cp << 6) | (next & 0x3f);
    }
    chars.push(String.fromCodePoint(cp));
    i += size;
  }
  return chars.join("");
}

function firstText() {
  var sources = [
    typeof ${'$'}searchText === "string" ? ${'$'}searchText : "",
    typeof ${'$'}selectedText === "string" ? ${'$'}selectedText : "",
    typeof ${'$'}pasteboardContent === "string" ? ${'$'}pasteboardContent : "",
  ];
  for (var i = 0; i < sources.length; i++) {
    if (sources[i].trim() !== "") {
      return sources[i].trim();
    }
  }
  return "";
}

async function output() {
  var input = firstText();
  if (input === "") {
    return ["请输入要解码的 Base64 文本，或选中文本后再运行"];
  }

  var bytes = fromBase64(input);
  if (bytes === null) {
    return ["不是合法的 Base64 文本"];
  }

  var text = fromUtf8(bytes);
  if (text === null) {
    return ["解码结果不是合法的 UTF-8 文本"];
  }

  ${'$'}log("解码 " + bytes.length + " 字节");

  return [text];
}"""

    val ROLL_CONTENT = """/**
 * Roll
 *
 * 不用输入，每次运行返回一个 1 到 100 的随机数。
 *
 * 输出例子：42
 */

async function output() {
  var value = Math.floor(Math.random() * 100) + 1;

  ${'$'}log("roll: " + value);

  return [String(value)];
}"""
}
