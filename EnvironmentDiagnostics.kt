package com.devai.core

import java.io.File

class EnvironmentDiagnostics {
    data class Report(val text: String)

    fun inspect(): Report {
        val env = System.getenv()
        val path = env["PATH"].orEmpty()
        val javaHome = env["JAVA_HOME"]
        val sdk = env["ANDROID_SDK_ROOT"] ?: env["ANDROID_HOME"]
        val lines = mutableListOf<String>()
        lines += "DevAI V1.8 • environnement local"
        lines += ""
        lines += "Architecture : ${System.getProperty("os.arch").orEmpty().ifBlank { "inconnue" }}"
        lines += "OS : ${System.getProperty("os.name").orEmpty().ifBlank { "inconnu" }}"
        lines += "Runtime : ${System.getProperty("java.runtime.name").orEmpty().ifBlank { "inconnu" }}"
        lines += "Java système : ${checkCommand("java", path)}"
        lines += "Gradle système : ${checkCommand("gradle", path)}"
        lines += "Javac système : ${checkCommand("javac", path)}"
        lines += ""
        lines += "JAVA_HOME : ${javaHome ?: "non défini"}"
        lines += "ANDROID_SDK_ROOT/HOME : ${sdk ?: "non défini"}"
        lines += ""
        lines += "SDK Android détecté : ${sdk?.let { directoryState(File(it)) } ?: "non détecté"}"
        if (sdk != null) {
            lines += "  platforms : ${directoryState(File(sdk, "platforms"))}"
            lines += "  build-tools : ${directoryState(File(sdk, "build-tools"))}"
            lines += "  platform-tools : ${directoryState(File(sdk, "platform-tools"))}"
        }
        lines += ""
        lines += "PATH : ${if (path.isBlank()) "non défini" else path}"
        lines += ""
        lines += "Conclusion : diagnostic local uniquement."
        lines += "Aucun téléchargement et aucun accès GitHub n'est effectué."
        return Report(lines.joinToString("\n"))
    }

    private fun checkCommand(name: String, path: String): String {
        val candidates = path.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it, name) }
        val found = candidates.firstOrNull { it.isFile && it.canExecute() }
        return if (found != null) "trouvé : ${found.absolutePath}" else "non trouvé dans PATH"
    }

    private fun directoryState(file: File): String = when {
        !file.exists() -> "absent"
        !file.isDirectory -> "présent mais invalide"
        else -> "présent"
    }
}
