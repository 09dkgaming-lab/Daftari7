package com.daftari.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity data class Customer(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val phone: String)

@Entity data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long, val amount: Double, val note: String,
    val type: String, // "DEBT" or "PAY"
    val method: String, val ts: Long
)

@Dao interface AppDao {
    @Query("SELECT * FROM Customer ORDER BY name") fun customers(): Flow<List<Customer>>
    @Query("SELECT * FROM Txn ORDER BY ts") fun txns(): Flow<List<Txn>>
    @Query("SELECT * FROM Customer") suspend fun allC(): List<Customer>
    @Query("SELECT * FROM Txn") suspend fun allT(): List<Txn>
    @Insert suspend fun addC(c: Customer): Long
    @Insert suspend fun addT(t: Txn)
    @Insert suspend fun addAllC(l: List<Customer>)
    @Insert suspend fun addAllT(l: List<Txn>)
    @Query("DELETE FROM Customer WHERE id=:id") suspend fun delC(id: Long)
    @Query("DELETE FROM Txn WHERE customerId=:id") suspend fun delT(id: Long)
    @Query("DELETE FROM Customer") suspend fun clrC()
    @Query("DELETE FROM Txn") suspend fun clrT()
}

@Database(entities = [Customer::class, Txn::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var i: Db? = null
        fun get(c: Context): Db = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "daftari.db").build().also { i = it }
        }
    }
}

fun bal(l: List<Txn>) = l.sumOf { if (it.type == "DEBT") it.amount else -it.amount }
fun stars(l: List<Txn>) = minOf(5, l.count { it.type == "PAY" })
fun lateDays(l: List<Txn>): Long {
    val d = l.filter { it.type == "DEBT" }.maxOfOrNull { it.ts } ?: return 0
    return (System.currentTimeMillis() - d) / 86400000
}
