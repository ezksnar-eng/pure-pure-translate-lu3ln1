package com.veron.files

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    data class Item(
        val title: String,
        val sub: String,
        val icon: String,
        val file: File? = null,
        val app: ApplicationInfo? = null
    )

    private val BG = Color.parseColor("#0F1115")
    private val CARD = Color.parseColor("#1A1D24")
    private val ACC = Color.parseColor("#7C5CFF")
    private val DLG = android.R.style.Theme_Material_Dialog_Alert

    private lateinit var pathView: TextView
    private lateinit var listView: ListView
    private lateinit var content: LinearLayout
    private lateinit var permPanel: LinearLayout
    private lateinit var adapter: BaseAdapter
    private val items = ArrayList<Item>()

    private var cur: File = Environment.getExternalStorageDirectory()
    private var mode = 0            // 0 = ملفات ، 1 = تطبيقات
    private var searching = false
    private var clip: File? = null
    private var cut = false
    private var started = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ───────────────────────── UI ─────────────────────────
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(0, dp(8), 0, 0)
        }

        // لوحة طلب الصلاحية
        permPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            visibility = View.GONE
        }
        permPanel.addView(TextView(this).apply {
            text = "🔐\nلازم تعطي التطبيق صلاحية \"الوصول لكل الملفات\" حتى يكدر يعرض كل ملفاتك"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })
        permPanel.addView(chip("اعطاء الصلاحية") { askAccess() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setBackgroundColor(ACC)
        })

        // المحتوى
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        pathView = TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 12f
            setPadding(dp(14), dp(6), dp(14), dp(6))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.START
            textDirection = View.TEXT_DIRECTION_LTR
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val tabs = row(
            chip("📂 الملفات") { showFiles() },
            chip("📱 التطبيقات") { showApps() }
        )
        val actions = row(
            chip("⬆") { up() },
            chip("🏠") { roots() },
            chip("➕") { newFolder() },
            chip("📋") { paste() },
            chip("🔍") { prompt("ابحث بالاسم داخل هذا المجلد", "") { if (it.isNotEmpty()) search(it) } }
        )

        listView = ListView(this).apply {
            divider = null
            setBackgroundColor(BG)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(i: Int) = items[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, p: ViewGroup?): View {
                val item = items[i]
                val r = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
                if (item.app != null) {
                    val iv = ImageView(this@MainActivity)
                    iv.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    try { iv.setImageDrawable(packageManager.getApplicationIcon(item.app)) } catch (e: Exception) {}
                    r.addView(iv)
                } else {
                    r.addView(TextView(this@MainActivity).apply {
                        text = item.icon; textSize = 26f; gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    })
                }
                val col = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(12), 0)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                col.addView(TextView(this@MainActivity).apply {
                    text = item.title; setTextColor(Color.WHITE); textSize = 15f
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                col.addView(TextView(this@MainActivity).apply {
                    text = item.sub; setTextColor(Color.GRAY); textSize = 11f
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                r.addView(col)
                return r
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, i, _ ->
            val item = items[i]
            if (item.file != null) {
                if (item.file.isDirectory) { cur = item.file; showFiles() } else openFile(item.file)
            } else if (item.app != null) appMenu(item.app)
        }
        listView.setOnItemLongClickListener { _, _, i, _ ->
            val f = items[i].file
            if (f != null) fileMenu(f) else if (items[i].app != null) appMenu(items[i].app!!)
            true
        }

        content.addView(pathView)
        content.addView(tabs)
        content.addView(actions)
        content.addView(listView)

        root.addView(permPanel, LinearLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun chip(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        setPadding(dp(6), dp(10), dp(6), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(CARD) }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), 0)
        v.forEach { addView(it) }
    }

    // ───────────────────────── الصلاحيات ─────────────────────────
    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); updateAccessUi()
    }

    override fun onResume() { super.onResume(); updateAccessUi() }

    private fun updateAccessUi() {
        if (hasAccess()) {
            permPanel.visibility = View.GONE
            content.visibility = View.VISIBLE
            if (!started) { started = true; showFiles() }
        } else {
            permPanel.visibility = View.VISIBLE
            content.visibility = View.GONE
        }
    }

    // ───────────────────────── الملفات ─────────────────────────
    private fun showFiles() {
        mode = 0; searching = false
        pathView.text = cur.absolutePath
        items.clear()
        val arr = cur.listFiles()
        if (arr == null) toast("ماكو صلاحية لقراءة هذا المجلد (أندرويد يمنعه)")
        else arr.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { items.add(fileItem(it)) }
        adapter.notifyDataSetChanged()
        listView.setSelection(0)
    }

    private fun fileItem(f: File): Item {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))
        val sub = if (f.isDirectory) date else "${size(f.length())} · $date"
        return Item(f.name, sub, emoji(f), file = f)
    }

    private fun up() {
        val p = cur.parentFile ?: return
        cur = p; showFiles()
    }

    private fun roots() {
        val ext = Environment.getExternalStorageDirectory()
        val l = ArrayList<Pair<String, File>>()
        l.add("📱 الذاكرة الداخلية" to ext)
        listOf("Download", "DCIM", "Pictures", "Movies", "Music", "Documents", "Android").forEach {
            val f = File(ext, it); if (f.exists()) l.add("   • $it" to f)
        }
        File("/storage").listFiles()?.filter { it.name != "emulated" && it.name != "self" }
            ?.forEach { l.add("💾 ${it.name}" to it) }
        l.add("/  جذر النظام" to File("/"))
        AlertDialog.Builder(this, DLG).setTitle("اختر مكان")
            .setItems(l.map { it.first }.toTypedArray()) { _, w -> cur = l[w].second; showFiles() }
            .show()
    }

    private fun newFolder() {
        if (mode != 0 || searching) return
        prompt("اسم المجلد الجديد", "") { n ->
            if (n.isNotEmpty()) { toast(if (File(cur, n).mkdirs()) "تم" else "فشل"); showFiles() }
        }
    }

    private fun search(q: String) {
        searching = true; mode = 0
        pathView.text = "🔍 $q"
        items.clear(); adapter.notifyDataSetChanged()
        val base = cur
        Thread {
            val res = ArrayList<File>()
            try {
                for (f in base.walkTopDown()) {
                    if (res.size >= 500) break
                    if (f != base && f.name.contains(q, true)) res.add(f)
                }
            } catch (e: Exception) {}
            runOnUiThread {
                if (searching) {
                    items.clear()
                    res.forEach { items.add(fileItem(it).copy(sub = it.parent ?: "")) }
                    adapter.notifyDataSetChanged()
                    toast("${res.size} نتيجة")
                }
            }
        }.start()
    }

    private fun paste() {
        val src = clip ?: run { toast("ماكو شي منسوخ"); return }
        if (mode != 0 || searching) return
        val dest = File(cur, src.name)
        if (dest.exists()) { toast("الاسم موجود بالمجلد"); return }
        Thread {
            val ok = try {
                if (cut) src.renameTo(dest) || (src.copyRecursively(dest) && src.deleteRecursively())
                else src.copyRecursively(dest)
            } catch (e: Exception) { false }
            runOnUiThread {
                toast(if (ok) "تم" else "فشل")
                if (ok && cut) clip = null
                showFiles()
            }
        }.start()
    }

    private fun fileMenu(f: File) {
        val ops = arrayOf("فتح", "مشاركة", "نسخ", "قص", "إعادة تسمية", "حذف", "تفاصيل")
        AlertDialog.Builder(this, DLG).setTitle(f.name).setItems(ops) { _, w ->
            when (w) {
                0 -> if (f.isDirectory) { cur = f; showFiles() } else openFile(f)
                1 -> share(f)
                2 -> { clip = f; cut = false; toast("اننسخ — روح للمجلد واضغط 📋") }
                3 -> { clip = f; cut = true; toast("انقص — روح للمجلد واضغط 📋") }
                4 -> prompt("الاسم الجديد", f.name) { n ->
                    if (n.isNotEmpty()) {
                        toast(if (f.renameTo(File(f.parentFile, n))) "تم" else "فشل"); showFiles()
                    }
                }
                5 -> AlertDialog.Builder(this, DLG).setMessage("تحذف \"${f.name}\"؟")
                    .setPositiveButton("احذف") { _, _ ->
                        Thread {
                            val ok = f.deleteRecursively()
                            runOnUiThread { toast(if (ok) "انحذف" else "فشل"); showFiles() }
                        }.start()
                    }.setNegativeButton("إلغاء", null).show()
                6 -> details(f)
            }
        }.show()
    }

    private fun details(f: File) {
        val d = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(f.lastModified()))
        val msg = "المسار:\n${f.absolutePath}\n\nالحجم: ${if (f.isDirectory) "مجلد" else size(f.length())}\n" +
                "آخر تعديل: $d\nقراءة: ${f.canRead()}  |  كتابة: ${f.canWrite()}"
        AlertDialog.Builder(this, DLG).setTitle(f.name).setMessage(msg).setPositiveButton("تمام", null).show()
    }

    private fun uriFor(f: File) = FileProvider.getUriForFile(this, "$packageName.fp", f)
    private fun mime(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    private fun openFile(f: File) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriFor(f), mime(f))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: ActivityNotFoundException) { toast("ماكو تطبيق يفتح هذا النوع") }
        catch (e: Exception) { toast("تعذر الفتح") }
    }

    private fun share(f: File) {
        if (f.isDirectory) { toast("ما ينشارك مجلد"); return }
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = mime(f); putExtra(Intent.EXTRA_STREAM, uriFor(f))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "مشاركة"))
        } catch (e: Exception) { toast("تعذرت المشاركة") }
    }

    // ───────────────────────── التطبيقات ─────────────────────────
    private fun showApps() {
        mode = 1; searching = false
        pathView.text = "التطبيقات المثبتة"
        items.clear(); adapter.notifyDataSetChanged()
        val pm = packageManager
        Thread {
            val list = pm.getInstalledApplications(0)
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
                .map {
                    val sys = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    Item(
                        pm.getApplicationLabel(it).toString(),
                        it.packageName + (if (sys) " · نظام" else "") + " · " + size(File(it.sourceDir).length()),
                        "", app = it
                    )
                }
            runOnUiThread {
                if (mode == 1) {
                    items.clear(); items.addAll(list); adapter.notifyDataSetChanged()
                    pathView.text = "التطبيقات المثبتة (${list.size})"
                }
            }
        }.start()
    }

    private fun appMenu(a: ApplicationInfo) {
        val pm = packageManager
        val label = pm.getApplicationLabel(a).toString()
        val ops = arrayOf("فتح التطبيق", "معلومات التطبيق", "استخراج APK للذاكرة", "مجلد بياناته", "إلغاء التثبيت")
        AlertDialog.Builder(this, DLG).setTitle(label).setItems(ops) { _, w ->
            when (w) {
                0 -> {
                    val i = pm.getLaunchIntentForPackage(a.packageName)
                    if (i != null) startActivity(i) else toast("هذا التطبيق ماله واجهة تشغيل")
                }
                1 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
                2 -> extractApk(a, label)
                3 -> {
                    val d = File(Environment.getExternalStorageDirectory(), "Android/data/${a.packageName}")
                    if (d.exists() && d.canRead() && d.list() != null) { cur = d; showFiles() }
                    else toast("أندرويد يمنع الوصول لهذا المجلد (قيد من النظام)")
                }
                4 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.packageName}")))
            }
        }.show()
    }

    private fun extractApk(a: ApplicationInfo, label: String) {
        val out = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AppBackup/" + label.replace(Regex("[^\\w\\u0600-\\u06FF .-]"), "_"))
        toast("جاري الاستخراج...")
        Thread {
            val ok = try {
                out.mkdirs()
                File(a.sourceDir).copyTo(File(out, "base.apk"), true)
                a.splitSourceDirs?.forEach { File(it).copyTo(File(out, File(it).name), true) }
                true
            } catch (e: Exception) { false }
            runOnUiThread { toast(if (ok) "انحفظ بـ: ${out.absolutePath}" else "فشل الاستخراج") }
        }.start()
    }

    // ───────────────────────── أدوات ─────────────────────────
    private fun prompt(title: String, def: String, ok: (String) -> Unit) {
        val et = EditText(this).apply { setText(def); setTextColor(Color.WHITE) }
        AlertDialog.Builder(this, DLG).setTitle(title).setView(et)
            .setPositiveButton("تم") { _, _ -> ok(et.text.toString().trim()) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun size(b: Long): String {
        if (b < 1024) return "$b B"
        val u = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0; var i = 0
        while (v >= 1024 && i < 3) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, u[i])
    }

    private fun emoji(f: File) = if (f.isDirectory) "📁" else when (f.extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "🖼️"
        "mp4", "mkv", "avi", "mov", "webm" -> "🎬"
        "mp3", "wav", "ogg", "m4a", "flac" -> "🎵"
        "pdf" -> "📕"
        "zip", "rar", "7z", "tar", "gz" -> "🗜️"
        "apk" -> "🤖"
        "txt", "md", "log" -> "📄"
        "html", "js", "css", "json", "xml", "kt", "java", "py" -> "🧾"
        else -> "📃"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode == 1 || searching) { showFiles(); return }
        val p = cur.parentFile
        val top = Environment.getExternalStorageDirectory().absolutePath
        if (p != null && cur.absolutePath != top && cur.absolutePath != "/") { cur = p; showFiles() }
        else super.onBackPressed()
    }
}
