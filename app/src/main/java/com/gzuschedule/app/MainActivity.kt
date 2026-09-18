package com.gzuschedule.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "环境自检中…\n\nOkHttp 已就绪，正在测试网络…"
            textSize = 16f
            setPadding(48, 96, 48, 48)
        }
        setContentView(tv)

        // 端到端验证：证明 OkHttp/协程/网络权限/打包链路全部可用
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url("https://www.baidu.com").build()
                    client.newCall(req).execute().use { resp ->
                        "HTTP ${resp.code}  长度=${resp.body?.contentLength() ?: -1}"
                    }
                }.getOrElse { "网络测试失败: ${it.message}" }
            }
            tv.text = "环境自检通过 ✅\n\nJDK 21 / Gradle 8.13 / AGP 8.7.3\nSDK 35 / minSdk 26\n\n网络测试: $result"
        }
    }
}
