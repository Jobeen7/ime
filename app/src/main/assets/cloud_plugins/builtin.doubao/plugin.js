// Built-in cloud voice plugin: streaming recognition over WebSocket with a
// compact binary frame protocol (4-byte header + big-endian lengths).
// Auth: the API key is attached by the host from the key store; the script
// never sees the key value. The model setting only selects the resource id
// header; the request model name stays 'bigmodel'. Audio frames carry raw
// PCM. Server responses are binary frames whose JSON payload carries the
// transcript.

register({
  id: 'builtin.doubao',
  version: '1.4',
  hosts: ['openspeech.bytedance.com'],
  features: [{ type: 'voice.asr', sampleRate: 16000 }],
  settings: [
    { key: 'api_key', type: 'secret', label: 'X-Api-Key' },
    {
      key: 'model',
      type: 'select',
      label: '识别模型',
      options: ['豆包大模型流式 v2（推荐）', '豆包大模型流式 v1'],
      defaultValue: ''
    }
  ],
  auth: [{ header: 'X-Api-Key', field: 'api_key', prefix: '' }]
});

var ws = null;
var finished = false;
var connectId = '';
var sessionUid = '';

var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

function randHex(n) {
  var out = '';
  var chars = '0123456789abcdef';
  for (var i = 0; i < n; i++) {
    out += chars.charAt(Math.floor(Math.random() * 16));
  }
  return out;
}

function b64Encode(bytes) {
  var out = '';
  for (var i = 0; i < bytes.length; i += 3) {
    var b0 = bytes[i];
    var b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
    var b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
    out += B64.charAt(b0 >> 2);
    out += B64.charAt(((b0 & 3) << 4) | (b1 >> 4));
    out += i + 1 < bytes.length ? B64.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=';
    out += i + 2 < bytes.length ? B64.charAt(b2 & 63) : '=';
  }
  return out;
}

function utf8Encode(str) {
  var out = [];
  for (var i = 0; i < str.length; i++) {
    var c = str.charCodeAt(i);
    if (c < 128) {
      out.push(c);
    } else if (c < 2048) {
      out.push(192 | (c >> 6), 128 | (c & 63));
    } else if (c >= 55296 && c <= 56319 && i + 1 < str.length) {
      var d = str.charCodeAt(i + 1);
      if (d >= 56320 && d <= 57343) {
        var cp = 65536 + ((c - 55296) << 10) + (d - 56320);
        out.push(240 | (cp >> 18), 128 | ((cp >> 12) & 63), 128 | ((cp >> 6) & 63), 128 | (cp & 63));
        i++;
      } else {
        out.push(239, 191, 189);
      }
    } else {
      out.push(224 | (c >> 12), 128 | ((c >> 6) & 63), 128 | (c & 63));
    }
  }
  return out;
}

function start(ctx) {
  connectId = randHex(32);
  sessionUid = randHex(32);
  // Model setting only switches the resource id header; empty or unknown
  // keeps the previous resource (existing behavior). The request model
  // name stays 'bigmodel' for both models.
  var resourceId = ctx.settings.model === '豆包大模型流式 v2（推荐）'
    ? 'volc.seedasr.sauc.duration'
    : 'volc.bigasr.sauc.duration';
  var r = net.websocketConnect('wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async', {
    'X-Api-Resource-Id': resourceId,
    'X-Api-Connect-Id': connectId
  });
  if (!r.ok) {
    cb.error();
    return;
  }
  ws = r.ws;
  var config = JSON.stringify({
    user: { uid: sessionUid },
    audio: { format: 'pcm', rate: 16000, bits: 16, channel: 1 },
    request: {
      model_name: 'bigmodel',
      enable_itn: true,
      enable_punc: true,
      enable_ddc: true,
      show_utterances: true
    }
  });
  // 配置帧改由宿主组帧（net.configFrame）：类型 1、JSON 序列化、gzip
  // 压缩，服务端只接受该组合；脚本侧旧 frame() 已删。
  var cf = net.configFrame(b64Encode(utf8Encode(config)));
  if (!cf) {
    cb.error();
    return;
  }
  ws.sendBytes(cf);
}

function audio(ctx) {
  if (finished || !ws) return;
  // 组帧改由宿主做（net.audioFrame）：脚本侧逐字节编解码在 Rhino 下
  // 超 50ms 事件预算，首块即停用插件；宿主组帧为流式协议音频帧
  // （类型 2、gzip 压缩），不再是旧的 raw 音频帧格式。
  var f = net.audioFrame(ctx.pcmBase64);
  if (f) ws.sendBytes(f);
}

function onMessage(ctx) {
  if (!ctx.isBinary) return;
  // 响应解析改由宿主做（net.doubaoResult）：帧头解析、gzip 解压、
  // UTF-8 解码与 JSON 取文本均在 Kotlin 侧；脚本内四遍逐字节循环
  // 随载荷增大超 50ms 事件预算，2026-10-30 真机 partial 之后即因此
  // 被杀。脚本只做路由；桥返 null＝丢帧，error＝服务端错误帧。
  var r = net.doubaoResult(ctx.data);
  if (!r) return;
  if (r.error) {
    cb.error();
    return;
  }
  if (r.final) {
    finished = true;
    if (r.text) cb.final(r.text);
    cb.ended();
  } else if (r.text) {
    cb.partial(r.text);
  }
}

function stop(ctx) {
  if (ws && !finished) {
    // 空末帧走宿主音频帧桥（类型 2、标志 2、gzip 空载荷）。
    var f = net.audioFrame('', true);
    if (f) ws.sendBytes(f);
  }
}

function onSocketClosed() {
  if (!finished) cb.error();
}
