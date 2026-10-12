package com.daftari.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** حاسبة داخلية (Custom Calculator) — لا تخرج من التطبيق. onApply = null للحاسبة العامة */
@Composable fun CalcDialog(onDismiss: () -> Unit, onApply: ((Double) -> Unit)?) {
    var ex by remember { mutableStateOf("") }
    val clean = ex.trimEnd('+', '−', '×', '÷')
    val res = if (clean.isEmpty()) 0.0 else evalExpr(clean) ?: 0.0
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(16.dp)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(ex.ifEmpty { " " }, Modifier.fillMaxWidth(), textAlign = TextAlign.End, fontSize = 20.sp, color = MUT)
                    Text(fmt(res), Modifier.fillMaxWidth(), textAlign = TextAlign.End, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    listOf("7", "8", "9", "÷", "4", "5", "6", "×", "1", "2", "3", "−", "0", ".", "⌫", "+").chunked(4).forEach { r ->
                        Row(Modifier.fillMaxWidth()) {
                            r.forEach { k ->
                                val op = k in listOf("÷", "×", "−", "+")
                                Button({ ex = if (k == "⌫") ex.dropLast(1) else ex + k }, Modifier.weight(1f).padding(3.dp).height(58.dp),
                                    colors = if (op) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(containerColor = BG, contentColor = INK)) {
                                    Text(k, fontSize = 24.sp)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ ex = "" }, Modifier.weight(1f).height(52.dp)) { Text("مسح", fontSize = 18.sp) }
                    Button({ onApply?.invoke(res); onDismiss() }, Modifier.weight(2f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = GRN)) {
                        Text(if (onApply != null) "تطبيق المبلغ" else "إغلاق", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
