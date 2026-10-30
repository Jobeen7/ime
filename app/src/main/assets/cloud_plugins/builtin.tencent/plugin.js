// Built-in cloud voice plugin: streaming recognition over WebSocket.
// Auth: request signature computed by the host signing bridge from the
// stored key; the script only ever handles the field name, never the key
// value. Audio is buffered until the server handshake ack, then streamed
// as raw PCM frames. Unstable slices preview, steady slices commit, the
// stream-final message ends the session.

register({
  id: 'builtin.tencent',
  version: '1.1',
  hosts: ['asr.cloud.tencent.com'],
  features: [{ type: 'voice.asr', sampleRate: 16000 }],
  settings: [
    { key: 'appid', type: 'text', label: '应用 ID' },
    { key: 'secretid', type: 'text', label: 'SecretId' },
    { key: 'secretkey', type: 'secret', label: 'SecretKey' },
    {
      key: 'category',
      type: 'select',
      label: '识别类型',
      options: ['大模型 2.0 · 流式', '大模型 1.0 · 流式', '标准版 · 流式', '大模型 · 极速文件', '标准版 · 极速文件'],
      defaultValue: ''
    },
    {
      key: 'engine',
      type: 'select',
      label: '识别引擎',
      options: [
        '中英语言', 'Hy-ASR-3.0-preview', '多语言', '英语通用',
        '中文通用', '中文繁体', '中文教育', '中文医疗', '中文法庭', '粤语',
        '英语游戏', '英语教育', '韩语', '日语', '泰语', '印尼语', '越南语',
        '马来语', '菲律宾语', '葡萄牙语', '土耳其语', '阿拉伯语', '西班牙语',
        '印地语', '法语', '德语', '中英粤混合'
      ],
      defaultValue: ''
    }
  ],
  auth: []
});

// Category label -> ordered [engine label, engine id] pairs. The same engine
// label maps to different ids under different categories, so the category
// must be resolved first. Unknown category keeps the previous engine id
// (existing behavior); a known category with an engine label outside it
// falls back to that category's first engine (same rule as switching the
// category on the settings page).
var CATEGORY_ENGINES = {
  '大模型 2.0 · 流式': [
    ['中英语言', '16k_zh_en_2.0'], ['Hy-ASR-3.0-preview', 'Hy-ASR-3.0-preview']
  ],
  '大模型 1.0 · 流式': [
    ['中英语言', '16k_zh_en'], ['多语言', '16k_multi_lang'], ['英语通用', '16k_en_large']
  ],
  '标准版 · 流式': [
    ['中文通用', '16k_zh'], ['中文繁体', '16k_zh-TW'], ['中文教育', '16k_zh_edu'],
    ['中文医疗', '16k_zh_medical'], ['中文法庭', '16k_zh_court'], ['粤语', '16k_yue'],
    ['英语通用', '16k_en'], ['英语游戏', '16k_en_game'], ['英语教育', '16k_en_edu'],
    ['韩语', '16k_ko'], ['日语', '16k_ja'], ['泰语', '16k_th'], ['印尼语', '16k_id'],
    ['越南语', '16k_vi'], ['马来语', '16k_ms'], ['菲律宾语', '16k_fil'],
    ['葡萄牙语', '16k_pt'], ['土耳其语', '16k_tr'], ['阿拉伯语', '16k_ar'],
    ['西班牙语', '16k_es'], ['印地语', '16k_hi'], ['法语', '16k_fr'], ['德语', '16k_de']
  ],
  '大模型 · 极速文件': [
    ['中英语言', '16k_zh_en'], ['多语言', '16k_multi_lang']
  ],
  '标准版 · 极速文件': [
    ['中文通用', '16k_zh'], ['中英粤混合', '16k_zh-PY'], ['粤语', '16k_yue'],
    ['英语通用', '16k_en'], ['日语', '16k_ja'], ['韩语', '16k_ko'],
    ['越南语', '16k_vi'], ['马来语', '16k_ms'], ['印尼语', '16k_id'],
    ['菲律宾语', '16k_fil'], ['泰语', '16k_th'], ['葡萄牙语', '16k_pt'],
    ['土耳其语', '16k_tr'], ['阿拉伯语', '16k_ar'], ['西班牙语', '16k_es'],
    ['印地语', '16k_hi'], ['法语', '16k_fr'], ['德语', '16k_de']
  ]
};

function resolveEngineId(category, engineLabel) {
  var list = CATEGORY_ENGINES[category];
  if (!list) return '16k_zh_large';
  for (var i = 0; i < list.length; i++) {
    if (list[i][0] === engineLabel) return list[i][1];
  }
  return list[0][1];
}

var ws = null;
var acked = false;
var finished = false;
var pending = [];

function randInt(max) {
  return Math.floor(Math.random() * max) + 1;
}

function randId(n) {
  var out = '';
  var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  for (var i = 0; i < n; i++) {
    out += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return out;
}

function start(ctx) {
  var s = ctx.settings;
  var ts = Math.floor(Date.now() / 1000);
  var params = {
    secretid: s.secretid,
    timestamp: String(ts),
    expired: String(ts + 3600),
    nonce: String(randInt(1000000000)),
    engine_model_type: resolveEngineId(s.category || '', s.engine || ''),
    voice_id: randId(16),
    voice_format: '1',
    needvad: '1'
  };
  var keys = [];
  for (var k in params) keys.push(k);
  keys.sort();
  var query = '';
  for (var i = 0; i < keys.length; i++) {
    if (i > 0) query += '&';
    query += keys[i] + '=' + params[keys[i]];
  }
  var plain = 'asr.cloud.tencent.com/asr/v2/' + s.appid + '?' + query;
  var sig = crypto.hmacSha1Base64('secretkey', plain);
  if (sig === null) {
    cb.error();
    return;
  }
  var url = 'wss://asr.cloud.tencent.com/asr/v2/' + s.appid + '?' + query +
    '&signature=' + encodeURIComponent(sig);
  var r = net.websocketConnect(url, {});
  if (!r.ok) {
    cb.error();
    return;
  }
  ws = r.ws;
}

function audio(ctx) {
  if (finished || !ws) return;
  if (acked) {
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
  if (msg.code !== 0) {
    cb.error();
    return;
  }
  acked = true;
  for (var i = 0; i < pending.length; i++) {
    ws.sendBytes(pending[i]);
  }
  pending = [];
  if (msg.final === 1) {
    finished = true;
    cb.ended();
    return;
  }
  var result = msg.result;
  if (!result || !result.voice_text_str) return;
  if (result.slice_type === 2) {
    cb.final(result.voice_text_str);
  } else if (result.slice_type === 1) {
    cb.partial(result.voice_text_str);
  }
}

function stop(ctx) {
  if (ws && !finished) {
    ws.send('{"type":"end"}');
  }
}

function onSocketClosed() {
  if (!finished) cb.error();
}
