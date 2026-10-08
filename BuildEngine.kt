package com.devai.core

import java.io.File

class BuildEngine(private val runner: LocalCommandRunner, private val toolchain: ToolchainManager) {
    data class BuildResult(val result: LocalCommandRunner.Result, val apk: File?)

    suspend fun build(projectDir: File): BuildResult {
        val report = toolchain.inspect()
        if (!report.ready) return BuildResult(LocalCommandRunner.Result(-10, "[DevAI] Toolchain Android non installée ou incomplète.\n\n${report.text}"), null)
        val verify = toolchain.verifyExecutables(report, runner)
        if (verify.exitCode != 0) return BuildResult(verify, null)
        val gradle = report.gradle ?: return BuildResult(LocalCommandRunner.Result(-11, "[DevAI] Gradle local introuvable."), null)
        if (!projectDir.isDirectory) return BuildResult(LocalCommandRunner.Result(-12, "[DevAI] Projet introuvable : ${projectDir.absolutePath}"), null)
        val settings = File(projectDir, "settings.gradle.kts")
        val settingsGroovy = File(projectDir, "settings.gradle")
        val build = File(projectDir, "build.gradle.kts")
        val buildGroovy = File(projectDir, "build.gradle")
        if (!settings.exists() && !settingsGroovy.exists()) return BuildResult(LocalCommandRunner.Result(-13, "[DevAI] settings.gradle(.kts) absent dans le projet."), null)
        if (!build.exists() && !buildGroovy.exists()) return BuildResult(LocalCommandRunner.Result(-14, "[DevAI] build.gradle(.kts) absent dans le projet."), null)

        val r = runner.run(gradle.absolutePath, listOf("--no-daemon", "--offline", "assembleDebug"), projectDir, toolchain.environment(report), 900_000L)
        val apk = if (r.exitCode == 0) findApk(projectDir) else null
        val suffix = if (apk != null) "\n[DevAI] APK trouvé : ${apk.absolutePath} (${apk.length()} octets)" else if (r.exitCode == 0) "\n[DevAI] Build terminé mais aucun APK n'a été trouvé." else ""
        return BuildResult(r.copy(output = r.output + suffix), apk)
    }

    private fun findApk(projectDir: File): File? = projectDir.walkTopDown()
        .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        .filter { it.absolutePath.contains("build${File.separator}outputs${File.separator}apk") }
        .maxByOrNull { it.lastModified() }
}
