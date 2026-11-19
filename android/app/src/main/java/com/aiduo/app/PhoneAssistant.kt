package com.aiduo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.DocumentsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

class AiDuoNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}

private fun launchPhoneIntent(context: Context, intent: Intent): Boolean {
    return try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

private fun controlMusic(context: Context, command: String): String {
    return try {
        val listener = ComponentName(context, AiDuoNotificationListener::class.java)
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val controllers = manager.getActiveSessions(listener)
        val controller: MediaController = controllers.firstOrNull()
            ?: return "Не найден активный музыкальный плеер. Запусти музыку и попробуй снова."
        when (command) {
            "play" -> controller.transportControls.play()
            "pause" -> controller.transportControls.pause()
            "next" -> controller.transportControls.skipToNext()
            "previous" -> controller.transportControls.skipToPrevious()
        }
        "Команда отправлена активному плееру."
    } catch (_: SecurityException) {
        "Сначала разреши AI Duo доступ к уведомлениям и медиасеансам."
    } catch (_: Exception) {
        "Не удалось управлять текущим плеером."
    }
}

@Composable
fun PhoneAssistantPanel() {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Выбери действие. Android запросит доступ только при необходимости.") }
    var showSettings by remember { mutableStateOf(false) }
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            status = "Документ выбран: ${uri.lastPathSegment ?: "файл"}. Следующий этап — чтение и обработка содержимого."
        }
    }

    Surface(
        color = Color(0xFF151D31),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Помощник телефона", color = Color(0xFFEAF0FF), fontSize = 16.sp)
                TextButton(onClick = { showSettings = true }) { Text("Настройки") }
            }
            Text("Музыка", color = Color(0xFF39B98A), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("⏮" to "previous", "▶" to "play", "Ⅱ" to "pause", "⏭" to "next").forEach { (label, command) ->
                    OutlinedButton(
                        onClick = {
                            val result = controlMusic(context, command)
                            status = result
                            if (result.startsWith("Сначала")) {
                                launchPhoneIntent(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text(label) }
                }
            }
            Text("Документы и организация", color = Color(0xFF4C8DFF), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    documentPicker.launch(arrayOf("text/*", "application/pdf", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                }, modifier = Modifier.weight(1f)) { Text("Документ") }
                Button(onClick = {
                    val ok = launchPhoneIntent(context, Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI))
                    status = if (ok) "Открываю создание события в календаре." else "Не найдено приложение календаря."
                }, modifier = Modifier.weight(1f)) { Text("Событие") }
                Button(onClick = {
                    val ok = launchPhoneIntent(context, Intent(Intent.ACTION_VIEW).setData(CalendarContract.CONTENT_URI))
                    status = if (ok) "Открываю календарь." else "Не найдено приложение календаря."
                }, modifier = Modifier.weight(1f)) { Text("Календарь") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    val now = Calendar.getInstance()
                    now.add(Calendar.MINUTE, 1)
                    val ok = launchPhoneIntent(context, Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, now.get(Calendar.HOUR_OF_DAY))
                        putExtra(AlarmClock.EXTRA_MINUTES, now.get(Calendar.MINUTE))
                        putExtra(AlarmClock.EXTRA_MESSAGE, "AI Duo")
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    })
                    status = if (ok) "Открываю установку будильника." else "Не найдено приложение Часы."
                }, modifier = Modifier.weight(1f)) { Text("Будильник") }
                Button(onClick = {
                    val intent = if (Build.VERSION.SDK_INT >= 30) {
                        Intent(Intent.ACTION_CREATE_REMINDER).putExtra(Intent.EXTRA_TITLE, "Напоминание AI Duo")
                    } else {
                        Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, "Напоминание AI Duo")
                    }
                    val ok = launchPhoneIntent(context, intent)
                    status = if (ok) "Открываю создание напоминания." else "Нет приложения для создания напоминаний; можно добавить событие в календарь."
                }, modifier = Modifier.weight(1f)) { Text("Напоминание") }
            }
            Text(status, color = Color(0xFFAAB6D0), fontSize = 11.sp)
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Настройки AI Duo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Тема: тёмная")
                    Text("Голос: русский, озвучивание ответа включено")
                    Text("Сервер: адрес backend пока требует настройки")
                    Text("Яндекс ID и Сбер ID: подключение OAuth потребует Client ID и настроек приложений.")
                    Text("Для управления музыкой нужно вручную разрешить доступ AI Duo к уведомлениям.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSettings = false
                    launchPhoneIntent(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text("Доступ к музыке") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Закрыть") } }
        )
    }
}
