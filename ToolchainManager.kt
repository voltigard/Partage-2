package com.devai.core

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ToolchainManager(private val context: Context) {
    data class ToolchainReport(
        val ready: Boolean,
        val text: String,
        val java: File?,
        val gradle: File?,
        val sdk: File?
    )

    private val root = File(context.filesDir, "toolchain")

    fun inspect(): ToolchainReport {
        // Ne dépend pas du nom du dossier racine du ZIP : on cherche les signatures
        // réelles d'une installation JDK, Gradle et Android SDK.
        val java = findNamedExecutable(root, "java")
        val gradle = findNamedExecutable(root, "gradle")
        val sdkDir = findAndroidSdk(root)
        val buildTools = sdkDir?.let { File(it, "build-tools") }
        val platforms = sdkDir?.let { File(it, "platforms") }
        val aapt2 = buildTools?.let { firstExecutableChild(it, "aapt2") }
        val apksigner = buildTools?.let { firstExecutableChild(it, "apksigner") }
        val d8 = buildTools?.let { firstExecutableChild(it, "d8") }
        val ready = java != null && gradle != null && sdkDir?.isDirectory == true &&
            buildTools?.isDirectory == true && platforms?.isDirectory == true &&
            aapt2 != null && apksigner != null && d8 != null

        val lines = mutableListOf<String>()
        lines += "DevAI • toolchain locale"
        lines += ""
        lines += "Racine : ${root.absolutePath}"
        lines += "JDK : ${java?.absolutePath ?: "ABSENT"}"
        lines += "Gradle : ${gradle?.absolutePath ?: "ABSENT"}"
        lines += "Android SDK : ${sdkDir?.absolutePath ?: "ABSENT"}"
        lines += "Build-tools : ${buildTools?.absolutePath ?: "ABSENT"}"
        lines += "aapt2 : ${aapt2?.absolutePath ?: "ABSENT"}"
        lines += "d8 : ${d8?.absolutePath ?: "ABSENT"}"
        lines += "apksigner : ${apksigner?.absolutePath ?: "ABSENT"}"
        lines += ""
        lines += if (ready) "État : TOOLCHAIN PRÉSENTE — tests d'exécution à effectuer avant compilation." else "État : TOOLCHAIN INCOMPLÈTE."
        lines += ""
        lines += "Important : la présence des fichiers ne garantit pas qu'ils sont exécutables sur Android/ARM64."
        lines += "DevAI ne télécharge rien et ne contacte pas GitHub."
        return ToolchainReport(ready, lines.joinToString("\n"), java, gradle, sdkDir)
    }

    fun installFromZip(source: File): Result<String> {
        return runCatching {
            if (!source.isFile) error("ZIP introuvable : ${source.absolutePath}")
            root.mkdirs()
            val staging = File(context.cacheDir, "toolchain-import-${System.currentTimeMillis()}")
            staging.deleteRecursively()
            staging.mkdirs()
            ZipInputStream(source.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (name.isBlank() || name.startsWith("/") || name.split('/').any { it == ".." }) {
                        error("Entrée ZIP dangereuse : $name")
                    }
                    val target = File(staging, name).canonicalFile
                    if (!target.path.startsWith(staging.canonicalPath + File.separator) && target != staging.canonicalFile) {
                        error("Chemin ZIP invalide : $name")
                    }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val importedRoot = normalizeRoot(staging)
            root.deleteRecursively()
            importedRoot.copyRecursively(root, overwrite = true)
            restoreExecutableBits(root)
            staging.deleteRecursively()
            "Toolchain importée.\n\n${inspect().text}"
        }
    }

    fun environment(report: ToolchainReport): Map<String, String> {
        val env = linkedMapOf<String, String>()
        report.java?.parentFile?.parentFile?.let { env["JAVA_HOME"] = it.absolutePath }
        report.sdk?.let {
            env["ANDROID_SDK_ROOT"] = it.absolutePath
            env["ANDROID_HOME"] = it.absolutePath
        }
        val path = System.getenv("PATH").orEmpty()
        val additions = buildList {
            report.java?.parentFile?.absolutePath?.let(::add)
            report.gradle?.parentFile?.absolutePath?.let(::add)
            report.sdk?.let { File(it, "platform-tools").absolutePath }?.let(::add)
            report.sdk?.let { File(it, "cmdline-tools/latest/bin").absolutePath }?.let(::add)
        }
        env["PATH"] = (additions + path).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
        return env
    }

    suspend fun verifyExecutables(report: ToolchainReport, runner: LocalCommandRunner): LocalCommandRunner.Result =
        withContext(Dispatchers.IO) {
            if (report.java == null || report.gradle == null) {
                return@withContext LocalCommandRunner.Result(-20, "[DevAI] Java ou Gradle manquant : impossible de tester le toolchain.")
            }
            val env = environment(report)
            val checks = listOf(
                "JAVA" to listOf(report.java.absolutePath, "-version"),
                "GRADLE" to listOf(report.gradle.absolutePath, "--version")
            )
            val out = StringBuilder("[DevAI] Vérification d'exécution du toolchain\n\n")
            for ((name, command) in checks) {
                val r = runner.run(command.first(), command.drop(1), root, env, 60_000L)
                out.append("[$name] exit=").append(r.exitCode).append('\n').append(r.output).append("\n")
                if (r.exitCode != 0) return@withContext LocalCommandRunner.Result(r.exitCode, out.toString())
            }
            LocalCommandRunner.Result(0, out.toString() + "[DevAI] Java et Gradle sont exécutables.\n")
        }

    private fun normalizeRoot(staging: File): File {
        val children = staging.listFiles()?.filter { it.name != "__MACOSX" } ?: emptyList()
        return if (children.size == 1 && children[0].isDirectory) children[0] else staging
    }

    private fun restoreExecutableBits(base: File) {
        base.walkTopDown().forEach { f ->
            if (f.isFile) {
                val n = f.name
                val parent = f.parentFile?.name.orEmpty()
                if (n in setOf("java", "javac", "gradle", "gradlew", "aapt2", "d8", "apksigner", "zipalign") || parent == "bin") {
                    runCatching { f.setExecutable(true, false) }
                }
            }
        }
    }

    private fun findNamedExecutable(base: File, name: String): File? {
        if (!base.isDirectory) return null
        val maxDepth = 7
        return base.walkTopDown()
            .onEnter { dir ->
                val relative = runCatching { base.toPath().relativize(dir.toPath()).nameCount }.getOrDefault(maxDepth + 1)
                relative <= maxDepth
            }
            .filter { it.isFile && it.name == name && it.parentFile?.name == "bin" }
            .firstOrNull { it.canExecute() }
    }

    private fun findAndroidSdk(base: File): File? {
        if (!base.isDirectory) return null
        val maxDepth = 6
        return base.walkTopDown()
            .onEnter { dir ->
                val relative = runCatching { base.toPath().relativize(dir.toPath()).nameCount }.getOrDefault(maxDepth + 1)
                relative <= maxDepth
            }
            .filter { it.isDirectory && File(it, "platforms").isDirectory && File(it, "build-tools").isDirectory }
            .sortedBy { it.absolutePath.length }
            .firstOrNull()
    }

    private fun firstExecutableChild(buildTools: File, name: String): File? {
        return buildTools.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.asSequence()
            ?.map { File(it, name) }
            ?.firstOrNull { it.isFile && it.canExecute() }
    }
}