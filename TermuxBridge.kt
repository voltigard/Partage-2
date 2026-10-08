package com.devai.core

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import java.io.File

/**
 * Bridge to Termux. Android apps cannot directly execute binaries stored in
 * Termux's private sandbox, so builds are delegated to Termux through its
 * RUN_COMMAND intent.
 */
class TermuxBridge(private val context: Context) {
    data class Result(val ok: Boolean, val message: String)

    private val sharedRoot = File(Environment.getExternalStorageDirectory(), "DevAI")

    fun hasStorageAccess(): Boolean = android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun openStorageSettings() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.termux", 0)
        true
    }.getOrDefault(false)

    fun prepareProject(projectDir: File, projectName: String): Result = runCatching {
        val target = File(sharedRoot, "projects/$projectName").canonicalFile
        if (!target.path.startsWith(sharedRoot.canonicalPath + File.separator)) error("Chemin projet invalide")
        target.deleteRecursively()
        copyTree(projectDir, target)
        Result(true, target.absolutePath)
    }.getOrElse { Result(false, "Préparation Termux impossible : ${it.message.orEmpty()}") }

    fun startBuild(projectPath: String): Result = runCatching {
        if (!isInstalled()) return Result(false, "Termux n'est pas installé.")
        val command = "cd ${shellQuote(projectPath)} && export ANDROID_HOME=\"\$HOME/Android/android-sdk\" && export ANDROID_SDK_ROOT=\"\$ANDROID_HOME\" && export JAVA_HOME=\"\$PREFIX/lib/jvm/java-21-openjdk\" && export PATH=\"\$PREFIX/bin:\$ANDROID_HOME/build-tools/37.0.0:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH\" && gradle --no-daemon assembleDebug 2>&1 | tee ${shellQuote(projectPath + "/devai-build.log")}"
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", projectPath)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        Result(true, "Build lancé dans Termux. Logs : $projectPath/devai-build.log")
    }.getOrElse { Result(false, "Lancement Termux impossible : ${it.message.orEmpty()}") }

    fun logFile(projectPath: String): File = File(projectPath, "devai-build.log")

    private fun copyTree(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { copyTree(it, File(target, it.name)) }
        } else {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
