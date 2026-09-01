package com.bluewhale.agent.platform

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.bluewhale.agent.model.ActionResult
import com.bluewhale.agent.model.SystemCapability
import com.bluewhale.agent.virtualdisplay.ShizukuVirtualDisplayClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.ArrayList

/** Converts closed SystemCapability values into allow-listed Android Intents. */
object SystemCapabilityExecutor {
    fun executeOnMain(
        context: Context,
        capability: SystemCapability,
    ): ActionResult =
        try {
            val spec = buildSpec(context, capability)
            spec.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(spec.intent)
            success(capability, spec.message, spec.intent)
        } catch (error: ActivityNotFoundException) {
            ActionResult.Failure("系统中没有可处理 ${capability.toolName} 的应用", error)
        } catch (error: Exception) {
            ActionResult.Failure("系统工具 ${capability.toolName} 执行失败：${error.message}", error)
        }

    fun executeOnVirtualDisplay(
        context: Context,
        client: ShizukuVirtualDisplayClient,
        displayId: Int,
        capability: SystemCapability,
    ): ActionResult =
        try {
            val spec = buildSpec(context, capability)
            val command = intentCommand(spec.intent, displayId)
            val exitCode = client.executeShellCommand(command.toTypedArray())
            if (exitCode == 0) {
                success(capability, spec.message, spec.intent)
            } else {
                ActionResult.Failure(
                    "虚拟屏系统工具 ${capability.toolName} 启动失败，退出码 $exitCode",
                )
            }
        } catch (error: Exception) {
            ActionResult.Failure("虚拟屏系统工具 ${capability.toolName} 执行失败：${error.message}", error)
        }

    private fun success(
        capability: SystemCapability,
        message: String,
        intent: Intent,
    ): ActionResult.Success =
        ActionResult.Success(
            message,
            metadata =
                linkedMapOf(
                    "system_verified" to "true",
                    "system_tool" to capability.toolName,
                    "intent_action" to intent.action.orEmpty(),
                    "requires_user_interaction" to capability.requiresUserInteraction.toString(),
                ),
        )

    private fun buildSpec(
        context: Context,
        capability: SystemCapability,
    ): IntentSpec =
        when (capability) {
            is SystemCapability.Navigate -> {
                val uri =
                    Uri.parse("geo:0,0")
                        .buildUpon()
                        .appendQueryParameter("q", capability.destination)
                        .build()
                val intent = Intent(Intent.ACTION_VIEW, uri)
                mapPackage(capability.mapApp)
                    ?.takeIf { context.packageManager.getLaunchIntentForPackage(it) != null }
                    ?.let(intent::setPackage)
                IntentSpec(intent, "已打开地图并传入目的地：${capability.destination}")
            }
            is SystemCapability.SetAlarm -> {
                val days = ArrayList(capability.repeatDays.mapNotNull(::calendarDay))
                val intent =
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, capability.hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, capability.minute)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                capability.label.takeIf(String::isNotBlank)?.let {
                    intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
                }
                if (days.isNotEmpty()) intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days)
                IntentSpec(
                    intent,
                    "已请求系统设置闹钟：%02d:%02d".format(capability.hour, capability.minute),
                )
            }
            is SystemCapability.SetTimer ->
                IntentSpec(
                    Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, capability.durationSeconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, capability.label)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                    "已启动 ${capability.durationSeconds} 秒计时器",
                )
            is SystemCapability.CreateCalendarEvent -> {
                val intent =
                    Intent(Intent.ACTION_INSERT)
                        .setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, capability.title)
                        .putExtra(
                            CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                            parseDateTime(capability.startTime),
                        )
                        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, capability.allDay)
                capability.endTime?.let {
                    intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, parseDateTime(it))
                }
                capability.location.takeIf(String::isNotBlank)?.let {
                    intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it)
                }
                capability.description.takeIf(String::isNotBlank)?.let {
                    intent.putExtra(CalendarContract.Events.DESCRIPTION, it)
                }
                IntentSpec(intent, "已打开日历并预填事件“${capability.title}”，请确认保存")
            }
            is SystemCapability.CreateContact -> {
                val intent =
                    Intent(Intent.ACTION_INSERT)
                        .setType(ContactsContract.Contacts.CONTENT_TYPE)
                        .putExtra(ContactsContract.Intents.Insert.NAME, capability.name)
                capability.phone.takeIf(String::isNotBlank)?.let {
                    intent.putExtra(ContactsContract.Intents.Insert.PHONE, it)
                }
                capability.email.takeIf(String::isNotBlank)?.let {
                    intent.putExtra(ContactsContract.Intents.Insert.EMAIL, it)
                }
                IntentSpec(intent, "已打开联系人并预填“${capability.name}”，请确认保存")
            }
            is SystemCapability.ComposeSms ->
                IntentSpec(
                    Intent(
                        Intent.ACTION_SENDTO,
                        Uri.fromParts("smsto", capability.recipient, null),
                    ).putExtra("sms_body", capability.body),
                    "已打开短信编辑页并预填收件人和内容，请确认发送",
                )
            is SystemCapability.DialPhone ->
                IntentSpec(
                    Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", capability.phoneNumber, null)),
                    "已打开拨号页面并填入号码，请确认拨打",
                )
            is SystemCapability.OpenCamera ->
                IntentSpec(
                    Intent(
                        if (capability.mode.equals("video", true)) {
                            MediaStore.INTENT_ACTION_VIDEO_CAMERA
                        } else {
                            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
                        },
                    ),
                    if (capability.mode.equals("video", true)) {
                        "已打开录像模式，请确认开始录制"
                    } else {
                        "已打开拍照模式，请确认拍摄"
                    },
                )
            is SystemCapability.OpenUrl ->
                IntentSpec(Intent(Intent.ACTION_VIEW, Uri.parse(capability.url)), "已打开网页")
            is SystemCapability.WebSearch ->
                IntentSpec(
                    Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, capability.query),
                    "已发起网页搜索：${capability.query}",
                )
            is SystemCapability.OpenSystemSettings ->
                IntentSpec(
                    Intent(settingsAction(capability.page)),
                    "已打开${settingsLabel(capability.page)}",
                )
            is SystemCapability.ComposeEmail -> {
                val uri =
                    Uri.parse("mailto:${Uri.encode(capability.recipient)}")
                        .buildUpon()
                        .appendQueryParameter("subject", capability.subject)
                        .appendQueryParameter("body", capability.body)
                        .build()
                IntentSpec(Intent(Intent.ACTION_SENDTO, uri), "已打开邮件编辑页，请确认发送")
            }
            is SystemCapability.ShareText ->
                IntentSpec(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, capability.text)
                        .putExtra(Intent.EXTRA_TITLE, capability.title),
                    "已打开系统分享面板，请选择目标应用",
                )
            is SystemCapability.PlayMedia ->
                IntentSpec(
                    Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        .putExtra(SearchManager.QUERY, capability.query)
                        .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"),
                    "已请求播放：${capability.query}",
                )
        }

    private fun intentCommand(intent: Intent, displayId: Int): List<String> =
        buildList {
            addAll(listOf("am", "start", "--display", displayId.toString()))
            intent.action?.let { addAll(listOf("-a", it)) }
            intent.dataString?.let { addAll(listOf("-d", it)) }
            intent.type?.let { addAll(listOf("-t", it)) }
            intent.`package`?.let { addAll(listOf("-p", it)) }
            intent.extras?.let { extras ->
                extras.keySet().sorted().forEach { key ->
                    when (val value = extras.get(key)) {
                        is String -> addAll(listOf("--es", key, value))
                        is Int -> addAll(listOf("--ei", key, value.toString()))
                        is Long -> addAll(listOf("--el", key, value.toString()))
                        is Boolean -> addAll(listOf("--ez", key, value.toString()))
                        is ArrayList<*> -> {
                            val integers = value.filterIsInstance<Int>()
                            if (integers.size == value.size) {
                                addAll(listOf("--eial", key, integers.joinToString(",")))
                            }
                        }
                    }
                }
            }
        }

    private fun mapPackage(value: String): String? =
        when (value.lowercase()) {
            "amap" -> "com.autonavi.minimap"
            "baidu" -> "com.baidu.BaiduMap"
            "tencent" -> "com.tencent.map"
            else -> null
        }

    private fun calendarDay(value: String): Int? =
        when (value.uppercase()) {
            "MON" -> java.util.Calendar.MONDAY
            "TUE" -> java.util.Calendar.TUESDAY
            "WED" -> java.util.Calendar.WEDNESDAY
            "THU" -> java.util.Calendar.THURSDAY
            "FRI" -> java.util.Calendar.FRIDAY
            "SAT" -> java.util.Calendar.SATURDAY
            "SUN" -> java.util.Calendar.SUNDAY
            else -> null
        }

    private fun settingsAction(value: String): String =
        when (value.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "notification" -> ACTION_NOTIFICATION_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

    private fun settingsLabel(value: String): String =
        when (value.lowercase()) {
            "wifi" -> "Wi-Fi 设置"
            "bluetooth" -> "蓝牙设置"
            "location" -> "定位设置"
            "notification" -> "通知设置"
            "display" -> "显示设置"
            "sound" -> "声音设置"
            "battery" -> "省电设置"
            "accessibility" -> "无障碍设置"
            "apps" -> "应用设置"
            else -> "系统设置"
        }

    private fun parseDateTime(value: String): Long =
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { ZonedDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            .recoverCatching {
                LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            .getOrThrow()

    private data class IntentSpec(val intent: Intent, val message: String)

    private const val ACTION_NOTIFICATION_SETTINGS = "android.settings.NOTIFICATION_SETTINGS"
}
