import { createFFmpeg } from './ffmpeg/ffmpeg.js';

function isTikTokOrigin(origin) {
  if (origin === 'https://tiktok.com') return true;
  return /^https:\/\/[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*\.tiktok\.com$/.test(origin);
}

function detectParentOrigin() {
  try {
    if (document.referrer) {
      const url = new URL(document.referrer);
      if (isTikTokOrigin(url.origin)) return url.origin;
    }
  } catch (_) {}
  return '*';
}

let _parentOrigin = detectParentOrigin();

function postToParent(msg, transfer = []) {
  window.parent.postMessage(msg, '*', transfer);
}

let ffmpeg       = null;
let ffmpegLoaded = false;

let _encodeQueue = Promise.resolve();

function enqueueEncode(task) {
  const run = () => task().catch((err) => {
    postToParent({
      type:    'ENCODE_ERROR',
      message: err?.message ?? String(err),
    });
  });
  _encodeQueue = _encodeQueue.then(run, run);
  return _encodeQueue;
}

async function ensureFFmpegLoaded() {
  if (ffmpegLoaded) return;

  const coreBaseURL = chrome.runtime.getURL('encoder/core');

  ffmpeg = createFFmpeg({
    log: false,
    corePath:   `${coreBaseURL}/ffmpeg-core.js`,
    wasmPath:   `${coreBaseURL}/ffmpeg-core.wasm`,
    workerPath: `${coreBaseURL}/ffmpeg-core.worker.js`,
    logger: ({ type, message }) => {
      postToParent({ type: 'ENCODE_LOG', message: `[${type}] ${message}` });
    },
    progress: ({ ratio }) => {
      if (typeof ratio === 'number' && ratio >= 0 && isFinite(ratio)) {
        postToParent({ type: 'ENCODE_PROGRESS', ratio });
      }
    },
  });

  await ffmpeg.load();
  ffmpegLoaded = true;
}

function guessInputName(file) {
  const dot = file.name.lastIndexOf('.');
  const ext = dot >= 0 ? file.name.slice(dot).toLowerCase() : '.mp4';
  return 'input' + ext;
}

function getOptimalThreads() {
  const cores = (typeof navigator !== 'undefined' && navigator.hardwareConcurrency)
    ? navigator.hardwareConcurrency
    : 4;

  const threads = Math.max(1, Math.floor(cores / 2));
  return threads;
}

function buildRemuxArgs(inputName, outputName) {
  return [
    '-i',        inputName,
    '-c',        'copy',
    '-threads',  String(getOptimalThreads()),
    '-movflags', '+faststart',
    outputName,
  ];
}

function buildReencodeArgs(inputName, outputName) {
  return [
    '-i',        inputName,
    '-c:v',      'libx264',
    '-preset',   'superfast',
    '-crf',      '23',
    '-threads',  String(getOptimalThreads()),
    '-af',       'aresample=async=1:first_pts=0',
    '-shortest',
    '-movflags', '+faststart',
    outputName,
  ];
}

async function handleEncode(file, mode) {
  let inputName = '';
  let outputName = '';

  try {
    await ensureFFmpegLoaded();

    inputName  = guessInputName(file);
    outputName = 'output.mp4';

    const fileBuffer = await file.arrayBuffer();
    ffmpeg.FS('writeFile', inputName, new Uint8Array(fileBuffer));

    const args = mode === 'reencode'
      ? buildReencodeArgs(inputName, outputName)
      : buildRemuxArgs(inputName, outputName);

    postToParent({
      type:    'ENCODE_LOG',
      message: `[mode=${mode}] $ ffmpeg ${args.join(' ')}`,
    });

    await ffmpeg.run(...args);

    const data = ffmpeg.FS('readFile', outputName);

    if (!data || data.byteLength === 0) {
      throw new Error(`corrupt file mode=${mode}. Encode Failed.`);
    }

    const arrayBuffer = data.buffer.slice(
      data.byteOffset,
      data.byteOffset + data.byteLength
    );

    try { ffmpeg.FS('unlink', outputName); } catch (_) {}
    try { ffmpeg.FS('unlink', inputName);  } catch (_) {}
    
    outputName = ''; 
    inputName = '';  

    if (arrayBuffer.byteLength !== data.byteLength) {
      throw new Error(
        `corrupt file ${data.byteLength} byte, ` +
        `copy ${arrayBuffer.byteLength} byte.`
      );
    }

    const mime = 'video/mp4';
    const baseName = file.name.replace(/\.[^/.]+$/, '');

    postToParent({
      type:        'ENCODE_DONE',
      arrayBuffer,
      fileName:    `${baseName}-SenzeEXT.mp4`,
      mimeType:    mime,
      mode,
    }, [arrayBuffer]);

  } catch (err) {
    postToParent({
      type:    'ENCODE_ERROR',
      message: err?.message ?? String(err),
    });
  } finally {
    if (ffmpeg) {
      if (inputName)  { try { ffmpeg.FS('unlink', inputName);  } catch (_) {} }
      if (outputName) { try { ffmpeg.FS('unlink', outputName); } catch (_) {} }
    }
  }
}

let _bootAcked = false;

window.addEventListener('message', (event) => {
  const msg = event.data;
  if (!msg || typeof msg !== 'object') return;
  if (msg.type === 'BOOT_ACK') {
    _bootAcked = true;
  }
});

window.addEventListener('message', (event) => {
  if (!isTikTokOrigin(event.origin)) return;

  if (_parentOrigin === '*') _parentOrigin = event.origin;

  const msg = event.data;
  if (!msg || typeof msg !== 'object') return;
  if (msg.type === 'BOOT_ACK') return;

  if (msg.type === 'ENCODE_START') {
    const mode = msg.mode === 'reencode' ? 'reencode' : 'remux';

    if (msg.ab instanceof ArrayBuffer) {
      const file = new File([msg.ab], msg.fileName || 'input.mp4', {
        type: msg.fileType || 'video/mp4',
      });
      enqueueEncode(() => handleEncode(file, mode));
    } else if (msg.file instanceof File) {
      enqueueEncode(() => handleEncode(msg.file, mode));
    } else {
      postToParent({ type: 'ENCODE_ERROR', message: 'ENCODE_START: Failed File' });
    }
  }
});

queueMicrotask(() => {
  postToParent({ type: 'FRAME_BOOTED' });

  let attempts = 0;
  const MAX_ATTEMPTS = 20;
  const retryId = setInterval(() => {
    if (_bootAcked || attempts >= MAX_ATTEMPTS) {
      clearInterval(retryId);
      return;
    }
    postToParent({ type: 'FRAME_BOOTED' });
    attempts++;
  }, 100);
});