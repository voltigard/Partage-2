package com.devai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.devai.core.BuildEngine
import com.devai.core.EnvironmentDiagnostics
import com.devai.core.LocalCommandRunner
import com.devai.core.ProjectStore
import com.devai.core.ToolchainManager
import com.devai.core.TermuxBridge
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); setContent { DevAIApp(ProjectStore(this)) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevAIApp(store: ProjectStore) {
    val context = LocalContext.current
    val scope = remember { MainScope() }
    val runner = remember { LocalCommandRunner() }
    val diagnostics = remember { EnvironmentDiagnostics() }
    val toolchain = remember { ToolchainManager(context) }
    val builder = remember { BuildEngine(runner, toolchain) }
    val termux = remember { TermuxBridge(context) }
    var project by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<String>()) }
    var projects by remember { mutableStateOf(store.listProjects()) }
    var status by remember { mutableStateOf("Prêt") }
    var logs by remember { mutableStateOf("") }
    var environment by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var renameName by remember { mutableStateOf("") }
    var clipboard by remember { mutableStateOf<String?>(null) }
    var termuxProjectPath by remember { mutableStateOf<String?>(null) }
    var cut by remember { mutableStateOf(false) }

    fun refresh() { project?.let { entries = store.listEntries(it) } }
    fun select(e: String) { selected = e; editing = null; status = "Sélectionné : $e" }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null && project != null) {
            val n = "import_${System.currentTimeMillis()}"
            val dest = selected?.takeIf { it.endsWith("/") }?.let { "$it$n" } ?: n
            status = if (store.importFile(project!!, dest, u)) { refresh(); "Fichier importé" } else "Échec import"
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null && project != null) {
            val dest = selected?.takeIf { it.endsWith("/") }?.trimEnd('/') ?: ""
            status = if (store.importZip(project!!, dest, u)) { refresh(); "ZIP extrait" } else "Échec extraction ZIP"
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null && project != null) {
            val n = "dossier_${System.currentTimeMillis()}"
            val dest = selected?.takeIf { it.endsWith("/") }?.let { "${it}$n" } ?: n
            status = if (store.importDirectory(project!!, dest, u)) { refresh(); "Dossier importé" } else "Échec import dossier"
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { u: Uri? ->
        if (u != null && project != null) status = if (store.exportZip(project!!, selected?.trimEnd('/'), u)) "ZIP exporté" else "Échec export ZIP"
    }
    val toolchainPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) {
            scope.launch {
                val local = java.io.File(context.cacheDir, "toolchain-import.zip")
                context.contentResolver.openInputStream(u)?.use { input -> local.outputStream().use { input.copyTo(it) } }
                val result = toolchain.installFromZip(local)
                logs = result.fold({ "[DevAI] ${it}" }, { "[DevAI] Import impossible : ${it.message.orEmpty()}" })
                status = "Toolchain : ${toolchain.inspect().let { if (it.ready) "présente" else "incomplète" }}"
                dialog = "logs"
            }
        }
    }

    MaterialTheme {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(project?.let { "DevAI • $it" } ?: "DevAI V1.11") },
                navigationIcon = { if (project != null) TextButton({ project = null; selected = null; editing = null }) { Text("Projets") } }
            )
        }) { pad ->
            if (project == null) {
                Home(Modifier.padding(pad), projects, { dialog = "project" }) { p -> project = p; entries = store.listEntries(p); status = "Projet : $p" }
            } else {
                Editor(
                    Modifier.padding(pad), entries, selected, editing, content, status,
                    { select(it) }, { f -> editing = f; content = store.readFile(project!!, f); status = "Modification : $f" },
                    { content = it }, { if (editing != null && store.writeFile(project!!, editing!!, content)) { status = "Enregistré"; refresh() } },
                    { dialog = "file" }, { dialog = "actions" },
                    { logs = diagnostics.inspect().text + "\n\n" + toolchain.inspect().text; status = "Diagnostic terminé"; dialog = "logs" },
                    { filePicker.launch(arrayOf("*/*")) }, { folderPicker.launch(null) }, { zipPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    { exportPicker.launch("${selected?.trimEnd('/') ?: project}.zip") },
                    { toolchainPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    {
                        status = "Compilation APK locale…"
                        scope.launch {
                            val built = builder.build(store.projectDir(project!!))
                            logs = "exit=${built.result.exitCode}\n${built.result.output}"
                            status = if (built.result.exitCode == 0) {
                                built.apk?.let { "APK généré : ${it.name}" } ?: "Build terminé"
                            } else "Compilation échouée"
                            dialog = "logs"
                        }
                    }, { dialog = "logs" }, { logs = "" }
                )
            }
        }
    }

    if (dialog == "project") Prompt("Nouveau projet", "Nom", name, { name = it }, { if (store.createProject(name)) { projects = store.listProjects(); name = ""; dialog = "" } }, { dialog = "" })
    if (dialog == "file") Prompt("Nouveau fichier", "Chemin ex. app/src/Main.kt", name, { name = it }, { if (store.createFile(project!!, name)) { refresh(); selected = name; name = ""; dialog = "" } }, { dialog = "" })
    if (dialog == "actions" && selected != null) Actions(selected!!, { editing = selected; content = store.readFile(project!!, selected!!); dialog = "" }, { clipboard = selected; cut = false; dialog = "" }, { clipboard = selected; cut = true; dialog = "" }, { clipboard?.let { src -> val base = if (selected!!.endsWith("/")) selected!!.trimEnd('/') + "/" else ""; if (store.paste(project!!, src, base + src.trimEnd('/').substringAfterLast('/'))) { if (cut) store.delete(project!!, src); refresh(); clipboard = null; cut = false } }; dialog = "" }, { renameName = selected!!.trimEnd('/').substringAfterLast('/'); dialog = "rename" }, { if (store.delete(project!!, selected!!)) { selected = null; refresh() }; dialog = "" }, { dialog = "" })
    if (dialog == "rename" && project != null && selected != null) Prompt("Renommer", "Nouveau nom", renameName, { renameName = it }, { val ok = store.rename(project!!, selected!!, renameName); status = if (ok) "Renommé" else "Échec du renommage"; if (ok) { refresh(); selected = null; dialog = "" } }, { dialog = "" })
    if (dialog == "logs") LogsDialog(logs, { copyText(context, logs); status = "Logs copiés" }, { dialog = "" })
}

@Composable private fun Home(m: Modifier, ps: List<String>, new: () -> Unit, open: (String) -> Unit) = Column(m.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Projets locaux", style = MaterialTheme.typography.headlineSmall); Text("Local uniquement — aucun accès GitHub."); Button(new) { Text("Nouveau projet") }; LazyColumn { items(ps) { Text(it, Modifier.fillMaxWidth().clickable { open(it) }.padding(16.dp)) } } }

@Composable
private fun Editor(m: Modifier, es: List<String>, sel: String?, edit: String?, content: String, status: String, select: (String) -> Unit, onEdit: (String) -> Unit, onContent: (String) -> Unit, save: () -> Unit, newFile: () -> Unit, actions: () -> Unit, environment: () -> Unit, importFile: () -> Unit, importFolder: () -> Unit, importZip: () -> Unit, exportZip: () -> Unit, toolchain: () -> Unit, build: () -> Unit, logs: () -> Unit, clearLogs: () -> Unit) {
    Column(m.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { Button(newFile) { Text("Nouveau") }; Button(actions, enabled = sel != null) { Text("Actions") }; Button(importFile) { Text("Importer") }; Button(importFolder) { Text("Dossier") }; Button(importZip) { Text("ZIP") } }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { Button(exportZip, enabled = sel != null) { Text("Exporter ZIP") }; Button(toolchain) { Text("TOOLCHAIN") }; Button(build) { Text("COMPILER APK") }; Button({
                            if (!termux.isInstalled()) {
                                logs = "Termux n'est pas installé. Installe Termux puis active l'accès externe."
                                dialog = "logs"
                            } else if (!termux.hasStorageAccess()) {
                                termux.openStorageSettings()
                                logs = "Autorise l'accès à tous les fichiers pour DevAI, puis appuie de nouveau sur TERMUX."
                                dialog = "logs"
                            } else {
                                scope.launch {
                                    val prepared = termux.prepareProject(store.projectDir(project!!), project!!)
                                    logs = if (!prepared.ok) prepared.message else {
                                        termuxProjectPath = prepared.message
                                        val launched = termux.startBuild(prepared.message)
                                        launched.message
                                    }
                                    status = if (termux.isInstalled()) "Build Termux lancé" else "Termux absent"
                                    dialog = "logs"
                                }
                            }
                        }) { Text("TERMUX") }; Button(logs) { Text("LOGS") } }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { Button(environment) { Text("Diagnostic") }; Button(clearLogs) { Text("Effacer logs") } }
        Text(status)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.fillMaxWidth(.35f)) { items(es) { Text(it, Modifier.fillMaxWidth().clickable { select(it) }.padding(7.dp)) } }
            Column(Modifier.fillMaxSize()) { Text(sel ?: "Sélectionnez un fichier ou dossier"); if (edit != null) { OutlinedTextField(content, onContent, Modifier.fillMaxWidth().weight(1f)); Button(save, Modifier.fillMaxWidth()) { Text("Enregistrer") } } else Text("Utilisez Actions pour modifier l'élément sélectionné.") }
        }
    }
}

@Composable private fun Prompt(title: String, label: String, value: String, onValue: (String) -> Unit, ok: () -> Unit, cancel: () -> Unit) = AlertDialog(onDismissRequest = cancel, title = { Text(title) }, text = { OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true) }, confirmButton = { TextButton(ok) { Text("OK") } }, dismissButton = { TextButton(cancel) { Text("Annuler") } })

@Composable private fun Actions(item: String, edit: () -> Unit, copy: () -> Unit, cut: () -> Unit, paste: () -> Unit, rename: () -> Unit, delete: () -> Unit, close: () -> Unit) = AlertDialog(onDismissRequest = close, title = { Text("Actions : $item") }, text = { Column { if (!item.endsWith("/")) TextButton(edit) { Text("Modifier") }; TextButton(copy) { Text("Copier") }; TextButton(cut) { Text("Couper") }; TextButton(paste) { Text("Coller") }; TextButton(rename) { Text("Renommer") }; TextButton(delete) { Text("Supprimer") } } }, confirmButton = { TextButton(close) { Text("Fermer") } })

@Composable private fun LogsDialog(logs: String, copy: () -> Unit, close: () -> Unit) = AlertDialog(onDismissRequest = close, title = { Text("Logs de compilation") }, text = { OutlinedTextField(logs, {}, Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 500.dp), readOnly = true) }, confirmButton = { TextButton(copy) { Text("Copier") } }, dismissButton = { TextButton(close) { Text("Fermer") } })

private fun copyText(context: Context, text: String) { val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; clipboard.setPrimaryClip(ClipData.newPlainText("DevAI logs", text)) }
