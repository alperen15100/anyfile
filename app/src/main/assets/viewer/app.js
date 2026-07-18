"use strict";

var vwParams = new URLSearchParams(location.search);
var vwName = vwParams.get("name") || "file";
var vwExt = (vwParams.get("ext") || "").toLowerCase();

function vwStatus(msg) {
  var el = document.getElementById("vw-status");
  if (!el) {
    el = document.createElement("div");
    el.id = "vw-status";
    el.className = "vw-status";
    document.body.insertBefore(el, document.body.firstChild);
  }
  el.className = "vw-status";
  el.textContent = msg;
  el.style.display = "";
}

function vwStatusDone() {
  var el = document.getElementById("vw-status");
  if (el) el.style.display = "none";
}

function vwError(title, detail) {
  var el = document.getElementById("vw-status");
  if (!el) {
    el = document.createElement("div");
    el.id = "vw-status";
    document.body.insertBefore(el, document.body.firstChild);
  }
  el.className = "vw-status vw-error";
  el.style.display = "";
  el.innerHTML = "";
  var t = document.createElement("div");
  t.className = "vw-error-title";
  t.textContent = title;
  var d = document.createElement("div");
  d.className = "vw-error-detail";
  d.textContent = detail || "";
  el.appendChild(t);
  el.appendChild(d);
}

/* Fetch the document being viewed. kind: "buffer" | "text" */
function vwFetchDoc(kind) {
  var url = "/doc/file" + (vwExt ? "." + vwExt : "");
  return fetch(url).then(function (r) {
    if (!r.ok) throw new Error("Could not read the file (HTTP " + r.status + ")");
    return kind === "text" ? r.text() : r.arrayBuffer();
  });
}

window.onerror = function (message) {
  vwError("Something went wrong while rendering", String(message));
};
