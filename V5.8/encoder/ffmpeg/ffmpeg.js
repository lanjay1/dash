const readFromBlobOrFile = (blob) =>
  new Promise((resolve, reject) => {
    const fr = new FileReader();
    fr.onload  = () => resolve(fr.result);
    fr.onerror = () => reject(Error('File could not be read'));
    fr.readAsArrayBuffer(blob);
  });

export const fetchFile = async (_data) => {
  if (typeof _data === 'undefined') return new Uint8Array();
  let data = _data;
  if (typeof _data === 'string') {
    if (/data:_data\/([a-zA-Z]*);base64,([^"]*)/.test(_data)) {
      data = atob(_data.split(',')[1]).split('').map((c) => c.charCodeAt(0));
    } else {
      data = await (await fetch(_data)).arrayBuffer();
    }
  } else if (_data instanceof File || _data instanceof Blob) {
    data = await readFromBlobOrFile(_data);
  }
  return new Uint8Array(data);
};

// helpers
const toBlobURL = async (url, mimeType) => {
  const buf  = await (await fetch(url)).arrayBuffer();
  const blob = new Blob([buf], { type: mimeType });
  return URL.createObjectURL(blob);
};

const parseArgs = (Core, args) => {
  const argsPtr = Core._malloc(args.length * 4);
  const argPtrs = args.map((arg) => {
    const ptr = Core._malloc(arg.length + 1);
    for (let i = 0; i < arg.length; i++) Core.HEAPU8[ptr + i] = arg.charCodeAt(i);
    Core.HEAPU8[ptr + arg.length] = 0;
    return ptr;
  });
  argPtrs.forEach((ptr, i) => { Core.HEAP32[(argsPtr >> 2) + i] = ptr; });
  return [args.length, argsPtr];
};

// createFFmpeg
const defaultArgs = ['-nostdin', '-y'];

export const createFFmpeg = (_options = {}) => {
  const {
    log: optLog       = false,
    logger: optLogger = () => {},
    progress: optProg = () => {},
    corePath:   _corePath,
    wasmPath:   _wasmPath,
    workerPath: _workerPath,
  } = _options;

  let Core = null, ffmpeg = null;
  let runResolve = null, runReject = null, running = false;
  let customLogger = optLogger, logging = optLog, progress = optProg;
  let duration = 0, frames = 0, readFrames = false, ratio = 0;

  const log = (type, message) => {
    customLogger({ type, message });
    if (logging) console.log(`[ffmpeg][${type}] ${message}`);
  };

  const ts2sec = (ts) => {
    const [h, m, s] = ts.split(':');
    return parseFloat(h) * 3600 + parseFloat(m) * 60 + parseFloat(s);
  };

  const detectCompletion = (message) => {
    if (message === 'FFMPEG_END' && runResolve) {
      runResolve(); runResolve = null; runReject = null; running = false;
    }
  };

  const parseProgress = (message, prog) => {
    if (typeof message !== 'string') return;
    if (message.startsWith('  Duration')) {
      const d = ts2sec(message.split(', ')[0].split(': ')[1]);
      prog({ duration: d, ratio });
      if (duration === 0 || duration > d) { duration = d; readFrames = true; }
    } else if (readFrames && message.startsWith('    Stream')) {
      const m = message.match(/([\d.]+) fps/);
      frames = m ? duration * parseFloat(m[1]) : 0;
      readFrames = false;
    } else if (message.startsWith('frame') || message.startsWith('size')) {
      const ts = message.split('time=')[1]?.split(' ')[0];
      if (!ts) return;
      const t = ts2sec(ts), m = message.match(/frame=\s*(\d+)/);
      ratio = (frames && m) ? Math.min(parseFloat(m[1]) / frames, 1) : t / duration;
      prog({ ratio, time: t });
    } else if (message.startsWith('video:')) {
      prog({ ratio: 1 }); duration = 0;
    }
  };

  const parseMessage = ({ type, message }) => {
    log(type, message);
    parseProgress(message, progress);
    detectCompletion(message);
  };

  const load = async () => {
    if (Core !== null) throw Error('ffmpeg.wasm sudah di-load.');

    if (typeof window.createFFmpegCore === 'undefined') {
      throw Error(
        'window.createFFmpegCore tidak ditemukan. ' +
        'Pastikan ffmpeg-core.js dimuat sebagai classic <script> sebelum module ini.'
      );
    }

    log('info', 'load ffmpeg-core 0.11.6 (multi-thread)...');

    const corePath   = _corePath;
    const wasmPath   = _wasmPath   ?? corePath.replace(/ffmpeg-core\.js$/, 'ffmpeg-core.wasm');
    const workerPath = _workerPath ?? corePath.replace(/ffmpeg-core\.js$/, 'ffmpeg-core.worker.js');

    const wasmBlobURL = await toBlobURL(wasmPath, 'application/wasm');

    log('info', `wasm   → blob:...`);
    log('info', `worker → ${workerPath} (extension origin)`);

    Core = await window.createFFmpegCore({
      mainScriptUrlOrBlob: corePath,
      printErr: (msg) => parseMessage({ type: 'fferr', message: msg }),
      print:    (msg) => parseMessage({ type: 'ffout', message: msg }),
      locateFile: (path) => {
        if (path.endsWith('ffmpeg-core.wasm'))      return wasmBlobURL;
        if (path.endsWith('ffmpeg-core.worker.js')) return workerPath;
        return path;
      },
    });

    ffmpeg = Core.cwrap('proxy_main', 'number', ['number', 'number']);
    log('info', 'ffmpeg-core Ready (multi-thread)');
  };

  const isLoaded = () => Core !== null;

  const run = (..._args) => {
    log('info', `run: ${_args.join(' ')}`);
    if (!Core)   throw Error('Belum di-load, panggil load() dulu.');
    if (running) throw Error('Proses');
    running = true;
    return new Promise((resolve, reject) => {
      const args = [...defaultArgs, ..._args].filter((s) => s.length !== 0);
      runResolve = resolve; runReject = reject;
      ffmpeg(...parseArgs(Core, args));
    });
  };

  const FS = (method, ...args) => {
    if (!Core) throw Error('Belum di-load, panggil load() dulu.');
    try {
      return Core.FS[method](...args);
    } catch (e) {
      if (method === 'readdir')  throw Error(`FS.readdir('${args[0]}') gagal`);
      if (method === 'readFile') throw Error(`FS.readFile('${args[0]}') gagal`);
      throw Error(`FS.${method} gagal: ${e.message}`);
    }
  };

  const exit = () => {
    if (!Core) throw Error('Belum di-load.');
    if (runReject) runReject('ffmpeg exited');
    running = false;
    try { Core.exit(1); } catch (_) {}
    Core = null; ffmpeg = null; runResolve = null; runReject = null;
  };

  const setProgress = (fn)  => { progress     = fn;  };
  const setLogger   = (fn)  => { customLogger = fn;  };
  const setLogging  = (val) => { logging      = val; };

  return { load, isLoaded, run, FS, exit, setProgress, setLogger, setLogging };
};
