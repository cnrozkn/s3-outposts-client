'use strict';

// Small helpers for the server-rendered pages. Reading works without JavaScript; writes need it.
(() => {
  const loading = document.getElementById('loading');
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
  const plainClick = (event) =>
    event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey;
  const showLoading = () => {
    if (loading) loading.hidden = false;
  };

  // Listing a large folder can take a few seconds; show at once that the click was taken.
  document.addEventListener('click', (event) => {
    if (event.target.closest('a[data-nav]') && plainClick(event)) showLoading();
  });
  document.addEventListener('submit', (event) => {
    if (event.target.matches('form[data-nav]')) showLoading();
  });
  window.addEventListener('pageshow', () => {
    if (loading) loading.hidden = true;
  });

  // On wide screens object details open in the side panel; a newer click cancels the older request.
  let detailRequest;
  document.addEventListener('click', async (event) => {
    const link = event.target.closest('a[data-meta]');
    if (!link || !plainClick(event) || matchMedia('(max-width: 1199.98px)').matches) return;
    event.preventDefault();
    detailRequest?.abort();
    const request = (detailRequest = new AbortController());
    document.querySelectorAll('tr.is-selected').forEach((row) => row.classList.remove('is-selected'));
    link.closest('tr').classList.add('is-selected');
    try {
      const response = await fetch(link.href, { headers: { Accept: 'text/html' }, signal: request.signal });
      const page = new DOMParser().parseFromString(await response.text(), 'text/html');
      const content = page.getElementById('meta');
      if (!content) {
        location.reload(); // for example the session expired and the login page came back
        return;
      }
      document.getElementById('meta').replaceWith(content);
    } catch (error) {
      if (error.name !== 'AbortError') throw error;
    }
  });

  // Version rows load under the file row when it is first opened and are removed when it is closed.
  document.addEventListener('click', async (event) => {
    const toggle = event.target.closest('button[data-versions]');
    if (!toggle || toggle.disabled) return;
    const icon = toggle.querySelector('i');
    if (toggle.getAttribute('aria-expanded') === 'true') {
      toggle.versionRows.forEach((row) => row.remove());
      toggle.setAttribute('aria-expanded', 'false');
      icon.className = 'bi bi-chevron-right';
      return;
    }
    toggle.disabled = true;
    let rows;
    try {
      const response = await fetch(toggle.dataset.versions, { headers: { Accept: 'text/html' } });
      const body = new DOMParser().parseFromString(await response.text(), 'text/html').getElementById('versions');
      if (!body && response.ok) {
        location.reload();
        return;
      }
      rows = body ? [...body.rows] : [messageRow(`Could not load versions (HTTP ${response.status}).`)];
    } catch {
      rows = [messageRow('Could not load versions (network error).')];
    } finally {
      toggle.disabled = false;
    }
    toggle.versionRows = rows;
    toggle.closest('tr').after(...rows);
    toggle.setAttribute('aria-expanded', 'true');
    icon.className = 'bi bi-chevron-down';
  });

  function messageRow(text) {
    const row = document.createElement('tr');
    row.className = 'version-row';
    const cell = row.insertCell();
    cell.colSpan = 4;
    cell.className = 'ps-5 pe-4 small text-danger';
    cell.textContent = text;
    return row;
  }

  // Writes: upload (also "Replace"), rename and delete. The server checks every rule again.
  const actions = document.getElementById('actions')?.dataset;
  if (!actions) return;
  const uploadInput = document.getElementById('upload-input');
  const status = document.getElementById('upload-status');

  async function keyExists(key) {
    const url = new URL(actions.existsUrl, location.href);
    url.searchParams.set('key', key);
    const response = await fetch(url, { headers: { Accept: 'application/json' } });
    // An expired session redirects to the login page, which answers 200 with HTML.
    if (response.redirected) throw new Error('the session expired, reload the page to sign in again');
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return (await response.json()).exists;
  }

  function report(text) {
    if (!status) return;
    status.hidden = false;
    status.textContent = text;
  }

  // The file goes as the raw request body, which the server streams straight into S3.
  function upload(file, key, overwrite) {
    return new Promise((resolve) => {
      const url = new URL(actions.uploadUrl, location.href);
      url.searchParams.set('key', key);
      url.searchParams.set('overwrite', String(overwrite));
      const xhr = new XMLHttpRequest();
      xhr.open('POST', url);
      xhr.setRequestHeader(csrfHeader, csrfToken);
      // A form or multipart type would make the server read the body as request parameters.
      const formLike = /^(multipart\/|application\/x-www-form-urlencoded)/i.test(file.type);
      xhr.setRequestHeader('Content-Type', file.type && !formLike ? file.type : 'application/octet-stream');
      xhr.upload.onprogress = (progress) => {
        if (progress.lengthComputable) {
          report(`Uploading ${file.name}: ${Math.floor((progress.loaded * 100) / progress.total)}%`);
        }
      };
      xhr.onload = () => resolve(xhr.status === 204 ? null : `HTTP ${xhr.status}`);
      xhr.onerror = () => resolve('network error');
      report(`Uploading ${file.name}…`);
      xhr.send(file);
    });
  }

  document.addEventListener('click', (event) => {
    const button = event.target.closest('[data-upload]');
    if (!button || !uploadInput) return;
    uploadInput.dataset.key = button.dataset.upload; // empty: upload into the current folder
    uploadInput.multiple = !button.dataset.upload;
    uploadInput.click();
  });

  uploadInput?.addEventListener('change', async () => {
    const files = [...uploadInput.files];
    const fixedKey = uploadInput.dataset.key;
    uploadInput.value = '';
    const failures = [];
    let uploaded = 0;
    for (const file of files) {
      const key = fixedKey || actions.prefix + file.name;
      if (file.size > Number(actions.maxSize)) {
        failures.push(`${file.name}: larger than the upload limit`);
        continue;
      }
      let exists;
      try {
        exists = await keyExists(key);
      } catch (error) {
        failures.push(`${file.name}: ${error.message}`);
        continue;
      }
      if (exists && !confirm(`"${key}" already exists. Replace it?`)) continue;
      const failure = await upload(file, key, exists);
      if (failure) failures.push(`${file.name}: ${failure}`);
      else uploaded++;
    }
    if (failures.length) alert(`Some uploads failed:\n${failures.join('\n')}`);
    if (uploaded) {
      showLoading();
      location.reload();
    } else if (status) {
      status.hidden = true;
    }
  });

  document.addEventListener('click', async (event) => {
    const button = event.target.closest('[data-rename]');
    if (!button) return;
    const key = button.dataset.rename;
    const target = prompt('New key. Use "/" to move the object into another folder.', key);
    if (!target || target === key) return;
    let exists;
    try {
      exists = await keyExists(target);
    } catch (error) {
      alert(`Could not check the new key: ${error.message}`);
      return;
    }
    if (exists && !confirm(`"${target}" already exists. Replace it?`)) return;
    const form = document.getElementById('rename-form');
    form.elements.key.value = key;
    form.elements.target.value = target;
    form.elements.overwrite.value = String(exists);
    showLoading();
    form.submit();
  });

  document.addEventListener('click', (event) => {
    const button = event.target.closest('[data-delete]');
    if (!button) return;
    const key = button.dataset.delete;
    if (!confirm(`Delete "${key}"?\nWith versioning on, S3 keeps the older versions and adds a delete marker.`)) return;
    const form = document.getElementById('delete-form');
    form.elements.key.value = key;
    showLoading();
    form.submit();
  });
})();
