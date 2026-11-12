// Errors in the page go to the app's own output (stderr), where they can be seen without the web inspector.
(() => {
  const send = (m) => window.__TAURI__?.core.invoke('log', { msg: String(m) }).catch(() => {});
  addEventListener('error', (e) => send(`${e.message} at ${e.filename}:${e.lineno}`));
  addEventListener('unhandledrejection', (e) => send(`unhandled: ${e.reason?.message || e.reason} ${e.reason?.stack || ""}`));
  const err = console.error;
  console.error = (...a) => { err(...a); send(a.join(' ')); };
})();
