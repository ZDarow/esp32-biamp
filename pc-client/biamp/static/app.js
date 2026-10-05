"use strict";

const CHANNELS = ["LEFT НЧ", "LEFT ВЧ", "RIGHT НЧ", "RIGHT ВЧ"];
const state = { current: null, busy: false };

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

// Токен подставляет сервер в <head> при отдаче index.html. Без него
// /api/* отвечает 403, поэтому запросы без токена бесполезны.
const TOKEN = (document.querySelector('meta[name="biamp-token"]') || {}).content || "";

function showError(message) {
  const box = $("#error");
  box.textContent = message;
  box.hidden = !message;
}

function setLink(text, cls) {
  const badge = $("#link");
  badge.textContent = text;
  badge.className = "badge" + (cls ? " " + cls : "");
}

function format(field, value) {
  if (field === "balance" || field.endsWith("_db")) {
    return (value > 0 ? "+" : "") + Number(value).toFixed(1) + " дБ";
  }
  if (field === "crossover_hz" || field === "sub_hp_hz") {
    return Number(value).toFixed(0) + " Гц";
  }
  if (field === "test_volume") return value + " %";
  return String(value);
}

function showDupWarning(active) {
  const box = $("#dup-warn");
  if (box) box.hidden = !active;
}

function buildChannels(state) {
  const body = $("#channels tbody");
  body.textContent = "";
  state.filters.forEach((filt, i) => {
    const tr = document.createElement("tr");

    const name = document.createElement("td");
    name.textContent = CHANNELS[i];
    tr.append(name);

    const hp = document.createElement("td");
    const hpIn = document.createElement("input");
    hpIn.type = "number";
    hpIn.min = "0";
    hpIn.max = "20000";
    hpIn.step = "1";
    hpIn.value = filt.high_pass_hz;
    hpIn.dataset.field = "chhp" + i;
    hp.append(hpIn);
    tr.append(hp);

    const lp = document.createElement("td");
    const lpIn = document.createElement("input");
    lpIn.type = "number";
    lpIn.min = "0";
    lpIn.max = "20000";
    lpIn.step = "1";
    lpIn.value = filt.low_pass_hz;
    lpIn.dataset.field = "chlp" + i;
    lp.append(lpIn);
    tr.append(lp);

    const dl = document.createElement("td");
    const dlIn = document.createElement("input");
    dlIn.type = "number";
    dlIn.min = "0";
    dlIn.max = "220";
    dlIn.step = "1";
    dlIn.value = state.delays[i];
    dlIn.dataset.field = "delay" + i;
    dl.append(dlIn);
    tr.append(dl);

    body.append(tr);
  });
}

function fill(state) {
  state.current = state;
  $$("[data-field]").forEach((el) => {
    const field = el.dataset.field;
    if (!(field in state)) return;
    if (el.type === "checkbox") el.checked = Boolean(state[field]);
    else el.value = state[field];
    const out = $('[data-out="' + field + '"]');
    if (out) out.textContent = format(field, state[field]);
  });
  buildChannels(state);
  showDupWarning(Boolean(state.dup_out));
  setLink("подключено", "ok");
}

async function api(path, body) {
  const headers = { "X-BiAmp-Token": TOKEN };
  const options = body
    ? {
        method: "POST",
        headers: Object.assign({ "Content-Type": "application/json" }, headers),
        body: JSON.stringify(body),
      }
    : { headers };
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok || data.ok === false) {
    throw new Error(data.error || "HTTP " + response.status);
  }
  return data;
}

async function refresh() {
  if (state.busy) return;
  state.busy = true;
  try {
    const data = await api("/api/state");
    fill(data.state);
    showError("");
  } catch (err) {
    setLink("нет связи", "bad");
    showError("Не удалось прочитать состояние: " + err.message);
  } finally {
    state.busy = false;
  }
}

let debounce = null;
function pushField(field, value) {
  clearTimeout(debounce);
  debounce = setTimeout(async () => {
    try {
      const data = await api("/api/param", { name: field, value });
      fill(data.state);
      showError("");
    } catch (err) {
      showError(field + ": " + err.message);
      refresh();
    }
  }, 220);
}

document.addEventListener("input", (event) => {
  const el = event.target;
  const field = el.dataset.field;
  if (!field) return;
  const value = el.type === "checkbox" ? el.checked : el.value;
  if (field === "dup_out") showDupWarning(el.checked);
  const out = $('[data-out="' + field + '"]');
  if (out) out.textContent = format(field, value);
  pushField(field, value);
});

document.addEventListener("change", (event) => {
  const el = event.target;
  if (!el.dataset.field) return;
  const value = el.type === "checkbox" ? el.checked : el.value;
  if (el.dataset.field === "dup_out") showDupWarning(el.checked);
  pushField(el.dataset.field, value);
});

document.addEventListener("click", async (event) => {
  const button = event.target.closest("button");
  if (!button) return;

  if (button.id === "refresh") {
    refresh();
    return;
  }

  let command = button.dataset.cmd;
  if (command === "apply-test") {
    command = "test:" + $("#testmode").value;
  }
  if (!command) return;

  button.disabled = true;
  try {
    const data = await api("/api/command", { command });
    if (data.state) fill(data.state);
    showError("");
  } catch (err) {
    showError(command + ": " + err.message);
  } finally {
    button.disabled = false;
  }
});

if (!TOKEN) {
  showError("Токен доступа не подставлен: страница открыта не через сервер панели.");
  setLink("нет доступа", "bad");
} else {
  refresh();
}

setInterval(() => {
  if (!state.busy && TOKEN) refresh();
}, 15000);
