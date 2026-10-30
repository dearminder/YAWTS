(function () {
  'use strict';

  var API = '/.yawts/api/';

  // ---------- Translations (the page language follows the app's language setting) ----------

  var STRINGS = {
    en: {
      loginPrompt: 'Enter the password to access your files.', loginError: 'Incorrect password.', login: 'Log in',
      upload: 'Upload', uploadFolder: 'Upload folder', newFolder: 'New folder', zip: 'ZIP',
      rename: 'Rename', delete: 'Delete', cancel: 'Cancel', name: 'Name', date: 'Modified', size: 'Size',
      dropHint: 'Drop files or folders here to upload', search: 'Filter…',
      empty: 'This folder is empty.', noMatch: 'Nothing matches the filter.',
      selected: 'Selected: {n}', items: 'Items: {n}', free: '{free} free of {total}',
      download: 'Download', toggleTheme: 'Theme', logout: 'Log out',
      folderName: 'New folder name:', newName: 'New name:',
      deleteConfirm: 'Delete {n} item(s)? This can\'t be undone.',
      uploading: 'Uploading {done}/{total}: {name} — {pct}% · {speed}/s',
      uploaded: 'Uploaded {n} file(s).', uploadFailed: '{n} file(s) failed to upload.',
      deleteFailed: 'Could not delete: {names}', exists: 'An item with that name already exists.',
      badName: 'That name isn\'t allowed.', noSpace: 'Not enough free space on the phone.',
      deleteDisabled: 'Deleting is turned off in the app settings.', error: 'Something went wrong.',
      offline: 'Can\'t reach the phone. Is the server still running?'
    },
    pl: {
      loginPrompt: 'Wpisz hasło, aby uzyskać dostęp do plików.', loginError: 'Nieprawidłowe hasło.', login: 'Zaloguj',
      upload: 'Wyślij', uploadFolder: 'Wyślij folder', newFolder: 'Nowy folder', zip: 'ZIP',
      rename: 'Zmień nazwę', delete: 'Usuń', cancel: 'Anuluj', name: 'Nazwa', date: 'Zmodyfikowano', size: 'Rozmiar',
      dropHint: 'Upuść tu pliki lub foldery, aby je wysłać', search: 'Filtruj…',
      empty: 'Ten folder jest pusty.', noMatch: 'Nic nie pasuje do filtra.',
      selected: 'Zaznaczono: {n}', items: 'Elementów: {n}', free: 'Wolne {free} z {total}',
      download: 'Pobierz', toggleTheme: 'Motyw', logout: 'Wyloguj',
      folderName: 'Nazwa nowego folderu:', newName: 'Nowa nazwa:',
      deleteConfirm: 'Usunąć elementy ({n})? Tej operacji nie można cofnąć.',
      uploading: 'Wysyłanie {done}/{total}: {name} — {pct}% · {speed}/s',
      uploaded: 'Wysłano pliki: {n}.', uploadFailed: 'Nie udało się wysłać plików: {n}.',
      deleteFailed: 'Nie udało się usunąć: {names}', exists: 'Element o tej nazwie już istnieje.',
      badName: 'Ta nazwa jest niedozwolona.', noSpace: 'Za mało wolnego miejsca w telefonie.',
      deleteDisabled: 'Usuwanie jest wyłączone w ustawieniach aplikacji.', error: 'Coś poszło nie tak.',
      offline: 'Brak połączenia z telefonem. Czy serwer nadal działa?'
    }
  };
  var lang = (document.documentElement.lang || 'en').slice(0, 2).toLowerCase();
  var T = STRINGS[lang] || STRINGS.en;

  function t(key, vars) {
    var s = T[key] || STRINGS.en[key] || key;
    if (vars) Object.keys(vars).forEach(function (k) { s = s.split('{' + k + '}').join(vars[k]); });
    return s;
  }

  function $(id) { return document.getElementById(id); }

  function store(key, value) {
    try {
      if (value === undefined) return localStorage.getItem(key);
      localStorage.setItem(key, value);
    } catch (e) { return null; }
  }

  document.querySelectorAll('[data-t]').forEach(function (el) {
    el.textContent = t(el.dataset.t);
    // Toolbar buttons show only their icon on phones - the label stays available as a tooltip.
    if (el.parentElement.classList.contains('btn')) el.parentElement.title = t(el.dataset.t);
  });
  $('search').placeholder = t('search');
  $('themeBtn').title = t('toggleTheme');
  $('logoutBtn').title = t('logout');

  // ---------- State ----------

  var info = null;          // last /api/list response
  var selected = new Set(); // names in the current folder
  var sort = { key: store('yawts-sort') || 'name', desc: store('yawts-desc') === '1' };

  // ---------- Helpers ----------

  function join(dir, name) { return dir ? dir + '/' + name : name; }
  function encodePath(p) { return p.split('/').map(encodeURIComponent).join('/'); }
  function fileUrl(rel) { return '/' + encodePath(rel); }

  function currentPath() {
    var raw = location.hash.replace(/^#\/?/, '');
    try { return raw.split('/').filter(Boolean).map(decodeURIComponent).join('/'); } catch (e) { return ''; }
  }

  function go(path) { location.hash = '#/' + encodePath(path); }

  function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    var units = ['KB', 'MB', 'GB', 'TB'], i = -1;
    do { bytes /= 1024; i++; } while (bytes >= 1024 && i < units.length - 1);
    return bytes.toFixed(bytes < 10 ? 1 : 0) + ' ' + units[i];
  }

  function formatDate(ms) {
    if (!ms) return '';
    var d = new Date(ms);
    function pad(n) { return (n < 10 ? '0' : '') + n; }
    var parts = { yyyy: d.getFullYear(), MM: pad(d.getMonth() + 1), dd: pad(d.getDate()), HH: pad(d.getHours()), mm: pad(d.getMinutes()), ss: pad(d.getSeconds()) };
    return (info && info.dateFormat || 'dd-MM-yyyy HH:mm').replace(/yyyy|MM|dd|HH|mm|ss/g, function (k) { return parts[k]; });
  }

  var SVG_NS = 'http://www.w3.org/2000/svg';
  function icon(name, cls) {
    var svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'i' + (cls ? ' ' + cls : ''));
    var use = document.createElementNS(SVG_NS, 'use');
    use.setAttribute('href', '#i-' + name);
    svg.appendChild(use);
    return svg;
  }

  var FILE_ICONS = [
    [/\.(jpe?g|png|gif|webp|bmp|heic|heif|avif|svg)$/i, 'image'], [/\.(mp4|mkv|mov|webm|avi|3gp|m4v)$/i, 'video'],
    [/\.(mp3|m4a|aac|ogg|opus|wav|flac)$/i, 'audio'], [/\.(zip|7z|rar|gz|xz|bz2|tar)$/i, 'archive'], [/\.pdf$/i, 'pdf'],
    [/\.apk$/i, 'android'], [/\.(txt|md|log|csv|json|xml|srt|docx?|odt|xlsx?|ods|pptx?|odp)$/i, 'text']
  ];
  // Files the phone can make a preview for (keep in sync with Thumbnails.kt).
  var THUMB_TYPES = /\.(jpe?g|png|gif|webp|bmp|heic|heif|avif|mp3|m4a|aac|ogg|opus|flac|wav)$/i;
  var noThumb = new Set(); // files that had none, so they aren't asked for again on every render

  function iconFor(item) {
    if (item.d) return 'folder';
    for (var i = 0; i < FILE_ICONS.length; i++) if (FILE_ICONS[i][0].test(item.n)) return FILE_ICONS[i][1];
    return 'file';
  }

  // The phone's name (set in the app), shown instead of a generic "Storage".
  var deviceName = document.body.dataset.name || 'YAWTS';

  var toastTimer;
  function toast(text) {
    var el = $('toast');
    el.textContent = text;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 4000);
  }

  function errorText(code) { return STRINGS.en[code] ? t(code) : t('error'); }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  // ---------- API ----------

  function api(path, options) {
    options = options || {};
    options.headers = Object.assign({ 'X-Yawts': '1' }, options.headers || {});
    options.credentials = 'same-origin';
    return fetch(API + path, options).then(function (res) {
      if (res.status === 401) { showLogin(); throw new Error('auth'); }
      return res;
    }, function (e) { toast(t('offline')); throw e; });
  }

  function post(path, params) {
    var body = new URLSearchParams();
    Object.keys(params).forEach(function (k) {
      [].concat(params[k]).forEach(function (v) { body.append(k, v); });
    });
    return api(path, { method: 'POST', body: body }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (data) {
        if (!res.ok) { toast(errorText(data.error)); throw new Error(data.error); }
        return data;
      });
    });
  }

  // ---------- Views ----------

  function showLogin() {
    $('browseView').hidden = true;
    $('search').hidden = true;
    $('loginView').hidden = false;
    $('password').focus();
  }

  $('loginView').addEventListener('submit', function (e) {
    e.preventDefault();
    var body = new URLSearchParams({ password: $('password').value });
    fetch(API + 'login', { method: 'POST', body: body, credentials: 'same-origin' }).then(function (res) {
      if (res.ok) {
        $('loginView').hidden = true;
        $('loginError').hidden = true;
        $('password').value = '';
        load();
      } else {
        $('loginError').hidden = false;
      }
    }, function () { toast(t('offline')); });
  });

  $('logoutBtn').addEventListener('click', function () {
    api('logout', { method: 'POST' }).then(showLogin, function () {});
  });

  function load() {
    var path = currentPath();
    return api('list?path=' + encodeURIComponent(path)).then(function (res) {
      if (res.status === 404 && path) { go(''); return; }
      if (!res.ok) { toast(t('error')); return; }
      return res.json().then(function (data) {
        var changedFolder = !info || info.path !== data.path;
        info = data;
        if (changedFolder) { selected.clear(); $('search').value = ''; }
        else selected.forEach(function (n) { if (!data.items.some(function (i) { return i.n === n; })) selected.delete(n); });
        $('loginView').hidden = true;
        $('browseView').hidden = false;
        $('search').hidden = false;
        $('logoutBtn').hidden = !data.auth;
        $('deleteBtn').hidden = !data.canDelete;
        render();
      });
    }, function () {});
  }

  function sortedItems() {
    var filter = $('search').value.trim().toLowerCase();
    var items = info.items.filter(function (i) {
      return !filter || i.n.toLowerCase().indexOf(filter) >= 0;
    });
    var collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });
    var dir = sort.desc ? -1 : 1;
    items.sort(function (a, b) {
      if (a.d !== b.d) return b.d - a.d; // folders first
      var r = sort.key === 'size' ? a.s - b.s : sort.key === 'date' ? a.m - b.m : 0;
      return (r || collator.compare(a.n, b.n)) * dir;
    });
    return items;
  }

  function render() {
    renderCrumbs();
    var list = $('list');
    var items = sortedItems();
    var frag = document.createDocumentFragment();

    items.forEach(function (item) {
      var rel = join(info.path, item.n);
      var li = el('li', 'row' + (selected.has(item.n) ? ' sel' : ''));
      li.dataset.name = item.n;

      var check = el('span', 'c-check');
      var box = el('input');
      box.type = 'checkbox';
      box.checked = selected.has(item.n);
      check.appendChild(box);

      var name = el('span', 'c-name');
      var link = el('a');
      link.href = item.d ? '#/' + encodePath(rel) : fileUrl(rel);
      if (!item.d) { link.target = '_blank'; link.rel = 'noopener'; }
      var kind = iconFor(item);
      var thumbKey = rel + '@' + item.m;
      if (info.thumbs && !item.d && THUMB_TYPES.test(item.n) && !noThumb.has(thumbKey)) {
        link.appendChild(thumbnail(rel, item, kind, thumbKey));
      } else {
        link.appendChild(icon(kind, 'ico ico-' + kind));
      }
      link.appendChild(el('span', 'nm', item.n));
      name.appendChild(link);
      name.appendChild(el('span', 'meta', formatDate(item.m) + (item.d ? '' : ' · ' + formatSize(item.s))));

      var act = el('span', 'c-act');
      if (!item.d) {
        var dl = el('a', 'btn icon small');
        dl.appendChild(icon('download'));
        dl.href = fileUrl(rel) + '?dl=1';
        dl.title = t('download');
        dl.setAttribute('download', item.n);
        act.appendChild(dl);
      }

      li.appendChild(check);
      li.appendChild(name);
      li.appendChild(el('span', 'c-date', formatDate(item.m)));
      li.appendChild(el('span', 'c-size', item.d ? '' : formatSize(item.s)));
      li.appendChild(act);
      frag.appendChild(li);
    });

    list.textContent = '';
    list.appendChild(frag);

    var empty = $('empty');
    empty.hidden = items.length > 0;
    empty.textContent = info.items.length ? t('noMatch') : t('empty');

    document.querySelectorAll('.sort').forEach(function (b) {
      var active = b.dataset.sort === sort.key;
      b.classList.toggle('active', active);
      b.textContent = t(b.dataset.sort);
      if (active) b.appendChild(icon(sort.desc ? 'down' : 'up', 'small'));
    });

    $('summary').textContent = t('items', { n: info.items.length }) + ' · ' +
      t('free', { free: formatSize(info.free), total: formatSize(info.total) });
    updateSelection();
  }

  // Loaded only once scrolled into view; falls back to the plain icon if there's no preview.
  function thumbnail(rel, item, kind, key) {
    var img = el('img', 'thumb');
    img.alt = '';
    img.loading = 'lazy';
    img.decoding = 'async';
    img.src = API + 'thumb?path=' + encodeURIComponent(rel) + '&v=' + item.m;
    img.onerror = function () {
      noThumb.add(key);
      img.replaceWith(icon(kind, 'ico ico-' + kind));
    };
    return img;
  }

  function renderCrumbs() {
    var nav = $('crumbs');
    nav.textContent = '';
    var home = el('a', 'crumb');
    home.appendChild(icon('home'));
    home.appendChild(document.createTextNode(deviceName));
    home.href = '#/';
    nav.appendChild(home);
    var acc = '';
    info.path.split('/').filter(Boolean).forEach(function (seg) {
      acc = join(acc, seg);
      nav.appendChild(icon('chevron', 'sep'));
      var a = el('a', 'crumb', seg);
      a.href = '#/' + encodePath(acc);
      nav.appendChild(a);
    });
    document.title = (info.path ? info.path.split('/').pop() + ' · ' : '') + deviceName + ' · YAWTS';
  }

  function updateSelection() {
    var n = selected.size;
    var visible = $('list').childElementCount;
    // Always shown (also as 0), so nothing below moves when the first item gets selected.
    $('selCount').textContent = t('selected', { n: n });
    $('zipBtn').disabled = n === 0;
    $('renameBtn').disabled = n !== 1;
    $('deleteBtn').disabled = n === 0;
    $('selectAll').checked = n > 0 && n >= visible;
    $('selectAll').indeterminate = n > 0 && n < visible;
  }

  function setSelected(name, on) {
    if (on) selected.add(name); else selected.delete(name);
    var row = document.querySelector('#list .row[data-name="' + CSS.escape(name) + '"]');
    if (row) { row.classList.toggle('sel', on); row.querySelector('input').checked = on; }
    updateSelection();
  }

  // Tapping a row (anywhere but its links) toggles it - much easier than hitting a checkbox on a phone.
  $('list').addEventListener('click', function (e) {
    var row = e.target.closest('.row');
    if (!row || e.target.closest('a')) return;
    var name = row.dataset.name;
    var on = e.target.tagName === 'INPUT' ? e.target.checked : !selected.has(name);
    setSelected(name, on);
  });

  // Touches each row once - going through setSelected() would search the whole list per row.
  $('selectAll').addEventListener('change', function () {
    var on = $('selectAll').checked;
    document.querySelectorAll('#list .row').forEach(function (row) {
      if (on) selected.add(row.dataset.name); else selected.delete(row.dataset.name);
      row.classList.toggle('sel', on);
      row.querySelector('input').checked = on;
    });
    updateSelection();
  });

  document.querySelectorAll('.sort').forEach(function (b) {
    b.addEventListener('click', function () {
      if (sort.key === b.dataset.sort) sort.desc = !sort.desc; else { sort.key = b.dataset.sort; sort.desc = false; }
      store('yawts-sort', sort.key);
      store('yawts-desc', sort.desc ? '1' : '0');
      render();
    });
  });

  $('search').addEventListener('input', render);

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && selected.size) {
      selected.clear();
      render();
    }
  });

  // ---------- Actions ----------

  function selectedPaths() {
    return Array.from(selected).map(function (n) { return join(info.path, n); });
  }

  $('zipBtn').addEventListener('click', function () {
    // A POSTed form (not a URL) so any number of items fits; the response downloads as a file.
    var form = el('form');
    form.method = 'POST';
    form.action = API + 'zip';
    selectedPaths().forEach(function (p) {
      var input = el('input');
      input.type = 'hidden';
      input.name = 'p';
      input.value = p;
      form.appendChild(input);
    });
    document.body.appendChild(form);
    form.submit();
    form.remove();
  });

  $('mkdirBtn').addEventListener('click', function () {
    var name = prompt(t('folderName'));
    if (!name) return;
    post('mkdir', { path: info.path, name: name }).then(load, function () {});
  });

  $('renameBtn').addEventListener('click', function () {
    var old = Array.from(selected)[0];
    var name = prompt(t('newName'), old);
    if (!name || name === old) return;
    post('rename', { path: join(info.path, old), name: name }).then(function () {
      selected.clear();
      load();
    }, function () {});
  });

  $('deleteBtn').addEventListener('click', function () {
    if (!selected.size || !confirm(t('deleteConfirm', { n: selected.size }))) return;
    post('delete', { p: selectedPaths() }).then(function (data) {
      if (data.failed && data.failed.length) toast(t('deleteFailed', { names: data.failed.join(', ') }));
      selected.clear();
      load();
    }, function () {});
  });

  $('themeBtn').addEventListener('click', function () {
    var order = ['', 'light', 'dark'];
    var next = order[(order.indexOf(document.documentElement.dataset.theme || '') + 1) % order.length];
    if (next) document.documentElement.dataset.theme = next; else delete document.documentElement.dataset.theme;
    store('yawts-theme', next);
    updateThemeIcon();
  });

  function updateThemeIcon() {
    var mode = document.documentElement.dataset.theme || 'auto';
    $('themeBtn').querySelector('use').setAttribute('href', '#i-theme-' + mode);
  }
  updateThemeIcon();

  // ---------- Upload ----------

  var queue = [];
  var current = null;
  var stats = { done: 0, failed: 0, total: 0, bytesTotal: 0, bytesDone: 0, started: 0 };

  function enqueue(entries) {
    if (!entries.length || !info) return;
    if (!current) stats = { done: 0, failed: 0, total: 0, bytesTotal: 0, bytesDone: 0, started: Date.now() };
    entries.forEach(function (e) {
      queue.push({ file: e.file, name: e.name, dir: info.path });
      stats.total++;
      stats.bytesTotal += e.file.size;
    });
    $('uploads').hidden = false;
    if (!current) next();
  }

  function next() {
    var job = queue.shift();
    if (!job) return finish();
    current = job;
    var xhr = new XMLHttpRequest();
    job.xhr = xhr;
    xhr.open('PUT', API + 'upload?path=' + encodeURIComponent(job.dir) + '&name=' + encodeURIComponent(job.name) +
      '&mtime=' + (job.file.lastModified || 0));
    xhr.setRequestHeader('X-Yawts', '1');
    xhr.upload.onprogress = function (e) { progress(e.loaded); };
    xhr.onload = function () {
      if (xhr.status === 401) { queue = []; current = null; $('uploads').hidden = true; showLogin(); return; }
      if (xhr.status >= 200 && xhr.status < 300) stats.done++;
      else {
        stats.failed++;
        try { toast(errorText(JSON.parse(xhr.responseText).error)); } catch (e) { toast(t('error')); }
      }
      stats.bytesDone += job.file.size;
      next();
    };
    xhr.onerror = function () { stats.failed++; stats.bytesDone += job.file.size; next(); };
    xhr.onabort = function () { current = null; finish(); };
    progress(0);
    xhr.send(job.file);
  }

  function progress(loaded) {
    var sent = stats.bytesDone + loaded;
    var pct = stats.bytesTotal ? Math.floor(sent * 100 / stats.bytesTotal) : 100;
    var seconds = Math.max((Date.now() - stats.started) / 1000, 0.5);
    $('progressBar').style.width = pct + '%';
    $('uploadText').textContent = t('uploading', {
      done: Math.min(stats.done + stats.failed + 1, stats.total), total: stats.total,
      name: current ? current.name : '', pct: pct, speed: formatSize(sent / seconds)
    });
  }

  function finish() {
    current = null;
    $('uploads').hidden = true;
    var msg = [];
    if (stats.done) msg.push(t('uploaded', { n: stats.done }));
    if (stats.failed) msg.push(t('uploadFailed', { n: stats.failed }));
    if (msg.length) toast(msg.join(' '));
    load();
  }

  $('cancelBtn').addEventListener('click', function () {
    queue = [];
    if (current && current.xhr) current.xhr.abort();
  });

  window.addEventListener('beforeunload', function (e) {
    if (current) { e.preventDefault(); e.returnValue = ''; }
  });

  $('fileInput').addEventListener('change', function () {
    enqueue(Array.from(this.files).map(function (f) { return { file: f, name: f.name }; }));
    this.value = '';
  });

  $('dirInput').addEventListener('change', function () {
    enqueue(Array.from(this.files).map(function (f) { return { file: f, name: f.webkitRelativePath || f.name }; }));
    this.value = '';
  });

  // Drag & drop, including whole folders.
  var dragDepth = 0;
  function hasFiles(e) { return e.dataTransfer && Array.prototype.indexOf.call(e.dataTransfer.types, 'Files') >= 0; }

  document.addEventListener('dragenter', function (e) {
    if (!hasFiles(e)) return;
    e.preventDefault();
    dragDepth++;
    $('drop').classList.add('dragging');
  });
  document.addEventListener('dragleave', function () {
    if (--dragDepth <= 0) { dragDepth = 0; $('drop').classList.remove('dragging'); }
  });
  document.addEventListener('dragover', function (e) { if (hasFiles(e)) e.preventDefault(); });
  document.addEventListener('drop', function (e) {
    if (!hasFiles(e)) return;
    e.preventDefault();
    dragDepth = 0;
    $('drop').classList.remove('dragging');
    if ($('browseView').hidden) return;

    // Entries have to be taken synchronously - the DataTransfer is emptied once the handler returns.
    var entries = Array.prototype.map.call(e.dataTransfer.items || [], function (item) {
      return item.webkitGetAsEntry ? item.webkitGetAsEntry() : null;
    }).filter(Boolean);
    if (!entries.length) {
      enqueue(Array.from(e.dataTransfer.files).map(function (f) { return { file: f, name: f.name }; }));
      return;
    }
    var out = [];
    entries.reduce(function (p, entry) {
      return p.then(function () { return walk(entry, '', out); });
    }, Promise.resolve()).then(function () { enqueue(out); });
  });

  function walk(entry, prefix, out) {
    if (entry.isFile) {
      return new Promise(function (resolve) {
        entry.file(function (f) { out.push({ file: f, name: prefix + f.name }); resolve(); }, function () { resolve(); });
      });
    }
    if (!entry.isDirectory) return Promise.resolve();
    var reader = entry.createReader();
    var dir = prefix + entry.name + '/';
    function readBatch() {
      return new Promise(function (resolve) { reader.readEntries(resolve, function () { resolve([]); }); })
        .then(function (batch) {
          if (!batch.length) return;
          return batch.reduce(function (p, child) {
            return p.then(function () { return walk(child, dir, out); });
          }, Promise.resolve()).then(readBatch);
        });
    }
    return readBatch();
  }

  // ---------- Start ----------

  window.addEventListener('hashchange', load);
  load();
})();
