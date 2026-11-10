package id.webdev.studio.developer

import androidx.compose.runtime.mutableStateListOf
import id.webdev.studio.data.JsMessage
import id.webdev.studio.data.LogLevel
import id.webdev.studio.data.LogLine
import id.webdev.studio.data.NetEntry

/**
 * Penampung log per proyek: Server Console, JavaScript Console, Network Monitor.
 * Daftar berupa SnapshotStateList agar UI Compose otomatis ter-update. Mutasi dilakukan di main thread.
 */
class ProjectLogs {
    val server = mutableStateListOf<LogLine>()
    val js = mutableStateListOf<JsMessage>()
    val net = mutableStateListOf<NetEntry>()

    companion object { const val MAX = 1500 }

    fun addServer(level: LogLevel, text: String) {
        server.add(LogLine(System.currentTimeMillis(), level, text))
        while (server.size > MAX) server.removeAt(0)
    }
    fun addJs(m: JsMessage) {
        js.add(m)
        while (js.size > MAX) js.removeAt(0)
    }
    fun addNet(n: NetEntry) {
        net.add(n)
        while (net.size > MAX) net.removeAt(0)
    }
}

object DevToolsBus {
    private val map = HashMap<String, ProjectLogs>()
    fun of(projectId: String): ProjectLogs = synchronized(map) { map.getOrPut(projectId) { ProjectLogs() } }
    fun drop(projectId: String) = synchronized(map) { map.remove(projectId) }
}

/**
 * Skrip yang disuntikkan ke setiap halaman (document start) untuk menangkap console.*,
 * error JS, error pemuatan resource, serta fetch/XHR untuk Network Monitor di sisi halaman.
 * Pesan dikirim ke objek Java `__wds`.
 */
object InjectedScripts {
    const val BRIDGE = "__wds"

    val MONITOR = """
(function(){
  if (window.__wdsInstalled) return; window.__wdsInstalled = true;
  if (location.protocol !== 'http:' || !/^(127\.0\.0\.1|localhost)$/.test(location.hostname)) return;
  var B = window.$BRIDGE; if (!B) return;
  window.addEventListener('error', function(e){
    if (e.target && e.target !== window && (e.target.src||e.target.href)) {
      B.resourceError(e.target.tagName||'', e.target.src||e.target.href);
    } else {
      B.error(e.error && e.error.name || 'Error', e.message||'', e.filename||'', e.lineno||0, (e.error && e.error.stack)||'');
    }
  }, true);
  window.addEventListener('unhandledrejection', function(e){
    var r = e.reason; B.error('UnhandledRejection', (r&&r.message)||String(r), '', 0, (r&&r.stack)||'');
  });
  var _fetch = window.fetch;
  if (_fetch) window.fetch = function(input, init){
    var url = (typeof input==='string')? input : (input&&input.url)||''; var m = (init&&init.method)||(input&&input.method)||'GET';
    var t0 = performance.now();
    return _fetch.apply(this, arguments).then(function(r){ B.net(m, r.url||url, r.status, Math.round(performance.now()-t0), false); return r; },
      function(err){ B.net(m, url, 0, Math.round(performance.now()-t0), true); throw err; });
  };
  var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m,u){ this.__m=m; this.__u=u; return XO.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(){
    var x=this, t0=performance.now();
    x.addEventListener('loadend', function(){ B.net(x.__m||'GET', x.responseURL||x.__u||'', x.status, Math.round(performance.now()-t0), x.status===0); });
    return XS.apply(this, arguments);
  };
})();
""".trimIndent()
}
