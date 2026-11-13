/* Smart Select Parameter — dependency-free Jenkins adjunct. */
(() => {
  "use strict";
  let nextId = 0;
  const controllers = new WeakMap();

  function init(widget) {
    if (widget.dataset.smartSelectReady === "true") return;

    // Rendered by Jenkins for the current request locale; English is the fallback.
    const message = (key, fallback, ...args) =>
      (widget.dataset['i18n' + key] || fallback).replace(/\{(\d+)\}/g, (match, index) => args[index] ?? match);
    const multiple = widget.dataset.mode === "MULTI";
    const hidden = widget.querySelector(".smart-select-value");
    const control = widget.querySelector('.smart-select-widget');
    const trigger = widget.querySelector(".smart-select-trigger");
    const popup = widget.querySelector(".smart-select-popup");
    const summary = widget.querySelector(".smart-select-summary");
    const search = widget.querySelector(".smart-select-search");
    const count = widget.querySelector(".smart-select-count");
    const empty = widget.querySelector(".smart-select-empty");
    const container = widget.querySelector('.smart-select-options');
    const refresh = widget.querySelector('.smart-select-refresh');
    const status = widget.querySelector('.smart-select-error');
    // Jenkins may expose a widget while its HTML is still being inserted.
    if (!hidden || !control || !trigger || !popup || !summary || !empty || !container || !status
        || (widget.dataset.remote === 'true' && !refresh)) return;
    widget.dataset.smartSelectReady = "true";
    const pagination = widget.querySelector('.smart-select-pagination');
    const previousPage = widget.querySelector('.smart-select-previous');
    const nextPage = widget.querySelector('.smart-select-next');
    const pageInfo = widget.querySelector('.smart-select-page-info');
    const parameterName = widget.querySelector('input[name="name"]')?.value || '';
    const dependencies = (widget.dataset.dependsOn || '').split(',').filter(Boolean);
    let pageIndex = 0;
    let activeValue = '';
    const pageSize = 100;
    const listId = `smart-select-list-${++nextId}`;
    if (container) {
      container.id = listId;
      container.setAttribute('aria-label', message('OptionsLabel', '{0} options', parameterName || message('Parameter', 'Parameter')));
    }
    trigger.setAttribute('aria-controls', listId);
    if (search) search.setAttribute('aria-controls', listId);

    function makeOption(item) {
      if (typeof item.value !== 'string' || typeof item.label !== 'string') throw new Error('Invalid option');
      const option = document.createElement('button');
      option.type = 'button'; option.className = 'smart-select-option';
      option.dataset.value = item.value;
      option.setAttribute('role', 'option');
      const indicator = document.createElement('span');
      indicator.className = 'smart-select-indicator'; indicator.setAttribute('aria-hidden', 'true');
      const label = document.createElement('span');
      label.className = 'smart-select-label'; label.textContent = item.label;
      option.appendChild(indicator); option.appendChild(label);
      return option;
    }
    let all = Array.from(widget.querySelectorAll(".smart-select-option"));
    const initialData = widget.querySelector('.smart-select-data');
    if (initialData) all = JSON.parse(initialData.value).map(makeOption);
    let byValue = new Map(all.map((element) => [element.dataset.value, element]));
    const labelFor = (value) => byValue.get(value)?.querySelector('.smart-select-label')?.textContent || value;
    let optionWidth = 0;
    let measurement;
    try {
      measurement = document.createElement('canvas').getContext('2d');
    } catch (_) {
      // Use the CSS default when text measurement is unavailable.
    }
    function measureOptions() {
      if (!measurement) return;
      measurement.font = getComputedStyle(trigger).font || '14px sans-serif';
      optionWidth = all.reduce((longest, option) =>
        Math.max(longest, measurement.measureText(labelFor(option.dataset.value)).width), 0);
    }
    function updateWidth() {
      if (!measurement) return;
      const summaryWidth = measurement.measureText(summary.textContent).width;
      control.style.width = `${Math.ceil(Math.max(240, optionWidth + 88, summaryWidth + 64))}px`;
    }
    measureOptions();
    const selected = new Set(
      (hidden.value || "").split(",").map((v) => v.trim()).filter((v) => byValue.has(v))
    );

    function visibleOptions() {
      return all.filter((option) => !option.hidden);
    }

    function render() {
      const visible = visibleOptions();
      const pages = Math.max(1, Math.ceil(visible.length / pageSize));
      pageIndex = Math.max(0, Math.min(pageIndex, pages - 1));
      const current = visible.slice(pageIndex * pageSize, (pageIndex + 1) * pageSize);
      if (container) {
        container.replaceChildren();
        current.forEach((option, index) => {
          option.setAttribute('aria-posinset', String(pageIndex * pageSize + index + 1));
          option.setAttribute('aria-setsize', String(visible.length));
          container.appendChild(option);
        });
      }
      if (pagination) pagination.hidden = all.length <= pageSize;
      if (previousPage) previousPage.disabled = pageIndex === 0;
      if (nextPage) nextPage.disabled = pageIndex >= pages - 1;
      if (pageInfo) pageInfo.textContent = message('PageInfo', 'Page {0}/{1} - {2} results', pageIndex + 1, pages, visible.length);
    }

    function focusOption(option) {
      if (!option) return;
      const index = visibleOptions().indexOf(option);
      if (index < 0) return;
      pageIndex = Math.floor(index / pageSize);
      activeValue = option.dataset.value;
      all.forEach(item => { item.tabIndex = item === option ? 0 : -1; });
      render(); option.focus();
    }

    function sync() {
      const values = Array.from(selected);
      const previousValue = hidden.value;
      hidden.value = values.join(",");
      all.forEach((option) => {
        const checked = selected.has(option.dataset.value);
        option.setAttribute("aria-selected", String(checked));
        option.classList.toggle("is-selected", checked);
        option.tabIndex = option.dataset.value === activeValue ? 0 : -1;
      });
      if (!values.length) {
        summary.textContent = message('Placeholder', 'Select...');
        summary.classList.add("is-placeholder");
      } else {
        summary.classList.remove("is-placeholder");
        summary.textContent = values.length <= 2
          ? values.map(labelFor).join(message('LabelSeparator', ', '))
          : message('Selected', '{0} selected', values.length);
      }
      if (count) count.textContent = message('Count', '{0} / {1} selected', values.length, all.length);
      if (parameterName) trigger.setAttribute('aria-label', `${parameterName}: ${summary.textContent}`);
      updateWidth();
      if (previousValue !== hidden.value) hidden.dispatchEvent(new Event("change", { bubbles: true }));
    }

    function filter() {
      const q = (search ? search.value : "").trim().toLocaleLowerCase();
      all.forEach((option) => {
        option.hidden = !`${labelFor(option.dataset.value)} ${option.dataset.value}`.toLocaleLowerCase().includes(q);
      });
      empty.hidden = visibleOptions().length > 0;
      pageIndex = 0; render();
    }

    function open() {
      popup.hidden = false;
      trigger.setAttribute("aria-expanded", "true");
      filter();
      if (search) search.focus();
      else focusOption(visibleOptions().find(option => selected.has(option.dataset.value)) || visibleOptions()[0]);
    }

    function close() {
      popup.hidden = true;
      trigger.setAttribute("aria-expanded", "false");
    }

    trigger.addEventListener("click", () => {
      popup.hidden ? open() : close();
    });
    trigger.addEventListener('keydown', event => {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        open();
        const visible = visibleOptions();
        focusOption(event.key === 'ArrowUp' ? visible[visible.length - 1] : visible[0]);
      }
    });
    if (previousPage) previousPage.addEventListener('click', () => { pageIndex--; render(); });
    if (nextPage) nextPage.addEventListener('click', () => { pageIndex++; render(); });

    function bindOption(option) {
      option.addEventListener('focus', () => { activeValue = option.dataset.value; });
      option.addEventListener("click", () => {
        const value = option.dataset.value;
        if (multiple) {
          if (selected.has(value)) selected.delete(value);
          else selected.add(value);
        } else {
          selected.clear();
          selected.add(value);
          close();
          trigger.focus();
        }
        sync();
      });
    }
    all.forEach(bindOption);

    const form = widget.closest?.('form');
    let requestSequence = 0;
    let requestController;
    let initialSelection = true;
    function setState(state, message = '') {
      widget.dataset.state = state;
      if (status) status.textContent = message;
      trigger.disabled = state !== 'ready';
      if (refresh) refresh.disabled = state === 'loading';
      widget.setAttribute('aria-busy', String(state === 'loading'));
      if (form) {
        const ready = Array.from(form.querySelectorAll('.smart-select-parameter'))
          .every(item => !item.dataset.state || item.dataset.state === 'ready');
        form.querySelectorAll('button[type="submit"], button:not([type]), input[type="submit"]').forEach(button => {
          if (!ready && !button.disabled) {
            button.dataset.smartSelectDisabled = 'true'; button.disabled = true;
          } else if (ready && button.dataset.smartSelectDisabled === 'true') {
            delete button.dataset.smartSelectDisabled; button.disabled = false;
          }
        });
      }
    }

    function reloadOptions(forceRefresh = false) {
      if (!form) return;
      const sequence = ++requestSequence;
      if (requestController) requestController.abort();
      requestController = new AbortController();
      const controller = requestController;
      const parents = {};
      let waiting = false;
      const blocks = Array.from(form.querySelectorAll('[name="parameter"]'));
      dependencies.forEach(name => {
        const parent = blocks.find(block => block.querySelector('input[name="name"]')?.value === name);
        const field = parent?.querySelector('[name="value"]');
        if (!field || !field.value || (parent.dataset.state && parent.dataset.state !== 'ready')) waiting = true;
      });
      const needed = new Set(dependencies);
      for (let depth = 0; depth < 16; depth++) blocks.forEach(block => {
        if (needed.has(block.querySelector('input[name="name"]')?.value)) {
          (block.dataset.dependsOn || '').split(',').filter(Boolean).forEach(name => needed.add(name));
        }
      });
      // Only the declared dependency chain is sent; unrelated fields (including secrets) stay out.
      blocks.forEach(block => {
        const name = block.querySelector('input[name="name"]')?.value;
        const field = block.querySelector('[name="value"]');
        if (needed.has(name) && field && field.type !== 'password') parents[name] = field.type === 'checkbox' ? String(field.checked) : field.value;
      });
      if (waiting) {
        selected.clear(); setState('waiting', message('Waiting', 'Select parent parameters first')); sync(); close();
        return;
      }
      setState('loading', message('Loading', 'Loading options...'));
      const timeout = setTimeout(() => {
        if (sequence !== requestSequence) return;
        controller.abort(); selected.clear(); close();
        setState('error', message('Timeout', 'Loading options timed out. Please try again.')); sync();
      }, 10000);
      return Promise.resolve().then(() => {
        if (sequence !== requestSequence) throw new Error('Stale request');
        const url = new URL('descriptorByName/io.jenkins.plugins.smartselect.SmartSelectParameterDefinition/options', new URL('.', form.action));
        const headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        if (window.crumb) window.crumb.wrap(headers);
        return fetch(url, {
          method: 'POST', headers, credentials: 'same-origin', signal: controller.signal,
          body: new URLSearchParams({ name: parameterName, parents: JSON.stringify(parents), refresh: String(Boolean(forceRefresh)) })
        });
      }).then(response => {
        if (!response.ok) throw new Error('Request failed');
        return response.json();
      }).then(envelope => {
        clearTimeout(timeout);
        if (sequence !== requestSequence) return;
        const data = envelope.data || envelope;
        if (data.error || !Array.isArray(data.options)) throw new Error('Options unavailable');
        const next = data.options.map(makeOption);
        next.forEach(bindOption);
        all = next;
        byValue = new Map(all.map(option => [option.dataset.value, option]));
        measureOptions();
        for (const value of selected) if (!byValue.has(value)) selected.delete(value);
        if (initialSelection && dependencies.length && data.defaultValue) {
          data.defaultValue.split(',').filter(value => byValue.has(value)).forEach(value => selected.add(value));
        }
        initialSelection = false;
        setState('ready');
        sync(); filter();
      }).catch(error => {
        clearTimeout(timeout);
        if (sequence !== requestSequence || error.name === 'AbortError') return;
        selected.clear(); close();
        setState('error', message('Unavailable', 'Options are unavailable. Check the data source configuration and try again.'));
        sync();
      });
    }
    if (refresh) refresh.addEventListener('click', () => reloadOptions(true));
    if (form && dependencies.length) {
      form.addEventListener('change', event => {
        const parent = event.target.closest?.('[name="parameter"]');
        if (parent && dependencies.includes(parent.querySelector('input[name="name"]')?.value)) {
          selected.clear(); setState('loading'); sync(); close(); reloadOptions();
        }
      });
      setTimeout(reloadOptions, 0);
    }
    if (form) form.addEventListener('submit', event => {
      if (widget.dataset.state !== 'ready') {
        event.preventDefault();
        if (refresh) refresh.focus();
      }
    });
    setState(widget.dataset.error ? 'error' : 'ready', widget.dataset.error || '');

    if (search) {
      search.addEventListener("input", filter);
      search.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
          event.preventDefault(); // Search must never submit the build form, even without matches.
          const first = visibleOptions()[0];
          if (first) {
            first.click();
          }
        } else if (event.key === "ArrowDown" || event.key === 'ArrowUp') {
          const visible = visibleOptions();
          const first = event.key === 'ArrowUp' ? visible[visible.length - 1] : visible[0];
          if (first) {
            event.preventDefault();
            focusOption(first);
          }
        }
      });
    }

    const selectAll = widget.querySelector(".smart-select-select-all");
    if (selectAll) {
      selectAll.addEventListener("click", () => {
        visibleOptions().forEach((option) => selected.add(option.dataset.value));
        sync();
      });
    }

    const clear = widget.querySelector(".smart-select-clear");
    if (clear) {
      clear.addEventListener("click", () => {
        selected.clear();
        sync();
      });
    }

    widget.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && !popup.hidden) {
        event.preventDefault();
        close();
        trigger.focus();
      }
      if (event.key === 'Tab') close();
      if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)
          && event.target.classList.contains("smart-select-option")) {
        const visible = visibleOptions();
        const current = visible.indexOf(event.target);
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? visible.length - 1
          : event.key === "ArrowDown" ? current + 1 : current - 1;
        event.preventDefault();
        if (next >= 0 && next < visible.length) {
          event.preventDefault();
          focusOption(visible[next]);
        }
      }
    });

    controllers.set(widget, { close });
    sync(); filter();
    document.fonts?.ready.then(() => { measureOptions(); updateWidth(); });
  }

  function initAll() {
    document.querySelectorAll(".smart-select-parameter").forEach(init);
  }
  document.addEventListener('pointerdown', event => {
    document.querySelectorAll('.smart-select-parameter').forEach(widget => {
      if (!widget.contains(event.target)) controllers.get(widget)?.close();
    });
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initAll, { once: true });
  } else {
    initAll();
  }

  // Jenkins can insert parameter form fragments asynchronously.
  const observer = new MutationObserver((mutations) => {
    if (mutations.some((mutation) =>
      mutation.addedNodes.length && (mutation.target.closest?.('.smart-select-parameter') ||
      Array.from(mutation.addedNodes).some((node) => node.nodeType === 1 &&
        (node.matches?.(".smart-select-parameter") || node.querySelector?.(".smart-select-parameter")))))) {
      initAll();
    }
  });
  const startObserving = () => observer.observe(document.body, { childList: true, subtree: true });
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", startObserving, { once: true });
  else if (document.body) startObserving();
})();
