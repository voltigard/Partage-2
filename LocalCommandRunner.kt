package com.devai.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Small local process layer for the future build engine.
 * It does not contact GitHub or any network service.
 */
class LocalCommandRunner {
    data class Result(val exitCode: Int, val output: String)

    suspend fun run(
        executable: String,
        arguments: List<String> = emptyList(),
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutMs: Long = 120_000L
    ): Result = withContext(Dispatchers.IO) {
        val command = ArrayList<String>(arguments.size + 1).apply {
            add(executable)
            addAll(arguments)
        }
        val process = try {
            ProcessBuilder(command).apply {
                redirectErrorStream(true)
                if (workingDirectory != null) directory(workingDirectory)
                environment().putAll(environment)
            }.start()
        } catch (e: Exception) {
            return@withContext Result(
                -2,
                "[DevAI] Impossible de lancer la commande : ${command.joinToString(" ")}\n" +
                    "${e::class.simpleName}: ${e.message.orEmpty()}\n"
            )
        }
        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + timeoutMs

        while (process.isAlive) {
            while (reader.ready()) output.append(reader.readLine()).append('\n')
            if (System.currentTimeMillis() >= deadline) {
                process.destroyForcibly()
                output.append("\n[DevAI] Command timed out after ${timeoutMs} ms.\n")
                return@withContext Result(-1, output.toString())
            }
            Thread.sleep(20)
        }

        while (reader.ready()) output.append(reader.readLine()).append('\n')
        Result(process.exitValue(), output.toString())
    }
}
