package com.ghostty.android.terminal

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Manages a terminal session with a shell process.
 * Handles PTY communication and terminal state.
 */
class TerminalSession(
    private val shellCommand: String = "/system/bin/sh",
    private val homeDir: File? = null
) {
    /** Terminal size, passed to the PTY helper before it starts the shell. */
    var cols: Int = 80
    var rows: Int = 24

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    // Incremental output chunks, suitable for feeding a terminal renderer.
    private val _chunks = MutableSharedFlow<String>(replay = 64, extraBufferCapacity = 256)
    val chunks: SharedFlow<String> = _chunks.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Start the shell process.
     */
    fun start() {
        if (_isRunning.value) {
            Log.w(TAG, "Session already running")
            return
        }

        try {
            // Start the shell process. "-i" gives us a prompt even without a PTY.
            val processBuilder = ProcessBuilder(shellCommand, "-i")
            processBuilder.redirectErrorStream(true)
            processBuilder.environment()["TERM"] = "xterm-256color"
            processBuilder.environment()["COLORTERM"] = "truecolor"
            processBuilder.environment()["COLUMNS"] = cols.toString()
            processBuilder.environment()["LINES"] = rows.toString()
            homeDir?.let { home ->
                home.mkdirs()
                processBuilder.directory(home)
                processBuilder.environment()["HOME"] = home.absolutePath
                processBuilder.environment()["PWD"] = home.absolutePath
                processBuilder.environment()["ENV"] = File(home, ".mkshrc").absolutePath
            }

            process = processBuilder.start()
            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))

            _isRunning.value = true
            Log.d(TAG, "Started shell: $shellCommand")

            // Start reading output
            scope.launch {
                readOutput()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            cleanup()
        }
    }

    /**
     * Write input to the shell.
     */
    fun writeInput(text: String) {
        try {
            writer?.write(text)
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write input", e)
        }
    }

    /**
     * Send a line of input to the shell.
     */
    fun writeLine(line: String) {
        writeInput(line + "\n")
    }

    /**
     * Read output from the shell continuously.
     */
    private suspend fun readOutput() {
        try {
            val buffer = CharArray(8192)
            val outputBuilder = StringBuilder(_output.value)

            while (_isRunning.value) {
                val count = reader?.read(buffer) ?: break
                if (count <= 0) break

                val text = String(buffer, 0, count)
                outputBuilder.append(text)

                // Update the output state
                _output.value = outputBuilder.toString()

                // Emit the incremental chunk for the terminal renderer.
                _chunks.tryEmit(text)

                // Limit output buffer size to prevent memory issues
                if (outputBuilder.length > MAX_OUTPUT_SIZE) {
                    outputBuilder.delete(0, outputBuilder.length - MAX_OUTPUT_SIZE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading output", e)
        } finally {
            cleanup()
        }
    }

    /**
     * Stop the session and clean up resources.
     */
    fun stop() {
        // Never close the pipes on the main thread: BufferedReader.close()
        // waits for the reader coroutine's lock, and that coroutine is blocked
        // in read(). That deadlock otherwise causes an ANR on activity destroy.
        scope.launch { cleanup() }
    }

    private fun cleanup() {
        _isRunning.value = false

        // Detach the fields first so a concurrent start() cannot be clobbered.
        val oldProcess = process
        val oldWriter = writer
        val oldReader = reader
        process = null
        writer = null
        reader = null

        // Destroy the process first so the blocking read unblocks before close.
        try {
            oldProcess?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying process", e)
        }

        try {
            oldWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing writer", e)
        }

        try {
            oldReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing reader", e)
        }
    }

    companion object {
        private const val TAG = "TerminalSession"
        private const val MAX_OUTPUT_SIZE = 100_000 // Keep last 100k characters
    }
}
