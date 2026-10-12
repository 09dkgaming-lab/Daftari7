package com.daftari.app

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

val METHODS = listOf("نقد - مناولة يد", "جيب", "ون كاش", "فلوسك", "جوالي", "كريمي جوي / جوال", "حوالة الكريمي", "حوالة نجم",
    "حوالة حزمي", "حوالة الامياز", "حوالة الهتار", "حوالة العامري", "حوالة العيدروس", "حوالة دادية", "حوالة المميز",
    "حوالة اليمنيه", "حوالة الاكوع", "حوالة البرق", "حوالة مال", "حوالة Hb", "حوالة المحيط", "حوالة الناصر")
val QUICK = listOf("رز", "سكر", "زيت", "دقيق", "بيض", "مناولة", "سداد مناولة")

fun fmt(d: Double): String = String.format(Locale.US, "%,.0f", d)
fun dateStr(ts: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(ts)
fun shopName(c: Context) = c.getSharedPreferences("d", 0).getString("shop", "بقالتي") ?: "بقالتي"
fun setShop(c: Context, s: String) = c.getSharedPreferences("d", 0).edit().putString("shop", s).apply()
fun trialDaysLeft(c: Context): Int {
    val p = c.getSharedPreferences("d", 0)
    if (!p.contains("first")) p.edit().putLong("first", System.currentTimeMillis()).apply()
    return (7 - (System.currentTimeMillis() - p.getLong("first", 0)) / 86400000).toInt()
}

fun openWhatsApp(c: Context, phone: String, msg: String) {
    var p = phone.filter { it.isDigit() }
    if (p.length <= 9) p = "967$p" // اليمن
    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$p?text=" + Uri.encode(msg))))
}

fun statementLines(shop: String, c: Customer, l: List<Txn>): List<String> {
    val d = l.filter { it.type == "DEBT" }.sumOf { it.amount }
    val p = l.filter { it.type == "PAY" }.sumOf { it.amount }
    return listOf(shop, "كشف حساب: ${c.name}", "") +
        l.map { "${dateStr(it.ts)} | ${if (it.type == "DEBT") "دين" else "سداد"} ${fmt(it.amount)} ${it.note} ${it.method}".trim() } +
        listOf("", "إجمالي الدين: ${fmt(d)}", "المسدد: ${fmt(p)}", "الباقي: ${fmt(d - p)}")
}

fun shareStatement(c: Context, lines: List<String>, pdf: Boolean) {
    val w = 595; val h = maxOf(842, 100 + lines.size * 30)
    val paint = Paint().apply { textSize = 18f; isAntiAlias = true; textAlign = Paint.Align.RIGHT; color = Color.BLACK }
    fun draw(cv: Canvas) { lines.forEachIndexed { i, s -> cv.drawText(s, w - 30f, 50f + i * 30f, paint) } }
    val f = File(c.cacheDir, if (pdf) "statement.pdf" else "statement.png")
    if (pdf) {
        val d = PdfDocument()
        val pg = d.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        draw(pg.canvas); d.finishPage(pg)
        f.outputStream().use { d.writeTo(it) }; d.close()
    } else {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.drawColor(Color.WHITE); draw(cv)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    val uri = FileProvider.getUriForFile(c, "${c.packageName}.fp", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = if (pdf) "application/pdf" else "image/png"
        putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    c.startActivity(Intent.createChooser(i, "إرسال الكشف"))
}

fun shareText(c: Context, t: String) {
    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t) }, "مشاركة"))
}

fun webClientId(c: Context): String {
    val id = c.resources.getIdentifier("default_web_client_id", "string", c.packageName)
    return if (id == 0) "" else c.getString(id)
}

/** الحاسبة الداخلية: + − × ÷ مع أولوية الضرب والقسمة */
fun evalExpr(s: String): Double? {
    return try {
        var i = 0
        fun peek() = if (i < s.length) s[i] else '\u0000'
        fun num(): Double { val st = i; while (peek().isDigit() || peek() == '.') i++; return s.substring(st, i).toDouble() }
        fun term(): Double {
            var v = num()
            while (peek() == '×' || peek() == '÷') { val o = s[i++]; val r = num(); v = if (o == '×') v * r else v / r }
            return v
        }
        var v = term()
        while (peek() == '+' || peek() == '−') { val o = s[i++]; val r = term(); v = if (o == '+') v + r else v - r }
        if (i == s.length) v else null
    } catch (e: Exception) { null }
}

object Cloud {
    fun user() = FirebaseAuth.getInstance().currentUser
    suspend fun signIn(a: GoogleSignInAccount) {
        FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(a.idToken, null)).await()
    }
    fun signOut() = FirebaseAuth.getInstance().signOut()
    suspend fun upload(json: String) {
        val u = user() ?: return
        FirebaseFirestore.getInstance().collection("backups").document(u.uid)
            .set(mapOf("json" to json, "ts" to System.currentTimeMillis())).await()
    }
    suspend fun download(): String? {
        val u = user() ?: return null
        return FirebaseFirestore.getInstance().collection("backups").document(u.uid).get().await().getString("json")
    }
}
