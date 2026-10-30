// Built-in cloud voice plugin: streaming recognition over WebSocket.
// Auth: the host attaches the declared credential header; the script never
// sees the key value. Audio is buffered until the server confirms the task,
// then streamed as raw PCM frames. Intermediate results preview, sentence
// ends commit, task finish ends the session.

register({
  id: 'builtin.qwen',
  version: '1.2',
  hosts: ['dashscope.aliyuncs.com'],
  features: [{ type: 'voice.asr', sampleRate: 16000 }],
  settings: [
    { key: 'api_key', type: 'secret', label: 'API 密钥' },
    {
      key: 'model',
      type: 'text',
      label: '识别模型',
      defaultValue: 'qwen-audio-3.1-asr-flash-streaming'
    }
  ],
  auth: [{ header: 'Authorization', field: 'api_key', prefix: 'bearer ' }]
});

var ws = null;
var taskId = '';
var taskStarted = false;
var finished = false;
var pending = [];

function randHex(n) {
  var out = '';
  var chars = '0123456789abcdef';
  for (var i = 0; i < n; i++) {
    out += chars.charAt(Math.floor(Math.random() * 16));
  }
  return out;
}

function start(ctx) {
  taskId = randHex(32);
  var r = net.websocketConnect('wss://dashscope.aliyuncs.com/api-ws/v1/inference', {});
  if (!r.ok) {
    cb.error();
    return;
  }
  ws = r.ws;
  ws.send(JSON.stringify({
    header: { action: 'run-task', task_id: taskId, streaming: 'duplex' },
    payload: {
      task_group: 'audio',
      task: 'asr',
      function: 'recognition',
      model: ctx.settings.model || 'qwen-audio-3.1-asr-flash-streaming',
      parameters: { format: 'pcm', sample_rate: 16000, heartbeat: true },
      input: {}
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
  var ev = msg.header && msg.header.event;
  if (ev === 'task-started') {
    taskStarted = true;
    for (var i = 0; i < pending.length; i++) {
      ws.sendBytes(pending[i]);
    }
    pending = [];
  } else if (ev === 'result-generated') {
    var sentence = msg.payload && msg.payload.output && msg.payload.output.sentence;
    if (!sentence || !sentence.text) return;
    if (sentence.sentence_end) {
      cb.final(sentence.text);
    } else {
      cb.partial(sentence.text);
    }
  } else if (ev === 'task-finished') {
    finished = true;
    cb.ended();
  } else if (ev === 'task-failed') {
    cb.error();
  }
}

function stop(ctx) {
  if (ws && !finished) {
    ws.send(JSON.stringify({
      header: { action: 'finish-task', task_id: taskId, streaming: 'duplex' },
      payload: { input: {} }
    }));
  }
}

function onSocketClosed() {
  if (!finished) cb.error();
}
