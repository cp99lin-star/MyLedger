package com.example.myledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "records")
data class Record(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val category: String,
    val note: String,
    val isIncome: Boolean,
    val date: String
)

@Dao
interface RecordDao {
    @Query("SELECT * FROM records ORDER BY id ASC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<Record>>

    @Insert
    suspend fun insert(record: Record)

    @Update
    suspend fun update(record: Record)

    @Delete
    suspend fun delete(record: Record)
}

@Database(entities = [Record::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "my_ledger.db"
                ).build().also { INSTANCE = it }
            }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.get(this)
        setContent { MyLedgerApp(db.recordDao()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyLedgerApp(dao: RecordDao) {
    val records by dao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Record?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    val income = records.filter { it.isIncome }.sumOf { it.amount }
    val expense = records.filter { !it.isIncome }.sumOf { it.amount }
    val balance = income - expense

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("我的記帳") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAdd = true }) { Text("+") }
            }
        ) { padding ->
            Column(
                Modifier.padding(padding).padding(16.dp).fillMaxSize()
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("目前結餘", style = MaterialTheme.typography.titleMedium)
                        Text("NT$ ${"%,.0f".format(balance)}",
                            style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("收入\nNT$ ${"%,.0f".format(income)}")
                            Text("支出\nNT$ ${"%,.0f".format(expense)}")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("記帳紀錄", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))

                if (records.isEmpty()) {
                    Text("目前還沒有記帳資料，按右下角 + 開始記帳。")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(records.reversed(), key = { it.id }) { r ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(r.category, style = MaterialTheme.typography.titleMedium)
                                            Text(if (r.note.isBlank()) r.date else "${r.note} · ${r.date}")
                                        }
                                        Text((if (r.isIncome) "+" else "-") +
                                                " NT$ ${"%,.0f".format(r.amount)}")
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Row {
                                        TextButton(onClick = { editing = r }) { Text("編輯") }
                                        TextButton(onClick = {
                                            scope.launch { dao.delete(r) }
                                        }) { Text("刪除") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAdd) {
            RecordDialog(
                title = "新增記帳",
                initial = null,
                onDismiss = { showAdd = false },
                onSave = { amount, category, note, isIncome ->
                    val date = SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN).format(Date())
                    scope.launch {
                        dao.insert(Record(
                            amount = amount, category = category, note = note,
                            isIncome = isIncome, date = date
                        ))
                    }
                    showAdd = false
                }
            )
        }

        editing?.let { current ->
            RecordDialog(
                title = "編輯記帳",
                initial = current,
                onDismiss = { editing = null },
                onSave = { amount, category, note, isIncome ->
                    scope.launch {
                        dao.update(current.copy(
                            amount = amount, category = category,
                            note = note, isIncome = isIncome
                        ))
                    }
                    editing = null
                }
            )
        }
    }
}

@Composable
fun RecordDialog(
    title: String,
    initial: Record?,
    onDismiss: () -> Unit,
    onSave: (Double, String, String, Boolean) -> Unit
) {
    var amountText by remember { mutableStateOf(initial?.amount?.toString() ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "飲食") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var isIncome by remember { mutableStateOf(initial?.isIncome ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    FilterChip(selected = !isIncome, onClick = { isIncome = false }, label = { Text("支出") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = isIncome, onClick = { isIncome = true }, label = { Text("收入") })
                }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("金額") }, singleLine = true
                )
                OutlinedTextField(
                    value = category, onValueChange = { category = it },
                    label = { Text("分類") }, singleLine = true
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("備註") }, singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = amountText.toDoubleOrNull()
                if (amount != null && amount > 0) {
                    onSave(amount, category.ifBlank { "未分類" }, note, isIncome)
                }
            }) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
