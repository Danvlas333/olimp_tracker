package ru.olymp.tracker

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.work.*
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class Watcher(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result { checkAll(applicationContext); return Result.success() }

    companion object {
        const val CH = "stages"
        private val KEY = Regex("(регистрац|отборочн|заключительн|приём заявок|прием заявок|этап)", RegexOption.IGNORE_CASE)
        private val MONTHS = listOf("января","февраля","марта","апреля","мая","июня","июля","августа","сентября","октября","ноября","декабря")
        private val DMY = Regex("(\\d{1,2})\\s+(" + MONTHS.joinToString("|") + ")", RegexOption.IGNORE_CASE)
        private val DOT = Regex("(\\d{1,2})\\.(\\d{2})(?:\\.(\\d{2,4}))?")

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<Watcher>(6, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("watch", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun fetch(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) OlympTracker")
            return c.inputStream.use { it.readBytes().take(1_500_000).toByteArray().toString(Charsets.UTF_8) }
        }

        fun signalLines(html: String): List<String> {
            val t = html.replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
                .replace(Regex("(?s)<[^>]+>"), "\n").replace("&nbsp;", " ").replace("&amp;", "&")
            return t.lines().map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.length in 8..300 && KEY.containsMatchIn(it) && (DMY.containsMatchIn(it) || DOT.containsMatchIn(it)) }
                .distinct().take(8)
        }

        fun dates(line: String): List<LocalDate> {
            val today = LocalDate.now(); val res = mutableListOf<LocalDate>()
            DMY.findAll(line).forEach { m ->
                val mon = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
                runCatching {
                    var d = LocalDate.of(today.year, mon, m.groupValues[1].toInt())
                    if (ChronoUnit.DAYS.between(d, today) > 60) d = d.plusYears(1)
                    res += d
                }
            }
            DOT.findAll(line).forEach { m ->
                runCatching {
                    val y = m.groupValues[3].let { if (it.isEmpty()) today.year else if (it.length == 2) 2000 + it.toInt() else it.toInt() }
                    res += LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
                }
            }
            return res
        }

        fun advice(line: String): String = when {
            Regex("регистрац|заявок", RegexOption.IGNORE_CASE).containsMatchIn(line) -> "Нужно зарегистрироваться / подать заявку"
            Regex("отборочн", RegexOption.IGNORE_CASE).containsMatchIn(line) -> "Отборочный этап: нужно принять участие"
            Regex("заключительн", RegexOption.IGNORE_CASE).containsMatchIn(line) -> "Заключительный этап"
            else -> "Новый этап"
        }

        fun notify(ctx: Context, id: String, title: String, text: String, url: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Этапы олимпиад", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(ctx, text.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true).build()
            nm.notify((id + text).hashCode(), n)
        }

        /** Возвращает число проверенных олимпиад. */
        fun checkAll(ctx: Context): Int {
            val sp = ctx.getSharedPreferences("t", Context.MODE_PRIVATE)
            val arr = JSONArray(ctx.assets.open("olympiads.json").bufferedReader().readText())
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val id = o.getString("id"); val url = o.getString("url")
                if (url.isEmpty() || !sp.getBoolean("on:$id", o.getBoolean("on"))) continue
                runCatching {
                    val lines = signalLines(fetch(url)); n++
                    val hash = lines.joinToString("|").hashCode().toString()
                    val prev = sp.getString("h:$id", null)
                    val name = o.getString("name").take(60)
                    if (prev != null && prev != hash && lines.isNotEmpty())
                        notify(ctx, id, name, advice(lines[0]) + ": " + lines[0], url)
                    val seen = sp.getStringSet("n:$id", emptySet())!!.toMutableSet()
                    lines.forEach { l -> dates(l).forEach { d ->
                        val days = ChronoUnit.DAYS.between(LocalDate.now(), d); val k = "${l.hashCode()}|$d"
                        if (days in 0..3 && k !in seen) {
                            notify(ctx, id, name + (if (days == 0L) " — сегодня" else " — через $days дн."), advice(l) + ": " + l, url)
                            seen += k
                        }
                    } }
                    sp.edit().putString("h:$id", hash).putString("s:$id", lines.firstOrNull() ?: "")
                        .putStringSet("n:$id", seen).apply()
                }
            }
            return n
        }
    }
}
