package com.ghostty.android

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.viewinterop.AndroidView
import com.ghostty.android.renderer.TerminalEventListener
import com.ghostty.android.renderer.GhosttyGLSurfaceView
import com.ghostty.android.terminal.GhosttyBridge
import com.ghostty.android.terminal.TerminalSession
import com.ghostty.android.testing.TestRunner
import com.ghostty.android.testing.TestSuite
import com.ghostty.android.ui.theme.GhosttyTheme

class MainActivity : ComponentActivity() {

    private lateinit var ghosttyBridge: GhosttyBridge
    private lateinit var terminalSession: TerminalSession
    private var glSurfaceView: GhosttyGLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launch theme provides the splash window; switch to the normal
        // theme before the activity draws its content.
        setTheme(R.style.Theme_Ghostty)
        super.onCreate(savedInstanceState)

        // Keep screen on while terminal is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize Ghostty bridge
        ghosttyBridge = GhosttyBridge.getInstance()
        // TODO: Enable when JNI key encoder is implemented
        // ghosttyBridge.createKeyEncoder()
        // Create terminal session. The PTY helper is packaged as a native lib
        // so it is executable from nativeLibraryDir.
        val ptyHelper = File(applicationInfo.nativeLibraryDir, "libpty_shell.so")
        val homeDir = getDir("home", MODE_PRIVATE)

        // Create a default mksh profile on first run. Users can edit
        // app_home/.mkshrc to change the prompt, aliases, history, etc.
        val mkshrc = File(homeDir, ".mkshrc")
        if (!mkshrc.exists()) {
            mkshrc.writeText(
                """
                # Ghostty Android shell profile — edit freely.
                HISTFILE="${'$'}HOME/.mksh_history"
                HISTSIZE=1000
                alias ll='ls -l'
                alias la='ls -la'
                alias l='ls -l'
                alias home='cd ~'
                PS1='${'$'}(basename "${'$'}PWD") ${'$'} '
                """.trimIndent() + "\n"
            )
        }

        terminalSession = TerminalSession(ptyHelper.absolutePath, homeDir)

        enableEdgeToEdge()

        setContent {
            GhosttyTheme {
                TerminalScreen(
                    onGLSurfaceViewCreated = { view ->
                        glSurfaceView = view
                        var started = false
                        view.setEventListener(object : TerminalEventListener {
                            override fun onSurfaceReady(cols: Int, rows: Int) {
                                if (started) return
                                started = true
                                terminalSession.cols = cols
                                terminalSession.rows = rows
                                terminalSession.start()
                                // Close the app when the shell exits.
                                lifecycleScope.launch {
                                    terminalSession.isRunning.dropWhile { !it }.first { !it }
                                    finishAndRemoveTask()
                                }
                                lifecycleScope.launch(Dispatchers.IO) {
                                    terminalSession.chunks.collect { chunk ->
                                        view.queueEvent {
                                            view.getRenderer().processInput(chunk)
                                        }
                                        view.requestRender()
                                    }
                                }
                            }

                            override fun onKeyboardOverlayProgress(offset: Float, maxOffset: Float) {}
                            override fun onKeyboardOverlayStateChanged(expanded: Boolean) {}
                        })
                    }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause the GL rendering thread
        glSurfaceView?.onPauseView()
    }

    override fun onResume() {
        super.onResume()
        // Resume the GL rendering thread
        glSurfaceView?.onResumeView()
    }

    override fun onDestroy() {
        super.onDestroy()
        terminalSession.stop()
        ghosttyBridge.cleanup()
    }

    /**
     * Handle mouse-wheel scrolling at the activity level so it works
     * regardless of which view currently has focus.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vertical != 0f) {
                glSurfaceView?.handleWheelScroll(vertical)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * Route hardware keys (e.g. scrcpy's keyboard) into the shell, since this
     * device has no IME installed.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val input = keyToInput(event)
            if (input != null) {
                terminalSession.writeInput(input)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun keyToInput(event: KeyEvent): String? {
        // Ctrl+A .. Ctrl+Z -> 0x01 .. 0x1A
        if (event.isCtrlPressed &&
            event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
        ) {
            return (event.keyCode - KeyEvent.KEYCODE_A + 1).toChar().toString()
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return "\r"
            KeyEvent.KEYCODE_DEL -> return "\u007F"
            KeyEvent.KEYCODE_FORWARD_DEL -> return "\u001B[3~"
            KeyEvent.KEYCODE_TAB -> return "\t"
            KeyEvent.KEYCODE_ESCAPE -> return "\u001B"
            KeyEvent.KEYCODE_DPAD_UP -> return "\u001B[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> return "\u001B[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> return "\u001B[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> return "\u001B[D"
            KeyEvent.KEYCODE_MOVE_HOME -> return "\u001B[H"
            KeyEvent.KEYCODE_MOVE_END -> return "\u001B[F"
            KeyEvent.KEYCODE_PAGE_UP -> return "\u001B[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> return "\u001B[6~"
            KeyEvent.KEYCODE_INSERT -> return "\u001B[2~"
            // Let the system keep these.
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_MENU -> return null
        }

        val c = event.unicodeChar
        if (c == 0) return null
        val text = String(Character.toChars(c))
        return if (event.isAltPressed) "\u001B$text" else text
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onGLSurfaceViewCreated: (GhosttyGLSurfaceView) -> Unit
) {
    Scaffold { paddingValues ->
        // Use AndroidView to embed the native OpenGL surface view
        AndroidView(
            factory = { context ->
                GhosttyGLSurfaceView(context).also { view ->
                    // Notify the activity that the view has been created
                    onGLSurfaceViewCreated(view)

                    // Set up terminal size (will be calculated based on view size)
                    // For now, use default terminal size
                    view.setTerminalSize(80, 24)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestModeScreen(
    testRunner: TestRunner?,
    onExitTestMode: () -> Unit,
    onGLSurfaceViewCreated: (GhosttyGLSurfaceView) -> Unit,
    testId: String?,
    onTestsStarted: () -> Unit = {}
) {
    android.util.Log.i("TestModeScreen", "Compose: testRunner=$testRunner, testId=$testId")

    val isRunning by testRunner?.isRunning?.collectAsState() ?: remember { mutableStateOf(false) }
    val testResults by testRunner?.testResults?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val currentTest by testRunner?.currentTest?.collectAsState() ?: remember { mutableStateOf(null) }
    val currentTestIndex by testRunner?.currentTestIndexFlow?.collectAsState() ?: remember { mutableStateOf(0) }
    val totalTests by testRunner?.totalTests?.collectAsState() ?: remember { mutableStateOf(0) }

    // Initialize tests when both testRunner and testId are available
    if (testRunner != null && testId != null) {
        LaunchedEffect(testId, testRunner) {
            android.util.Log.i("TestModeScreen", "LaunchedEffect: initializing tests testId=$testId")
            testRunner.initializeTestById(testId)
            onTestsStarted()
        }
    } else {
        android.util.Log.w("TestModeScreen", "Waiting for initialization: testRunner=$testRunner, testId=$testId")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Visual Regression Tests") },
                actions = {
                    IconButton(onClick = onExitTestMode, enabled = !isRunning) {
                        Text("EXIT", style = MaterialTheme.typography.labelSmall)
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Test progress indicator
                    if (totalTests > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Test ${currentTestIndex + 1} of $totalTests",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    }

                    // Navigation buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { testRunner?.previousTest() },
                            enabled = !isRunning && testRunner?.hasPreviousTest() == true,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Previous")
                        }
                        Button(
                            onClick = { testRunner?.nextTest() },
                            enabled = !isRunning && testRunner?.hasNextTest() == true,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Next")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Terminal view (takes most of the space)
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    factory = { context ->
                        GhosttyGLSurfaceView(context).also { view ->
                            onGLSurfaceViewCreated(view)
                            view.setTerminalSize(80, 24)

                            // Configure bottom offset for keyboard gesture testing
                            view.setMaxBottomOffset(300f)
                            view.setEventListener(object : TerminalEventListener {
                                override fun onSurfaceReady(cols: Int, rows: Int) {
                                    android.util.Log.i("Terminal", "Surface ready: ${cols}x${rows}")
                                }
                                override fun onKeyboardOverlayProgress(offset: Float, maxOffset: Float) {
                                    android.util.Log.d("KeyboardOverlay", "progress=$offset, max=$maxOffset")
                                }
                                override fun onKeyboardOverlayStateChanged(expanded: Boolean) {
                                    android.util.Log.i("KeyboardOverlay", "expanded=$expanded")
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Overlay showing current test
                currentTest?.let { test ->
                    Surface(
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.TopEnd)
                            .padding(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "Running: ${test.id}",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Test results summary
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Results: ${testResults.size} tests",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (testResults.isNotEmpty()) {
                        val passed = testResults.count { it.status == com.ghostty.android.testing.TestStatus.PASSED }
                        val failed = testResults.count { it.status == com.ghostty.android.testing.TestStatus.FAILED }
                        Text(
                            text = "Passed: $passed, Failed: $failed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed > 0) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
