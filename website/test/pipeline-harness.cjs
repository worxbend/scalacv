const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createRequire} = require('node:module');
const website = path.resolve(__dirname, '..');
const localRequire = createRequire(path.join(website, 'package.json'));
const {transformSync} = localRequire('@babel/core');

function mount({reduced = false} = {}) {
  const effects = [], states = [], refs = [], observers = [], frames = new Map();
  let sequence = 0;
  const document = {hidden: false, createElement: () => canvas()};
  function canvas() {
    const value = {style: {}, blank: true};
    const context = new Proxy({
      ops: [],
      createLinearGradient: () => ({addColorStop() {}}),
      createImageData: (w, h) => ({data: new Uint8ClampedArray(w * h * 4)}),
      getImageData: () => ({data: Uint8ClampedArray.from({length: 384 * 240 * 4}, (_, i) => i % 251)}),
      measureText: text => ({width: text.length * 6}),
    }, {get(target, key) {
      if (key in target) return target[key];
      return (...args) => {
        if (key === 'fillRect' && typeof target.fillStyle === 'object') target.ops = [];
        target.ops.push([key, ...args.map(a => a?.getContext ? a.getContext().ops : a)]);
        value.blank = false;
      };
    }});
    for (const dim of ['width', 'height']) Object.defineProperty(value, dim, {
      get: () => value['_' + dim],
      set: n => { value['_' + dim] = n; value.blank = true; context.ops = []; },
    });
    value.getContext = () => context;
    return value;
  }
  const screen = canvas(), wrap = {getBoundingClientRect: () => ({width: 384})};
  const react = {
    useRef(value) { const ref = {current: value}; refs.push(ref); return ref; },
    useState(value) { const i = states.length; states.push(value); return [value, next => {
      states[i] = typeof next === 'function' ? next(states[i]) : next;
    }]; },
    useCallback: fn => fn,
    useEffect: fn => effects.push(fn),
    createElement(type, props, ...children) {
      if (props?.ref) props.ref.current = type === 'canvas' ? screen : wrap;
      return {type, props, children};
    },
  };
  class Observer {
    constructor(callback) { this.callback = callback; observers.push(this); }
    observe() {}
    disconnect() { this.disconnected = true; }
  }
  const sandbox = {
    Uint8Array, Uint8ClampedArray, document,
    window: {devicePixelRatio: 1, matchMedia: () => ({matches: reduced})},
    IntersectionObserver: Observer, ResizeObserver: Observer,
    requestAnimationFrame: fn => { frames.set(++sequence, fn); return sequence; },
    cancelAnimationFrame: id => frames.delete(id),
  };
  const cache = new Map();
  function load(file) {
    if (cache.has(file)) return cache.get(file);
    const module = {exports: {}};
    const source = fs.readFileSync(file, 'utf8');
    const {code} = transformSync(source, {filename: file, configFile: false, babelrc: false,
      plugins: [[localRequire.resolve('@babel/plugin-transform-typescript'), {isTSX: file.endsWith('.tsx')}],
        localRequire.resolve('@babel/plugin-transform-react-jsx'),
        localRequire.resolve('@babel/plugin-transform-modules-commonjs')]});
    const requireModule = name => {
      if (name === 'react') return react;
      if (name.endsWith('.css')) return {};
      const target = path.resolve(path.dirname(file), name);
      return load(fs.existsSync(target) ? target : target + '.ts');
    };
    vm.runInNewContext(code, {...sandbox, module, exports: module.exports, require: requireModule}, {filename: file});
    cache.set(file, module.exports);
    return module.exports;
  }
  const tree = load(path.join(website, 'src/components/VisionPipeline/index.tsx')).default();
  const cleanups = effects.map(fn => fn()).filter(Boolean);
  return {screen, states, refs, observers, frames, document, tree, load,
    tick(now) { const pending = [...frames.values()]; frames.clear(); pending.forEach(fn => fn(now)); },
    resize() { observers[1].callback(); },
    pause(value) { refs[2].current = value; },
    drain() { const ctx = screen.getContext(); const ops = JSON.stringify(ctx.ops); ctx.ops = []; return ops; },
    close() { cleanups.forEach(fn => fn()); },
  };
}
module.exports = {mount};
