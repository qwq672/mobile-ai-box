package com.aibox.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * 在 Markdown 代码块中渲染 Mermaid 图（内置能力，非插件扩展）。
 *
 * 实现要点：
 * - mermaid.js 与 HTML 模板都打包在 assets 并内联进页面，完全离线可用；
 * - WebView 只渲染内联内容，禁用文件访问与任意外链，mermaid securityLevel=strict；
 * - 主题色由 Compose 侧注入（适配中性黑白主题：节点灰阶、文字近黑/近白）；
 * - 通过 JS interface 回传渲染后的 SVG 高度，让 Compose 按内容分配高度。
 */
@Composable
fun MermaidBlock(code: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var heightPx by remember(code) { mutableStateOf(160) }

    val isDark = isSystemInDarkTheme()
    val themeJson = remember(isDark) {
        val fg = if (isDark) "#E6E6E6" else "#1A1A1A"
        val line = if (isDark) "#9E9E9E" else "#4A4A4A"
        val nodeFill = if (isDark) "#2A2A2A" else "#F0F0F0"
        val nodeBorder = "#757575"
        val clusterBg = if (isDark) "#242424" else "#F7F7F7"
        JSONObject().apply {
            put("primaryColor", nodeFill)
            put("primaryTextColor", fg)
            put("primaryBorderColor", nodeBorder)
            put("lineColor", line)
            put("secondaryColor", clusterBg)
            put("tertiaryColor", clusterBg)
            put("background", "transparent")
            put("mainBkg", nodeFill)
            put("nodeBorder", nodeBorder)
            put("clusterBkg", clusterBg)
            put("clusterBorder", line)
            put("titleColor", fg)
            put("edgeLabelBackground", clusterBg)
            put("fontFamily", "sans-serif")
            put("fontSize", "14px")
        }.toString()
    }

    // 读取 assets：模板 + mermaid.js，把外链脚本替换为内联脚本（全程无 file:// 访问）
    val pageHtml = remember {
        runCatching {
            val tpl = context.assets.open("mermaid_template.html").bufferedReader().use { it.readText() }
            val js = context.assets.open("mermaid.min.js").bufferedReader().use { it.readText() }
            tpl.replace(
                "<script src=\"mermaid.min.js\"></script>",
                buildString { append("<script>").append(js).append("</script>") }
            )
        }.getOrElse { e ->
            "<html><body><div id=\"err\" style=\"display:block;color:#d32f2f\">Mermaid 引擎加载失败：${e.message}</div><div id=\"chart\"></div></body></html>"
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(heightPx.dp),
        factory = { ctx: Context ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(Color.TRANSPARENT)
                settings.apply {
                    @SuppressLint("SetJavaScriptEnabled")
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    setGeolocationEnabled(false)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = true // 拦截一切外链跳转
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (view != null) renderMermaid(view, code, themeJson)
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onHeight(h: String) {
                        val px = h.toIntOrNull()?.coerceIn(80, 2000) ?: return
                        post { heightPx = px }
                    }
                }, "AiboxHeight")
                loadDataWithBaseURL(null, pageHtml, "text/html", "utf-8", null)
            }
        },
        update = { view: WebView -> renderMermaid(view, code, themeJson) }
    )
}

private fun renderMermaid(view: WebView, code: String, themeJson: String) {
    // 字符串统一走 JSON 编码，确保换行/引号安全转义为 JS 字面量
    val codeLiteral = Json.encodeToString(code)
    // themeJson 本身是 JSON 文本，作为 JS 字符串再编码一次
    val themeLiteral = Json.encodeToString(themeJson)
    val script = """
        (function(){
          if(typeof mermaid==='undefined'){
            var d=document.getElementById('err');
            if(d){d.style.display='block';d.textContent='Mermaid 引擎未加载';}
            return;
          }
          try{
            mermaid.initialize({
              startOnLoad:false, securityLevel:'strict', theme:'base',
              themeVariables:JSON.parse($themeLiteral),
              flowchart:{useMaxWidth:true,htmlLabels:true,curve:'basis'},
              sequence:{useMaxWidth:true}, gantt:{useMaxWidth:true}
            });
            mermaid.render('aiboxMermaid', $codeLiteral).then(function(res){
              var c=document.getElementById('chart');
              c.innerHTML=res.svg;
              var node=c.querySelector('svg');
              if(node && window.AiboxHeight){
                window.AiboxHeight.onHeight(String(Math.ceil(node.getBoundingClientRect().height)+8));
              }
            }).catch(function(e){
              var d=document.getElementById('err');
              if(d){d.style.display='block';d.textContent='图表语法错误：'+(e&&e.message?e.message:e);}
            });
          }catch(t){
            var d=document.getElementById('err');
            if(d){d.style.display='block';d.textContent='渲染失败：'+(t&&t.message?t.message:t);}
          }
        })();
    """.trimIndent()
    view.evaluateJavascript(script, null)
}
