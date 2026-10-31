// Built-in cloud voice plugin: Alibaba Cloud NLS real-time recognition.
// Auth: per-session token fetched with POP-signed CreateToken (the secret
// never enters script space; signing goes through the host crypto bridge).
// Audio is buffered until the server confirms the task, then streamed as
// raw PCM frames. Intermediate results preview, sentence ends commit, and
// an unfinalized tail is committed when the task completes.

register({
  id: 'builtin.nls',
  version: '1.0',
  hosts: ['nls-meta.cn-shanghai.aliyuncs.com', 'nls-gateway-cn-shanghai.aliyuncs.com'],
  features: [{ type: 'voice.asr', sampleRate: 16000 }],
  settings: [
    { key: 'appkey', type: 'text', label: 'AppKey' },
    { key: 'access_key_id', type: 'text', label: 'AccessKey ID' },
    { key: 'access_key_secret', type: 'secret', label: 'AccessKey Secret' }
  ],
  auth: []
});

var ws = null;
var taskId = '';
var taskStarted = false;
var finished = false;
var stopSent = false;
var pending = [];
var lastPartial = '';
var lastFinal = '';

function randHex(n) {
  var out = '';
  var chars = '0123456789abcdef';
  for (var i = 0; i < n; i++) {
    out += chars.charAt(Math.floor(Math.random() * 16));
  }
  return out;
}

function uuid() {
  var h = randHex(32);
  return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' +
    h.slice(16, 20) + '-' + h.slice(20);
}

function isoTimestamp() {
  return new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');
}

// Alibaba POP percent-encoding: encodeURIComponent leaves ! ' ( ) * raw,
// POP requires them encoded; ~ stays raw, space becomes %20.
function popEncode(s) {
  return encodeURIComponent(s)
    .replace(/!/g, '%21')
    .replace(/'/g, '%27')
    .replace(/\(/g, '%28')
    .replace(/\)/g, '%29')
    .replace(/\*/g, '%2A');
}

// ASCII-only base64 encode (the token form body is ASCII by construction).
function base64EncodeAscii(s) {
  var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var out = '';
  for (var i = 0; i < s.length; i += 3) {
    var b0 = s.charCodeAt(i) & 0xFF;
    var b1 = i + 1 < s.length ? s.charCodeAt(i + 1) & 0xFF : 0;
    var b2 = i + 2 < s.length ? s.charCodeAt(i + 2) & 0xFF : 0;
    out += chars.charAt(b0 >> 2);
    out += chars.charAt(((b0 & 3) << 4) | (b1 >> 4));
    out += i + 1 < s.length ? chars.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=';
    out += i + 2 < s.length ? chars.charAt(b2 & 63) : '=';
  }
  return out;
}

// ASCII-safe base64 decode: the token JSON fields we read (Token.Id) are
// ASCII; non-ASCII error text may mojibake but never affects the outcome.
function base64DecodeAscii(b64) {
  var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var table = {};
  for (var i = 0; i < chars.length; i++) table[chars.charAt(i)] = i;
  var out = '';
  var bits = 0;
  var nbits = 0;
  for (var j = 0; j < b64.length; j++) {
    var c = b64.charAt(j);
    if (c === '=') break;
    var v = table[c];
    if (v === undefined) continue;
    bits = (bits << 6) | v;
    nbits += 6;
    if (nbits >= 8) {
      nbits -= 8;
      out += String.fromCharCode((bits >> nbits) & 0xFF);
    }
  }
  return out;
}

function fetchToken(settings) {
  var params = {
    AccessKeyId: settings.access_key_id,
    Action: 'CreateToken',
    Version: '2019-02-28',
    RegionId: 'cn-shanghai',
    Format: 'JSON',
    SignatureMethod: 'HMAC-SHA1',
    SignatureVersion: '1.0',
    SignatureNonce: uuid(),
    Timestamp: isoTimestamp()
  };
  var keys = Object.keys(params).sort();
  var pairs = [];
  for (var i = 0; i < keys.length; i++) {
    pairs.push(popEncode(keys[i]) + '=' + popEncode(params[keys[i]]));
  }
  var canonical = pairs.join('&');
  var stringToSign = 'POST&' + popEncode('/') + '&' + popEncode(canonical);
  // POP signs with secret + "&" as the HMAC key; the "&" travels as the
  // bridge's key-suffix argument, the secret itself never enters script.
  var sig = crypto.hmacSha1Base64('access_key_secret', stringToSign, '&');
  if (!sig) return null;
  var body = canonical + '&Signature=' + popEncode(sig);
  var r = net.httpPost(
    'https://nls-meta.cn-shanghai.aliyuncs.com/',
    {},
    base64EncodeAscii(body),
    'application/x-www-form-urlencoded'
  );
  if (!r.ok || !r.bodyBase64) return null;
  var json;
  try {
    json = JSON.parse(base64DecodeAscii(r.bodyBase64));
  } catch (e) {
    return null;
  }
  if (json && json.Token && json.Token.Id) return json.Token.Id;
  return null;
}

function start(ctx) {
  if (!ctx.settings.appkey || !ctx.settings.access_key_id) {
    cb.error();
    return;
  }
  var token = fetchToken(ctx.settings);
  if (!token) {
    cb.error();
    return;
  }
  taskId = randHex(32);
  var r = net.websocketConnect(
    'wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1?token=' + encodeURIComponent(token),
    {}
  );
  if (!r.ok) {
    cb.error();
    return;
  }
  ws = r.ws;
  ws.send(JSON.stringify({
    header: {
      message_id: randHex(32),
      task_id: taskId,
      namespace: 'SpeechTranscriber',
      name: 'StartTranscription',
      appkey: ctx.settings.appkey
    },
    payload: {
      format: 'pcm',
      sample_rate: 16000,
      enable_intermediate_result: true,
      enable_punctuation_prediction: true
    }
  }));
}

function audio(ctx) {
  if (finished || !ws) return;
  if (taskStarted) {
    ws.sendBytes(ctx.pcmBase64);
  } else if (pending.length < 128) {
    pending.push(ctx.pcmBase64);
  }
}

function onMessage(ctx) {
  var msg;
  try {
    msg = JSON.parse(ctx.data);
  } catch (e) {
    return;
  }
  var header = msg.header || {};
  var name = header.name;
  if (name === 'TaskFailed') {
    cb.error();
    return;
  }
  if (header.status !== undefined && header.status !== 20000000) {
    cb.error();
    return;
  }
  var payload = msg.payload || {};
  if (name === 'TranscriptionStarted') {
    taskStarted = true;
    for (var i = 0; i < pending.length; i++) {
      ws.sendBytes(pending[i]);
    }
    pending = [];
  } else if (name === 'TranscriptionResultChanged') {
    if (payload.result) {
      lastPartial = payload.result;
      cb.partial(payload.result);
    }
  } else if (name === 'SentenceEnd') {
    if (payload.result) {
      lastFinal = payload.result;
      // 该句已提交，其中间结果随即作废，不可在完成时再提交一次。
      lastPartial = '';
      cb.final(payload.result);
    }
  } else if (name === 'TranscriptionCompleted') {
    finished = true;
    if (lastPartial && lastPartial !== lastFinal) {
      cb.final(lastPartial);
    }
    cb.ended();
  }
}

function stop(ctx) {
  if (ws && !finished && !stopSent) {
    stopSent = true;
    ws.send(JSON.stringify({
      header: {
        message_id: randHex(32),
        task_id: taskId,
        namespace: 'SpeechTranscriber',
        name: 'StopTranscription',
        appkey: ctx.settings.appkey
      }
    }));
  }
}

function onSocketClosed() {
  if (!finished) cb.error();
}
