package io.github.qymartin.crosssync.garmin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.qymartin.crosssync.core.Settings
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/**
 * Shows Garmin's own sign-in page. Password and MFA code are typed into Garmin's
 * page, never into CrossSync; we only catch the service ticket it redirects to.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var handled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        status = TextView(this).apply {
            textSize = 18f
            setPadding(48, 48, 48, 48)
            text = "正在完成登录…"
            visibility = TextView.GONE
        }
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(status)
        })

        CookieManager.getInstance().removeAllCookies(null)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                onUrl(request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                onUrl(Uri.parse(url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(TICKET_HOOK, null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (ticketOf(request.url) == null) return null
                runOnUiThread { onUrl(request.url) }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        // Backup path: if the page returns the ticket in a JSON response instead of
        // redirecting, the injected hook below passes it to us.
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun ticket(t: String) {
                if (t.startsWith("ST-")) runOnUiThread { onTicket(t) }
            }
        }, "CrossSync")
        web.loadUrl(GarminConfig.signInUrl)
    }

    private fun ticketOf(uri: Uri): String? =
        uri.getQueryParameter("ticket")?.takeIf { it.startsWith("ST-") }

    /** Returns true when the URL carried the ticket (so the WebView should not load it). */
    private fun onUrl(uri: Uri): Boolean {
        val ticket = ticketOf(uri) ?: return false
        onTicket(ticket)
        return true
    }

    private fun onTicket(ticket: String) {
        if (handled) return
        handled = true
        web.stopLoading()
        web.visibility = WebView.GONE
        status.visibility = TextView.VISIBLE
        lifecycleScope.launch {
            try {
                GarminAuth(this@LoginActivity).exchangeTicket(ticket)
                Settings(this@LoginActivity).displayName = null
                GarminSource(this@LoginActivity).displayName()
                setResult(RESULT_OK)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "登录失败：${e.message}", Toast.LENGTH_LONG).show()
                handled = false
                status.visibility = TextView.GONE
                web.visibility = WebView.VISIBLE
                web.loadUrl(GarminConfig.signInUrl)
            }
        }
    }

    private companion object {
        /** Watches fetch/XHR responses for a "serviceTicketId" and reports it. */
        const val TICKET_HOOK = """
            (function () {
              if (window.__crossSyncHook) return; window.__crossSyncHook = true;
              function check(t) {
                var m = /"serviceTicketId"\s*:\s*"(ST-[^"]+)"/.exec(t || "");
                if (m && window.CrossSync) CrossSync.ticket(m[1]);
              }
              var f = window.fetch;
              if (f) window.fetch = function () {
                return f.apply(this, arguments).then(function (r) {
                  try { r.clone().text().then(check); } catch (e) {}
                  return r;
                });
              };
              var send = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.send = function () {
                this.addEventListener("load", function () { try { check(this.responseText); } catch (e) {} });
                return send.apply(this, arguments);
              };
            })();
        """
    }
}
