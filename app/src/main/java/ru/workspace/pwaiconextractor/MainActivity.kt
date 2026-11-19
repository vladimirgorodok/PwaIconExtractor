package ru.workspace.pwaiconextractor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

class MainViewModel : ViewModel() {
    var appUrl by mutableStateOf("")
    var icons = mutableStateListOf<IconItem>()
    var logs = mutableStateListOf<LogEntry>()
    var saveTreeUri by mutableStateOf<Uri?>(null)
    var savePathLabel by mutableStateOf("Не выбрана")
    var isBusy by mutableStateOf(false)
    var statusText by mutableStateOf("Готово")

    fun appendLog(message: String, isError: Boolean = false, isSuccess: Boolean = false) {
        viewModelScope.launch(Dispatchers.Main) {
            logs.add(LogEntry(message, isError, isSuccess))
            if (logs.size > 500) logs.removeAt(0)
        }
    }

    fun setStatus(text: String) {
        viewModelScope.launch(Dispatchers.Main) { statusText = text }
    }

    fun extract() {
        val url = appUrl.trim()
        if (url.isBlank()) {
            appendLog("Введите адрес PWA-приложения", isError = true)
            return
        }
        if (isBusy) return
        viewModelScope.launch {
            isBusy = true
            setStatus("Извлечение...")
            icons.clear()
            logs.clear()
            appendLog("Запуск извлечения иконок")
            val result = withContext(Dispatchers.IO) {
                IconExtractor(
                    log = { msg, err, ok -> appendLog(msg, err, ok) }
                ).extract(url)
            }
            icons.clear()
            icons.addAll(result)
            isBusy = false
            setStatus(if (result.isEmpty()) "Иконки не найдены" else "Найдено: ${result.size}")
        }
    }

    fun save(context: android.content.Context) {
        val tree = saveTreeUri
        if (tree == null) {
            appendLog("Сначала выберите папку сохранения", isError = true)
            return
        }
        if (icons.none { it.selected && it.bytes != null }) {
            appendLog("Не выбрано ни одной иконки для сохранения", isError = true)
            return
        }
        if (isBusy) return
        viewModelScope.launch {
            isBusy = true
            setStatus("Сохранение...")
            val current = icons.toList()
            val updated = withContext(Dispatchers.IO) {
                IconSaver(context.applicationContext) { msg, err, ok -> appendLog(msg, err, ok) }
                    .save(tree, current)
            }
            icons.clear()
            icons.addAll(updated)
            isBusy = false
            setStatus("Сохранение завершено")
        }
    }

    fun setAllSelected(selected: Boolean) {
        val snapshot = icons.toList()
        icons.clear()
        icons.addAll(snapshot.map { if (it.bytes != null) it.copy(selected = selected) else it })
    }

    fun toggleItem(id: String) {
        val idx = icons.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val item = icons[idx]
            icons[idx] = item.copy(selected = !item.selected)
        }
    }

    fun onFolderPicked(context: android.content.Context, uri: Uri?) {
        if (uri == null) return
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) { }
        saveTreeUri = uri
        savePathLabel = formatTreeUri(uri)
        appendLog("Папка сохранения: $savePathLabel", isSuccess = true)
    }

    private fun formatTreeUri(uri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            docId ?: uri.toString()
        } catch (_: Exception) {
            uri.lastPathSegment ?: uri.toString()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.onFolderPicked(context, uri)
    }

    val selectedCount = vm.icons.count { it.selected && it.bytes != null }
    val allSelectable = vm.icons.count { it.bytes != null }
    val allSelected = allSelectable > 0 && selectedCount == allSelectable

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Извлечение иконок PWA") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // URL input
            OutlinedTextField(
                value = vm.appUrl,
                onValueChange = { vm.appUrl = it },
                label = { Text("Адрес PWA-приложения (URL)") },
                placeholder = { Text("https://example.app") },
                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val text = clipboard.getText()?.text?.trim().orEmpty()
                        if (text.isNotBlank()) {
                            vm.appUrl = text
                            vm.appendLog("URL вставлен из буфера обмена")
                        } else {
                            vm.appendLog("Буфер обмена пуст", isError = true)
                        }
                    },
                    enabled = !vm.isBusy
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Вставить")
                }
                Button(
                    onClick = { vm.extract() },
                    enabled = !vm.isBusy && vm.appUrl.isNotBlank()
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Извлечь")
                }
            }

            // Save path
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Путь сохранения", fontWeight = FontWeight.SemiBold)
                    Text(
                        vm.savePathLabel,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { folderPicker.launch(null) }, enabled = !vm.isBusy) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Выбрать папку")
                        }
                        Button(
                            onClick = { vm.save(context) },
                            enabled = !vm.isBusy && vm.saveTreeUri != null && selectedCount > 0
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Сохранить ($selectedCount)")
                        }
                    }
                }
            }

            // Preview header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Предпросмотр (${vm.icons.size})", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { vm.setAllSelected(it) },
                        enabled = allSelectable > 0 && !vm.isBusy
                    )
                    Text("Выбрать все", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Preview grid
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (vm.icons.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Иконки появятся здесь после извлечения",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(vm.icons, key = { it.id }) { item ->
                            IconPreviewCard(
                                item = item,
                                onToggle = { if (!vm.isBusy && item.bytes != null) vm.toggleItem(item.id) }
                            )
                        }
                    }
                }
            }

            // Progress & logs
            StatusSection(vm)
        }
    }
}

@Composable
fun IconPreviewCard(item: IconItem, onToggle: () -> Unit) {
    val borderColor = if (item.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (item.bitmap != null) {
                Image(
                    bitmap = item.bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    tint = if (item.bytes == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = item.selected,
                onCheckedChange = { onToggle() },
                enabled = item.bytes != null
            )
            Text(
                item.sizeLabel,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            item.source.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        if (item.saveStatus != null) {
            Text(
                item.saveStatus!!,
                style = MaterialTheme.typography.labelSmall,
                color = if (item.saveStatus == "Сохранено") Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (item.bytes == null) {
            Text("Не загружено", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun StatusSection(vm: MainViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.logs.size) {
        if (vm.logs.isNotEmpty()) listState.animateScrollToItem(vm.logs.size - 1)
    }
    Card(modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 220.dp)) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Прогресс и действия", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(vm.statusText, style = MaterialTheme.typography.bodySmall)
            }
            if (vm.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (vm.logs.isEmpty()) {
                Text("Журнал действий пуст", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(vm.logs) { entry ->
                        val color = when {
                            entry.isError -> MaterialTheme.colorScheme.error
                            entry.isSuccess -> Color(0xFF2E7D32)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            text = "• ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = color
                        )
                    }
                }
            }
        }
    }
}
