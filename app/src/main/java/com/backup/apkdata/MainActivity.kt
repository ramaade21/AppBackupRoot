package com.backup.apkdata

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.*
import com.topjohnwu.superuser.Shell
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val base = "/sdcard/AppBackup"
    private val data = "/data/user/0"
    private lateinit var list: ListView
    private lateinit var status: TextView
    private var pkgs = listOf<String>()
    private var restoreMode = false

    private fun sh(c: String) = Shell.cmd(c).exec()
    private fun log(s: String) = runOnUiThread { status.append(s + "\n") }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val r1 = LinearLayout(this); val r2 = LinearLayout(this)
        r1.addView(btn("Daftar Aplikasi") { loadApps() })
        r1.addView(btn("Daftar Backup") { loadBackups() })
        r2.addView(btn("Backup") { process(false) })
        r2.addView(btn("Restore") { process(true) })
        list = ListView(this)
        status = TextView(this).apply { textSize = 12f }
        val sv = ScrollView(this).apply { addView(status) }
        root.addView(r1); root.addView(r2)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 0.6f))
        setContentView(root)
        log("Perlu root. Backup disimpan di $base")
    }

    private fun show(labels: List<String>) = runOnUiThread {
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
    }

    private fun loadApps() = thread {
        if (!Shell.getShell().isRoot) { log("ERROR: akses root tidak diberikan"); return@thread }
        restoreMode = false
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { it.packageName != packageName &&
                ((it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        pkgs = apps.map { it.packageName }
        show(apps.map { it.loadLabel(pm).toString() + "\n" + it.packageName })
        log("${apps.size} aplikasi dimuat")
    }

    private fun loadBackups() = thread {
        if (!Shell.getShell().isRoot) { log("ERROR: akses root tidak diberikan"); return@thread }
        restoreMode = true
        pkgs = sh("ls $base").out.map { it.trim() }.filter { it.isNotEmpty() }
        show(pkgs)
        log("${pkgs.size} backup ditemukan")
    }

    private fun process(restore: Boolean) {
        if (restore != restoreMode) { log("Muat daftar yang sesuai dulu (Daftar ${if (restore) "Backup" else "Aplikasi"})"); return }
        val chk = list.checkedItemPositions
        val sel = pkgs.filterIndexed { i, _ -> chk.get(i) }
        if (sel.isEmpty()) { log("Belum ada yang dipilih"); return }
        thread {
            sel.forEach { p ->
                log(">> $p")
                val msg = try { if (restore) restore(p) else backup(p) } catch (e: Exception) { "GAGAL: ${e.message}" }
                log(msg)
            }
            log("Selesai.")
        }
    }

    private fun backup(p: String): String {
        val d = "$base/$p"
        sh("am force-stop $p")
        sh("rm -rf $d; mkdir -p $d/apk")
        val paths = sh("pm path $p").out.map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) return "GAGAL: APK tidak ditemukan"
        paths.forEach { sh("cp '$it' $d/apk/") }
        val t = sh("tar -czf $d/data.tar.gz --exclude=$p/cache --exclude=$p/code_cache -C $data $p")
        if (!t.isSuccess) return "GAGAL backup data: ${t.err.joinToString()}"
        sh("[ -d /sdcard/Android/data/$p ] && tar -czf $d/ext.tar.gz -C /sdcard/Android/data $p")
        sh("[ -d /sdcard/Android/obb/$p ] && tar -czf $d/obb.tar.gz -C /sdcard/Android/obb $p")
        return "OK: ${paths.size} APK + data tersimpan"
    }

    private fun restore(p: String): String {
        val d = "$base/$p"
        val tmp = "/data/local/tmp/rs"
        sh("rm -rf $tmp; mkdir -p $tmp; cp $d/apk/*.apk $tmp/; chmod 644 $tmp/*.apk")
        val i = sh("pm install-multiple -r -d $tmp/*.apk")
        sh("rm -rf $tmp")
        if (!i.isSuccess) return "GAGAL install: ${(i.out + i.err).joinToString()}"
        sh("am force-stop $p")
        val uid = sh("stat -c %u $data/$p").out.firstOrNull()?.trim()
        if (uid.isNullOrEmpty()) return "GAGAL: folder data tidak ditemukan"
        val x = sh("tar -xzf $d/data.tar.gz -C $data")
        if (!x.isSuccess) return "GAGAL ekstrak data: ${x.err.joinToString()}"
        sh("chown -R $uid:$uid $data/$p; restorecon -R $data/$p")
        sh("[ -f $d/ext.tar.gz ] && mkdir -p /sdcard/Android/data && tar -xzf $d/ext.tar.gz -C /sdcard/Android/data")
        sh("[ -f $d/obb.tar.gz ] && mkdir -p /sdcard/Android/obb && tar -xzf $d/obb.tar.gz -C /sdcard/Android/obb")
        return "OK: aplikasi + data dipulihkan"
    }
}
