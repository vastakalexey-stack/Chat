package com.aiduo.app

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val Bg = Color(0xFF0B1020)
private val Panel = Color(0xFF151D31)
private val Blue = Color(0xFF4C8DFF)
private val Green = Color(0xFF39B98A)
private val TextMain = Color(0xFFEAF0FF)
private val Muted = Color(0xFFAAB6D0)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ru", "RU")
        }
        setContent {
            AiDuoScreen(onSpeak = { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "aiduo") })
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

data class ChatLine(val who: String, val text: String)

@Composable
fun AiDuoScreen(onSpeak: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember { OkHttpClient() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pageContext by remember { mutableStateOf("") }
    val browserView = remember { mutableStateOf<WebView?>(null) }
    val history = remember { mutableStateListOf<Pair<String, String>>() }
    val lines = remember {
        mutableStateListOf(ChatLine("AI Duo", "Привет! Я объединяю YandexGPT и GigaChat. Напиши вопрос или нажми на микрофон."))
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = spoken
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Panel, primary = Blue, secondary = Green,
        onBackground = TextMain, onSurface = TextMain
    )) {
        Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI DUO", color = TextMain, fontSize = 24.sp)
                    Text("Два интеллекта. Одна команда.", color = Muted, fontSize = 12.sp)
                }
                Surface(color = Color(0xFF173B65), shape = RoundedCornerShape(50)) {
                    Text("TEAM MODE", color = TextMain, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiChip("YandexGPT", Blue, Modifier.weight(1f))
                AiChip("GigaChat", Green, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            BrowserPanel(webViewState = browserView, onPageCaptured = { pageContext = it })
            if (pageContext.isNotBlank()) {
                Text("Страница загружена для анализа Яндексом", color = Green, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(lines) { line -> MessageCard(line) }
                if (busy) item { Text("YandexGPT и GigaChat обсуждают вопрос…", color = Muted, fontSize = 13.sp) }
            }
            error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 12.sp) }
            Surface(color = Panel, shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите ваш вопрос")
                        }
                        try { speechLauncher.launch(intent) }
                        catch (_: Exception) { error = "На устройстве не найден сервис распознавания речи." }
                    }) {
                        Icon(Icons.Default.Mic, contentDescription = "Голосовой ввод", tint = Green)
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        textStyle = TextStyle(color = TextMain, fontSize = 15.sp),
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) Text("Спроси обоих ИИ…", color = Muted)
                                inner()
                            }
                        }
                    )
                    IconButton(enabled = input.isNotBlank() && !busy, onClick = {
                        val question = input.trim()
                        input = ""
                        error = null
                        lines.add(ChatLine("Ты", question))
                        history.add("user" to question)
                        busy = true
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val historyJson = JSONArray()
                                    history.forEach { (role, text) ->
                                        historyJson.put(JSONObject().put("role", role).put("content", text))
                                    }
                                    val body = JSONObject()
                                        .put("message", question)
                                        .put("history", historyJson)
                                        .put("page_context", pageContext.take(20000))
                                    val request = Request.Builder()
                                        .url(BuildConfig.API_BASE_URL + "api/team-chat")
                                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                                        .build()
                                    client.newCall(request).execute().use { response ->
                                        val raw = response.body?.string().orEmpty()
                                        if (!response.isSuccessful) {
                                            val message = try { JSONObject(raw).optString("error") } catch (_: Exception) { "Ошибка сервера" }
                                            throw Exception(message)
                                        }
                                        JSONObject(raw)
                                    }
                                }
                                lines.add(ChatLine("YandexGPT", result.optString("yandexgpt")))
                                lines.add(ChatLine("GigaChat", result.optString("gigachat")))
                                val joint = result.optString("joint")
                                val sources = result.optJSONArray("sources") ?: JSONArray()
                                val sourceText = (0 until sources.length()).mapNotNull { index ->
                                    sources.optJSONObject(index)?.let { source ->
                                        val title = source.optString("title").ifBlank { source.optString("url") }
                                        val url = source.optString("url")
                                        if (url.isNotBlank()) "• ${title} — ${url}" else null
                                    }
                                }.joinToString("\n")
                                val finalText = if (sourceText.isNotBlank()) "${joint}\n\nИсточники:\n${sourceText}" else joint
                                lines.add(ChatLine("Общий вывод", finalText))
                                history.add("assistant" to finalText)
                                pageContext = ""
                                onSpeak(finalText)
                            } catch (e: Exception) {
                                error = e.message ?: "Не удалось связаться с AI Duo."
                            } finally {
                                busy = false
                            }
                        }
                    }) {
                        Icon(Icons.Default.Send, contentDescription = "Отправить", tint = if (input.isNotBlank() && !busy) Blue else Muted)
                    }
                }
            }
            Text("Для работы требуется настроенный сервер AI Duo.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun BrowserPanel(
    webViewState: androidx.compose.runtime.MutableState<WebView?>,
    onPageCaptured: (String) -> Unit
) {
    var address by remember { mutableStateOf("https://yandex.ru") }
    var pageLoading by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Встроенный браузер AI Duo", color = TextMain, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f).background(Bg, RoundedCornerShape(10.dp)).padding(10.dp),
                textStyle = TextStyle(color = TextMain, fontSize = 13.sp),
                decorationBox = { inner ->
                    Box {
                        if (address.isBlank()) Text("Адрес сайта", color = Muted)
                        inner()
                    }
                }
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                val raw = address.trim()
                val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                val uri = runCatching { Uri.parse(normalized) }.getOrNull()
                if (uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) {
                    address = normalized
                    webViewState.value?.loadUrl(normalized)
                }
            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("Перейти") }
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(190.dp).background(Color.White, RoundedCornerShape(10.dp)),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            pageLoading = true
                            if (!url.isNullOrBlank()) address = url
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageLoading = false
                            if (!url.isNullOrBlank()) address = url
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            val uri = runCatching { Uri.parse(url ?: "") }.getOrNull()
                            return uri == null || uri.scheme !in listOf("http", "https")
                        }
                    }
                    webViewState.value = this
                    loadUrl("https://yandex.ru")
                }
            },
            update = { webViewState.value = it }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (pageLoading) "Загрузка страницы…" else "Открой сайт и передай его содержимое Яндексу",
                color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                webViewState.value?.evaluateJavascript(
                    "(function(){return document.title+'\\n'+(document.body?document.body.innerText:'');})()"
                ) { raw ->
                    val decoded = runCatching { JSONArray("[$raw]").optString(0) }.getOrDefault("")
                    if (decoded.isNotBlank()) onPageCaptured(decoded.take(20000))
                }
            }, enabled = !pageLoading, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)) {
                Text("Передать Яндексу", fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun AiChip(name: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            Text(name, color = TextMain, fontSize = 13.sp)
        }
    }
}

@Composable
fun MessageCard(line: ChatLine) {
    val accent = when (line.who) {
        "YandexGPT" -> Blue
        "GigaChat" -> Green
        "Ты" -> Color(0xFFB7A1FF)
        else -> Color(0xFFFFC36B)
    }
    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(line.who, color = accent, fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            Text(line.text, color = TextMain, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
