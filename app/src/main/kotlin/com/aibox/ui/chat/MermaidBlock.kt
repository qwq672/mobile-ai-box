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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * 通用 mermaid 引擎在真机 WebView 上实测不可用（内联脚本未执行，15 种图里 14 种报
 * "Mermaid 引擎未加载"）。 Mindmap 已由 [MindMapCanvas] 自研渲染，能走到这里的都是
 * 引擎不支持的类型。为避免红字报错污染回答，降级为**原生源码展示**：保留 mermaid
 * 源码（用户仍可读懂/复制），顶部给一行轻提示，而不是 WebView 报错。
 *
 * 无 WebView、无 JS、无外链，彻底消除竞态与崩溃面。
 */
@Composable
fun MermaidFallback(code: String, modifier: Modifier = Modifier) {
    val kind = code.trimStart().lineSequence().firstOrNull().orEmpty()
        .removePrefix("%%").trim().ifBlank { "mermaid" }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp)
    ) {
        Text(
            text = "$kind · 源码（当前设备暂不支持图形渲染）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        )
    }
}

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
    // 渲染去重：onHeight 回传高度会改变 heightPx → 触发重组 → update 再次渲染，
    // 形成"渲染→改高度→再渲染"的循环竞态。用 key 只在 code/主题变化时渲染。
    var lastRenderKey by remember { mutableStateOf<String?>(null) }

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
        update = { view: WebView ->
            // factory 中的 onPageFinished 会渲染一次；这里只处理 code/主题在运行期变化的情况。
            val key = code + "|" + themeJson
            if (lastRenderKey == null) {
                lastRenderKey = key
            } else if (lastRenderKey != key) {
                lastRenderKey = key
                renderMermaid(view, code, themeJson)
            }
        }
    )
}

private fun renderMermaid(view: WebView, code: String, themeJson: String) {
    // 字符串统一走 JSON 编码，确保换行/引号安全转义为 JS 字面量
    val codeLiteral = Json.encodeToString(code)
    // themeJson 本身是 JSON 文本，作为 JS 字符串再编码一次
    val themeLiteral = Json.encodeToString(themeJson)
    // 每次渲染用唯一 id：mermaid.render() 会在 document 里临时插入同名元素，
    // 固定 id 在同一屏多图或重组时冲突，导致第二次渲染静默失败（图不显示）。
    val renderId = "aiboxMermaid_${System.nanoTime()}"
    val renderIdLiteral = Json.encodeToString(renderId)
    val script = """
        (function(){
          var c=document.getElementById('chart');
          var d=document.getElementById('err');
          function hideErr(){ if(d){d.style.display='none';d.textContent='';} }
          function showErr(msg){ if(d){d.style.display='block';d.textContent=msg;} }

          // 内联 mermaid.js 可能尚未执行完：轮询等待引擎就绪（最多 ~3s），
          // 避免误报"引擎未加载"。成功后 hideErr 清掉上一次的残留文案。
          var tries=0;
          function waitAndRender(){
            if(typeof mermaid==='undefined'){
              if(tries++ < 60){ setTimeout(waitAndRender, 50); }
              else { showErr('Mermaid 引擎未加载'); }
              return;
            }
            try{
              mermaid.initialize({
                startOnLoad:false, securityLevel:'strict', theme:'base',
                themeVariables:JSON.parse($themeLiteral),
                flowchart:{useMaxWidth:true,htmlLabels:true,curve:'basis'},
                sequence:{useMaxWidth:true}, gantt:{useMaxWidth:true}
              });
              if(c){c.innerHTML='';}
              mermaid.render($renderIdLiteral, $codeLiteral).then(function(res){
                hideErr();
                if(!c){return;}
                c.innerHTML=res.svg;
                var node=c.querySelector('svg');
                if(node){
                  node.style.maxWidth='100%';
                  node.style.height='auto';
                  if(window.AiboxHeight){
                    window.AiboxHeight.onHeight(String(Math.ceil(node.getBoundingClientRect().height)+8));
                  }
                }
              }).catch(function(e){
                showErr('图表语法错误：'+(e&&e.message?e.message:e));
              });
            }catch(t){
              showErr('渲染失败：'+(t&&t.message?t.message:t));
            }
          }
          waitAndRender();
        })();
    """.trimIndent()
    view.evaluateJavascript(script, null)
}
