package ru.olymp.tracker

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var items: List<JSONObject>
    private lateinit var sp: SharedPreferences
    private lateinit var adapter: BaseAdapter

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sp = getSharedPreferences("t", MODE_PRIVATE)
        val arr = JSONArray(assets.open("olympiads.json").bufferedReader().readText())
        items = (0 until arr.length()).map { arr.getJSONObject(it) }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        Watcher.schedule(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 8) }
        val status = TextView(this).apply { text = "Отмечены олимпиады, за которыми идёт слежение (проверка раз в 6 часов)" }
        val btn = Button(this).apply { text = "Проверить сейчас" }
        btn.setOnClickListener {
            status.text = "Проверяю..."
            Thread {
                val n = Watcher.checkAll(this)
                runOnUiThread { status.text = "Проверено сайтов: $n"; adapter.notifyDataSetChanged() }
            }.start()
        }
        val list = ListView(this)
        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val o = items[p]; val id = o.getString("id")
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 16, 8, 16) }
                val cb = CheckBox(this@MainActivity).apply {
                    text = o.getString("name"); isChecked = sp.getBoolean("on:$id", o.getBoolean("on"))
                    setOnCheckedChangeListener { _, c -> sp.edit().putBoolean("on:$id", c).apply() }
                }
                val info = TextView(this@MainActivity).apply {
                    val s = sp.getString("s:$id", "") ?: ""
                    text = "Уровень ${o.getString("level")} · ${o.getString("profile")}" + if (s.isNotEmpty()) "\nНа сайте: $s" else ""
                    textSize = 12f
                }
                row.addView(cb); row.addView(info); return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ ->
            val u = items[p].getString("url")
            if (u.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        }
        root.addView(btn); root.addView(status); root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
