package com.daftari.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

val INK = Color(0xFF16324F); val BG = Color(0xFFEEF3F1); val RED = Color(0xFFC62828)
val GRN = Color(0xFF1B7F3B); val MUT = Color(0xFF5E7184)

class Vm(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()
    val customers = dao.customers().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val txns = dao.txns().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addCustomer(n: String, p: String) = viewModelScope.launch { dao.addC(Customer(name = n, phone = p)); sync() }
    fun addTxn(t: Txn) = viewModelScope.launch { dao.addT(t); sync() }
    fun delCustomer(id: Long) = viewModelScope.launch { dao.delT(id); dao.delC(id); sync() }

    suspend fun export(): String {
        val o = JSONObject()
        o.put("c", JSONArray().also { a -> dao.allC().forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone)) } })
        o.put("t", JSONArray().also { a -> dao.allT().forEach {
            a.put(JSONObject().put("id", it.id).put("c", it.customerId).put("a", it.amount).put("n", it.note)
                .put("k", it.type).put("m", it.method).put("ts", it.ts)) } })
        return o.toString()
    }
    fun import(json: String) = viewModelScope.launch {
        val o = JSONObject(json); val ca = o.getJSONArray("c"); val ta = o.getJSONArray("t")
        val cs = List(ca.length()) { ca.getJSONObject(it).let { j -> Customer(j.getLong("id"), j.getString("name"), j.getString("phone")) } }
        val ts = List(ta.length()) { ta.getJSONObject(it).let { j -> Txn(j.getLong("id"), j.getLong("c"), j.getDouble("a"), j.getString("n"), j.getString("k"), j.getString("m"), j.getLong("ts")) } }
        dao.clrT(); dao.clrC(); dao.addAllC(cs); dao.addAllT(ts)
    }
    /** نسخ احتياطي تلقائي للسحابة إذا كان البقال مسجل دخول */
    fun sync() = viewModelScope.launch {
        if (Cloud.user() != null) try { withTimeoutOrNull(15000) { Cloud.upload(export()) } } catch (_: Exception) {}
    }
}

sealed class Scr {
    object Home : Scr(); object Add : Scr(); object Rep : Scr(); object Cfg : Scr()
    data class Cust(val id: Long) : Scr(); data class Tx(val id: Long, val pay: Boolean) : Scr(); data class St(val id: Long) : Scr()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { val vm: Vm = viewModel(); App(vm) }
    }
}

@Composable fun App(vm: Vm) {
    var scr by remember { mutableStateOf<Scr>(Scr.Home) }
    var calc by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Pair<String, String>?>(null) } // phone, message
    val cs by vm.customers.collectAsState(); val ts by vm.txns.collectAsState()
    val ctx = LocalContext.current
    BackHandler(scr != Scr.Home) { scr = Scr.Home }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = lightColorScheme(primary = INK)) {
            Surface(Modifier.fillMaxSize(), color = BG) {
                if (calc) CalcDialog({ calc = false }, null)
                ask?.let { (ph, msg) ->
                    AlertDialog(onDismissRequest = { ask = null },
                        title = { Text("تم الحفظ") }, text = { Text("إرسال رسالة واتساب للزبون؟") },
                        confirmButton = { TextButton({ openWhatsApp(ctx, ph, msg); ask = null }) { Text("إرسال") } },
                        dismissButton = { TextButton({ ask = null }) { Text("لاحقاً") } })
                }
                when (val s = scr) {
                    Scr.Home -> Home(cs, ts, { scr = it }, { calc = true })
                    Scr.Add -> AddCust({ scr = Scr.Home }) { n, p -> vm.addCustomer(n, p); scr = Scr.Home }
                    is Scr.Cust -> cs.find { it.id == s.id }?.let { c ->
                        CustScr(c, ts.filter { it.customerId == c.id }, { scr = it }, { scr = Scr.Home }) { vm.delCustomer(c.id); scr = Scr.Home }
                    }
                    is Scr.Tx -> cs.find { it.id == s.id }?.let { c ->
                        val l = ts.filter { it.customerId == c.id }
                        TxForm(c, s.pay, bal(l), { scr = Scr.Cust(c.id) }) { t ->
                            vm.addTxn(t); val nb = bal(l) + if (t.type == "DEBT") t.amount else -t.amount
                            val shop = shopName(ctx)
                            ask = c.phone to (if (t.type == "DEBT") "${c.name}، تم تسجيل دين ${fmt(t.amount)} ريال (${t.note}). الباقي عليك ${fmt(nb)} ريال. $shop"
                                else "${c.name}، تم استلام ${fmt(t.amount)} ريال (${t.method}). الباقي عليك ${fmt(nb)} ريال. شكراً لك. $shop")
                            scr = Scr.Cust(c.id)
                        }
                    }
                    is Scr.St -> cs.find { it.id == s.id }?.let { c -> Statement(c, ts.filter { it.customerId == c.id }) { scr = Scr.Cust(c.id) } }
                    Scr.Rep -> Reports(cs, ts) { scr = Scr.Home }
                    Scr.Cfg -> Settings(vm) { scr = Scr.Home }
                }
            }
        }
    }
}

@Composable fun BigBtn(t: String, c: Color = INK, h: Int = 60, onClick: () -> Unit) =
    Button(onClick, Modifier.fillMaxWidth().height(h.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = c)) {
        Text(t, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    }

@Composable fun Top(t: String, back: () -> Unit) = Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    TextButton(back) { Text("رجوع", fontSize = 16.sp) }
    Text(t, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
}

@Composable fun Home(cs: List<Customer>, ts: List<Txn>, go: (Scr) -> Unit, calc: () -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val rows = cs.filter { it.name.contains(q) }.map { it to ts.filter { t -> t.customerId == it.id } }.sortedByDescending { bal(it.second) }
    val total = cs.sumOf { c -> maxOf(0.0, bal(ts.filter { it.customerId == c.id })) }
    val left = trialDaysLeft(ctx)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("دفتري", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text("دفترك وحاسبتك في جيبك", color = MUT)
                if (left in 0..7) Text("التجربة المجانية: باقي $left يوم", color = GRN, fontSize = 14.sp)
                Card(Modifier.fillMaxWidth().padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = INK)) {
                    Column(Modifier.padding(16.dp)) { Text("إجمالي الديون عند الزبائن", color = Color.White); Text(fmt(total), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold) }
                }
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("بحث عن زبون") }, singleLine = true)
            }
            items(rows) { (c, l) ->
                val b = bal(l); val late = b > 0 && lateDays(l) > 15
                Card(Modifier.fillMaxWidth(), onClick = { go(Scr.Cust(c.id)) }, colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = if (late) BorderStroke(2.dp, RED) else null) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("★".repeat(stars(l)) + if (late) "  متأخر ${lateDays(l)} يوم" else "", color = if (late) RED else MUT, fontSize = 14.sp)
                        }
                        Text(fmt(b), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = if (b > 0) RED else GRN)
                    }
                }
            }
            item { Spacer(Modifier.height(170.dp)) }
        }
        Column(Modifier.align(Alignment.BottomCenter).background(BG).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(calc, Modifier.weight(1f)) { Text("🧮 حاسبة") }
                OutlinedButton({ go(Scr.Rep) }, Modifier.weight(1f)) { Text("التقارير") }
                OutlinedButton({ go(Scr.Cfg) }, Modifier.weight(1f)) { Text("إعدادات") }
            }
            BigBtn("+ إضافة زبون") { go(Scr.Add) }
        }
    }
}

@Composable fun AddCust(back: () -> Unit, save: (String, String) -> Unit) {
    var n by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Top("زبون جديد", back)
        OutlinedTextField(n, { n = it }, Modifier.fillMaxWidth(), label = { Text("اسم الحساب (مثال: أحمد علي)") })
        OutlinedTextField(p, { p = it }, Modifier.fillMaxWidth(), label = { Text("رقم الواتساب (777xxxxxx)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        BigBtn("حفظ", GRN) { if (n.isNotBlank()) save(n.trim(), p.trim()) }
    }
}

@Composable fun CustScr(c: Customer, l: List<Txn>, go: (Scr) -> Unit, back: () -> Unit, del: () -> Unit) {
    val ctx = LocalContext.current; val b = bal(l); var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog({ confirm = false }, title = { Text("حذف الزبون وكل حركاته؟") },
        confirmButton = { TextButton(del) { Text("حذف", color = RED) } }, dismissButton = { TextButton({ confirm = false }) { Text("إلغاء") } })
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Top(c.name + " " + "★".repeat(stars(l)), back)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(16.dp)) { Text("الباقي عليه", color = MUT); Text(fmt(b), fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = if (b > 0) RED else GRN) }
        }
        BigBtn("+ إضافة حركة (دين)", RED) { go(Scr.Tx(c.id, false)) }
        BigBtn("تسديد", GRN) { go(Scr.Tx(c.id, true)) }
        BigBtn("كشف حساب") { go(Scr.St(c.id)) }
        OutlinedButton({ openWhatsApp(ctx, c.phone, "السلام عليكم ${c.name}، نذكّرك بأن المتبقي عليك في ${shopName(ctx)} هو ${fmt(b)} ريال. شكراً لك.") },
            Modifier.fillMaxWidth().height(52.dp)) { Text("ذكّره بالواتساب", fontSize = 18.sp) }
        TextButton({ confirm = true }) { Text("حذف الزبون", color = RED) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun TxForm(c: Customer, pay: Boolean, b: Double, back: () -> Unit, save: (Txn) -> Unit) {
    var amt by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    var m by remember { mutableStateOf(METHODS[0]) }; var calc by remember { mutableStateOf(false) }; var menu by remember { mutableStateOf(false) }
    if (calc) CalcDialog({ calc = false }) { amt = if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Top(if (pay) "تسديد" else "دين جديد", back)
        Text("${c.name} — الباقي عليه ${fmt(b)}", color = MUT)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(amt, { amt = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f),
                label = { Text(if (pay) "المبلغ المسدد" else "المبلغ") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 30.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedButton({ calc = true }, Modifier.height(64.dp)) { Text("🧮", fontSize = 24.sp) }
        }
        if (pay) Box {
            OutlinedButton({ menu = true }, Modifier.fillMaxWidth().height(56.dp)) { Text("طريقة القبض: $m") }
            DropdownMenu(menu, { menu = false }) { METHODS.forEach { x -> DropdownMenuItem(text = { Text(x) }, onClick = { m = x; menu = false }) } }
        }
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(if (pay) "البيان (مثال: سداد عبر ون كاش - رقم العملية)" else "المناولة / القيمة") })
        if (!pay) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QUICK.forEach { q -> AssistChip(onClick = { note = if (note.isEmpty()) q else "$note + $q" }, label = { Text(q) }) }
        }
        BigBtn(if (pay) "تأكيد السداد" else "حفظ كـ دين", if (pay) GRN else RED, 72) {
            val a = amt.toDoubleOrNull() ?: 0.0
            if (a > 0) save(Txn(customerId = c.id, amount = a, note = note, type = if (pay) "PAY" else "DEBT", method = if (pay) m else "", ts = System.currentTimeMillis()))
        }
    }
}

@Composable fun Statement(c: Customer, all: List<Txn>, back: () -> Unit) {
    val ctx = LocalContext.current; var f by remember { mutableStateOf(3) }
    val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
    val from = when (f) { 0 -> cal.timeInMillis; 1 -> cal.timeInMillis - 6 * 86400000L; 2 -> cal.apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis; else -> 0L }
    val l = all.filter { it.ts >= from }.sortedBy { it.ts }
    val d = l.filter { it.type == "DEBT" }.sumOf { it.amount }; val p = l.filter { it.type == "PAY" }.sumOf { it.amount }
    val lines = statementLines(shopName(ctx), c, l)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Top("كشف حساب — ${c.name}", back)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("اليوم", "الأسبوع", "الشهر", "كامل").forEachIndexed { i, t -> FilterChip(f == i, { f = i }, { Text(t) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("الدين" to d, "المسدد" to p, "الباقي" to d - p).forEach { (t, v) ->
                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(t, fontSize = 13.sp, color = MUT); Text(fmt(v), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(10.dp)) {
                l.forEach {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(dateStr(it.ts), Modifier.width(88.dp), fontSize = 14.sp)
                        Text((it.note + " " + it.method).trim(), Modifier.weight(1f), fontSize = 14.sp)
                        Text((if (it.type == "DEBT") "+" else "−") + fmt(it.amount), color = if (it.type == "DEBT") RED else GRN, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider()
                }
            }
        }
        BigBtn("إرسال الكشف صورة (واتساب)", GRN) { shareStatement(ctx, lines, false) }
        BigBtn("إرسال الكشف PDF (واتساب)", GRN) { shareStatement(ctx, lines, true) }
        OutlinedButton({ shareText(ctx, lines.joinToString("\n")) }, Modifier.fillMaxWidth().height(52.dp)) { Text("مشاركة كنص") }
    }
}

@Composable fun Reports(cs: List<Customer>, ts: List<Txn>, back: () -> Unit) {
    val t0 = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    val by = ts.filter { it.type == "PAY" && it.ts >= t0 }.groupBy { it.method }.mapValues { e -> e.value.sumOf { it.amount } }
    val rows = cs.map { c -> val l = ts.filter { it.customerId == c.id }; Triple(c.name, bal(l), stars(l)) }
    @Composable fun Sec(t: String, items: List<Pair<String, String>>, col: Color) = Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp)) {
            Text(t, fontWeight = FontWeight.ExtraBold)
            if (items.isEmpty()) Text("—", color = MUT)
            items.forEach { Row { Text(it.first, Modifier.weight(1f)); Text(it.second, color = col, fontWeight = FontWeight.Bold) } }
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Top("التقارير", back)
        Sec("المقبوض اليوم", by.map { it.key to fmt(it.value) }, GRN)
        Sec("أكثر الزبائن مديونية", rows.filter { it.second > 0 }.sortedByDescending { it.second }.take(5).map { it.first to fmt(it.second) }, RED)
        Sec("أكثر الزبائن التزاماً", rows.filter { it.third > 0 }.sortedByDescending { it.third }.take(5).map { it.first to "★".repeat(it.third) }, INK)
    }
}

@Composable fun Settings(vm: Vm, back: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var shop by remember { mutableStateOf(shopName(ctx)) }; var user by remember { mutableStateOf(Cloud.user()?.email) }
    var msg by remember { mutableStateOf("") }
    val client = remember { GoogleSignIn.getClient(ctx, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(webClientId(ctx)).requestEmail().build()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        scope.launch {
            try {
                Cloud.signIn(GoogleSignIn.getSignedInAccountFromIntent(r.data).await()); user = Cloud.user()?.email
                msg = "تم تسجيل الدخول. اضغط استرجاع إذا كانت لك نسخة سابقة."
            } catch (e: Exception) { msg = "فشل تسجيل الدخول: تأكد من إعداد Firebase (انظر README)" }
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Top("إعدادات", back)
        OutlinedTextField(shop, { shop = it; setShop(ctx, it) }, Modifier.fillMaxWidth(), label = { Text("اسم البقالة (يظهر في الكشف)") })
        Text(if (user == null) "غير مسجل دخول — بياناتك على هذا الجوال فقط" else "مسجل بحساب: $user", color = MUT)
        if (user == null) BigBtn("تسجيل الدخول بجوجل") { launcher.launch(client.signInIntent) }
        else {
            BigBtn("نسخ احتياطي للسحابة الآن", GRN) { scope.launch { msg = try { withTimeout(15000) { Cloud.upload(vm.export()) }; "تم الرفع" } catch (e: Exception) { "تعذر الرفع، تأكد من الإنترنت" } } }
            BigBtn("استرجاع من السحابة") { scope.launch { msg = try { val j = withTimeout(15000) { Cloud.download() }; if (j == null) "لا توجد نسخة" else { vm.import(j); "تم الاسترجاع" } } catch (e: Exception) { "تعذر الاسترجاع" } } }
            TextButton({ Cloud.signOut(); client.signOut(); user = null }) { Text("تسجيل الخروج") }
        }
        OutlinedButton({ scope.launch { shareText(ctx, vm.export()) } }, Modifier.fillMaxWidth().height(52.dp)) { Text("تصدير نسخة وإرسالها واتساب") }
        if (msg.isNotEmpty()) Text(msg, color = INK, fontWeight = FontWeight.Bold)
    }
}
