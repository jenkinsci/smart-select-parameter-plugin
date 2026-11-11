/* Minimal fake DOM: exercises the production widget JS without npm or a browser. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

class FakeElement {
  constructor(value = '') {
    this.value = value;
    this.hidden = false;
    this.textContent = '';
    this.dataset = {};
    this.style = {};
    this.attributes = {};
    this.children = [];
    this.handlers = new Map();
    this.classes = new Set();
    this.classList = {
      add: (c) => this.classes.add(c),
      remove: (c) => this.classes.delete(c),
      contains: (c) => this.classes.has(c),
      toggle: (c, flag) => flag ? this.classes.add(c) : this.classes.delete(c)
    };
  }
  addEventListener(type, handler) {
    if (!this.handlers.has(type)) this.handlers.set(type, []);
    this.handlers.get(type).push(handler);
  }
  dispatchEvent(event) {
    event.target ||= this;
    for (const handler of this.handlers.get(event.type) || []) handler(event);
    return true;
  }
  click() { this.dispatchEvent({ type: 'click', target: this }); }
  focus() { this.focused = true; }
  setAttribute(key, value) { this.attributes[key] = value; }
  getAttribute(key) { return this.attributes[key]; }
  contains(el) { return this === el || (this.allChildren || []).includes(el); }
  replaceChildren() { this.children = []; }
  appendChild(el) { this.children.push(el); return el; }
}

function makeWidget(mode, defaults, values) {
  const el = new FakeElement();
  el.dataset.mode = mode;
  const value = new FakeElement(defaults);
  const control = new FakeElement();
  const trigger = new FakeElement();
  const popup = new FakeElement(); popup.hidden = true;
  const summary = new FakeElement();
  const search = new FakeElement();
  const count = new FakeElement();
  const empty = new FakeElement(); empty.hidden = true;
  const status = new FakeElement();
  const selectAll = new FakeElement();
  const clear = new FakeElement();
  const container = new FakeElement();
  const pagination = new FakeElement();
  const previous = new FakeElement();
  const next = new FakeElement();
  const pageInfo = new FakeElement();
  const options = values.map((val) => {
    const option = new FakeElement();
    option.dataset.value = typeof val === 'string' ? val : val.value;
    const label = new FakeElement();
    label.textContent = typeof val === 'string' ? val : val.label;
    option.querySelector = selector => selector === '.smart-select-label' ? label : null;
    option.classList.add('smart-select-option');
    return option;
  });
  const elements = {
    '.smart-select-widget': control,
    '.smart-select-options': container,
    '.smart-select-pagination': pagination,
    '.smart-select-previous': previous,
    '.smart-select-next': next,
    '.smart-select-page-info': pageInfo,
    '.smart-select-value': value,
    '.smart-select-trigger': trigger,
    '.smart-select-popup': popup,
    '.smart-select-summary': summary,
    '.smart-select-search': search,
    '.smart-select-count': count,
    '.smart-select-empty': empty,
    '.smart-select-error': status,
    '.smart-select-select-all': mode === 'MULTI' ? selectAll : null,
    '.smart-select-clear': mode === 'MULTI' ? clear : null,
  };
  el.querySelector = (selector) => elements[selector];
  el.querySelectorAll = (selector) => selector === '.smart-select-option' ? options : [];
  el.allChildren = [...Object.values(elements), ...options];
  container.children = options.slice();
  return { el, control, value, trigger, popup, search, count, empty, options, selectAll, clear, summary, container, pagination, previous, next, pageInfo };
}

const multi = makeWidget('MULTI', 'price,vehicle', ['admin', 'price', 'vehicle', 'job']);
const single = makeWidget('SINGLE', 'pre', ['dev', 'pre', 'release']);
const labeled = makeWidget('MULTI', 'price', [{ value: 'price', label: '报价服务' }, { value: 'admin', label: '管理服务' }]);
const large = makeWidget('MULTI', '', Array.from({length: 2000}, (_, i) => `service-${i}`));
const wide = makeWidget('MULTI', '', ['short', 'a'.repeat(90)]);
const summaryWide = makeWidget('MULTI', '', ['abcdefghijklmno', 'pqrstuvwxyzabcd']);
const noSearch = makeWidget('SINGLE', 'dev', ['dev', 'release']);
const partial = makeWidget('MULTI', '', ['alpha', 'beta']);
let partialComplete = false;
const partialQuery = partial.el.querySelector;
partial.el.querySelector = selector => selector === '.smart-select-empty' && !partialComplete ? null : partialQuery(selector);
partial.el.closest = selector => selector === '.smart-select-parameter' ? partial.el : null;
const noSearchQuery = noSearch.el.querySelector;
noSearch.el.querySelector = selector => selector === '.smart-select-search' ? null : noSearchQuery(selector);
const document = {
  readyState: 'complete',
  body: {},
  querySelectorAll: (selector) => selector === '.smart-select-parameter' ? [multi.el, single.el, labeled.el, large.el, noSearch.el, partial.el, wide.el, summaryWide.el] : [],
  createElement: tag => tag === 'canvas' ? { getContext: () => ({ measureText: text => ({ width: text.length * 10 }) }) } : new FakeElement(),
  addEventListener: () => {}
};
let observerCallback;
class FakeObserver { constructor(callback) { observerCallback = callback; } observe() {} }
class FakeEvent { constructor(type) { this.type = type; } }
const file = path.join(__dirname, '../../main/resources/io/jenkins/plugins/smartselect/smart-select.js');
vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
  document, MutationObserver: FakeObserver, Event: FakeEvent, getComputedStyle: () => ({ font: '14px Arial' }),
}, { filename: file });


assert.notEqual(partial.el.dataset.smartSelectReady, 'true', 'An incomplete widget must wait for its remaining elements');
partialComplete = true;
observerCallback([{target: partial.el, addedNodes: [partial.empty]}]);
assert.equal(partial.el.dataset.smartSelectReady, 'true', 'A completed fragment should initialize when children arrive');
assert.ok(parseFloat(wide.control.style.width) > parseFloat(multi.control.style.width), 'Longer options should produce a wider control');
const initialSummaryWidth = parseFloat(summaryWide.control.style.width);
summaryWide.options[0].click(); summaryWide.options[1].click();
assert.ok(parseFloat(summaryWide.control.style.width) > initialSummaryWidth, 'A long selected summary should fit in the control');
partial.options[0].click();
assert.equal(partial.value.value, 'alpha', 'An asynchronously completed widget must accept selections');

assert.equal(multi.value.value, 'price,vehicle');
assert.equal(multi.summary.textContent, 'price, vehicle');
multi.trigger.click();
assert.equal(multi.popup.hidden, false);
multi.search.value = 'job';
multi.search.dispatchEvent(new FakeEvent('input'));
assert.equal(multi.options.filter(o => !o.hidden).length, 1);
multi.selectAll.click();
assert.equal(multi.value.value, 'price,vehicle,job');
multi.clear.click();
assert.equal(multi.value.value, '');
multi.search.value = '';
multi.search.dispatchEvent(new FakeEvent('input'));
multi.options[0].click();
assert.equal(multi.value.value, 'admin');
assert.equal(multi.popup.hidden, false); // MULTI stays open
single.trigger.click();
single.options[2].click();
assert.equal(single.value.value, 'release');
assert.equal(single.popup.hidden, true); // SINGLE closes on selection
assert.equal(single.trigger.getAttribute('aria-expanded'), 'false');
// Enter in the search box must never fall through to the Jenkins build form.
multi.search.value = 'no-such-service';
multi.search.dispatchEvent(new FakeEvent('input'));
let prevented = false;
multi.search.dispatchEvent({
  type: 'keydown', key: 'Enter', preventDefault() { prevented = true; }
});
assert.equal(prevented, true, 'Enter with no matches must not submit the build form');
assert.equal(multi.value.value, 'admin');
multi.search.value = 'job';
multi.search.dispatchEvent(new FakeEvent('input'));
prevented = false;
multi.search.dispatchEvent({
  type: 'keydown', key: 'Enter', preventDefault() { prevented = true; }
});
assert.equal(prevented, true);
assert.equal(multi.value.value, 'admin,job');
assert.equal(labeled.summary.textContent, '报价服务');
labeled.search.value = '管理';
labeled.search.dispatchEvent(new FakeEvent('input'));
assert.equal(labeled.options.filter(o => !o.hidden).length, 1);
assert.equal(labeled.options.find(o => !o.hidden).dataset.value, 'admin');
labeled.selectAll.click();
assert.equal(labeled.value.value, 'price,admin');
assert.equal(labeled.summary.textContent, '报价服务, 管理服务');
assert.ok(large.container.children.length <= 100, 'Long lists must bound attached option nodes');
large.trigger.click();
large.selectAll.click();
assert.equal(large.value.value.split(',').length, 2000, 'Select-all includes other pages');
large.next.click();
assert.equal(large.container.children[0].dataset.value, 'service-100');
large.search.value = 'service-1999';
large.search.dispatchEvent(new FakeEvent('input'));
assert.equal(large.container.children.length, 1);
assert.equal(large.container.children[0].dataset.value, 'service-1999');
let keyboardPrevented = false;
noSearch.trigger.dispatchEvent({ type: 'keydown', key: 'ArrowDown', preventDefault() { keyboardPrevented = true; } });
assert.equal(keyboardPrevented, true);
assert.equal(noSearch.popup.hidden, false);
assert.equal(noSearch.options[0].focused, true);
noSearch.el.dispatchEvent({ type: 'keydown', key: 'End', target: noSearch.options[0], preventDefault() {} });
assert.equal(noSearch.options[1].focused, true);
noSearch.el.dispatchEvent({ type: 'keydown', key: 'Tab', target: noSearch.options[1], preventDefault() { throw new Error('Tab must not be trapped'); } });
assert.equal(noSearch.popup.hidden, true);
console.log('widget-smoke: all assertions passed');
