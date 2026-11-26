package com.example.andrea.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.andrea.accessibility.ScreenActionService
import com.example.andrea.ai.LocalAgentBrain
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class LocalAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return LocalAssistantSession(this)
    }
}

class LocalAssistantSession(context: Context) : VoiceInteractionSession(context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusText: TextView? = null
    private var modelOutputText: TextView? = null
    private var inputEditText: EditText? = null
    private var sendBtn: Button? = null
    private var speechRecognizer: SpeechRecognizer? = null

    // Lazy-load the LLM so it doesn't eat RAM until you actually trigger the assistant
    private val brain by lazy {
        val modelFile = File(context.filesDir, "model.litertlm")
        LocalAgentBrain(modelFile.absolutePath)
    }

    // 1. Creates the floating bottom overlay pill when you summon the assistant
    override fun onCreateContentView(): View {
        val container = FrameLayout(context).apply {
            setPadding(32, 32, 32, 48)
        }

        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 32)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F51C1D22"))
                cornerRadius = 56f
            }
        }

        // Top Status Text
        statusText = TextView(context).apply {
            text = "Listening..."
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(8, 4, 8, 8)
        }
        cardLayout.addView(statusText)

        // Raw Model Output Text (Visible to show streaming model responses)
        modelOutputText = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#A0A0A0"))
            textSize = 13f
            setPadding(8, 0, 8, 12)
            visibility = View.GONE
        }
        cardLayout.addView(modelOutputText)

        // Bottom Action Bar: Keyboard | Input Field | Send | Mic | Close
        val controlBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val keyboardBtn = Button(context).apply {
            text = "⌨"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = 48f
            }
            layoutParams = LinearLayout.LayoutParams(110, 110).apply {
                setMargins(0, 0, 16, 0)
            }
        }

        inputEditText = EditText(context).apply {
            hint = "Type a command..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#22FFFFFF"))
                cornerRadius = 32f
            }
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 16, 0)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    sendTypedCommand()
                    true
                } else false
            }
        }

        sendBtn = Button(context).apply {
            text = "➔"
            textSize = 16f
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4488FF"))
                cornerRadius = 48f
            }
            layoutParams = LinearLayout.LayoutParams(110, 110).apply {
                setMargins(0, 0, 16, 0)
            }
            setOnClickListener { sendTypedCommand() }
        }

        val micBtn = Button(context).apply {
            text = "🎙"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33FFFFFF"))
                cornerRadius = 48f
            }
            layoutParams = LinearLayout.LayoutParams(110, 110).apply {
                setMargins(0, 0, 16, 0)
            }
            setOnClickListener {
                hideTextInput()
                startListening()
            }
        }

        val closeBtn = Button(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 0)
            background = null
            layoutParams = LinearLayout.LayoutParams(90, 90)
            setOnClickListener { finish() }
        }

        keyboardBtn.setOnClickListener {
            toggleTextInput()
        }

        controlBar.addView(keyboardBtn)
        controlBar.addView(inputEditText)
        controlBar.addView(sendBtn)
        controlBar.addView(micBtn)
        controlBar.addView(closeBtn)

        cardLayout.addView(controlBar)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        container.addView(cardLayout, params)
        return container
    }

    private fun toggleTextInput() {
        if (inputEditText?.visibility == View.VISIBLE) {
            hideTextInput()
        } else {
            showTextInput()
        }
    }

    private fun showTextInput() {
        inputEditText?.visibility = View.VISIBLE
        sendBtn?.visibility = View.VISIBLE
        inputEditText?.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(inputEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideTextInput() {
        inputEditText?.visibility = View.GONE
        sendBtn?.visibility = View.GONE
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(inputEditText?.windowToken, 0)
    }

    private fun sendTypedCommand() {
        val text = inputEditText?.text?.toString()?.trim() ?: ""
        if (text.isNotEmpty()) {
            statusText?.text = "\"$text\""
            inputEditText?.setText("")
            hideTextInput()
            runAgentLoop(text)
        }
    }

    // 2. Starts listening when summoned
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startListening()
    }

    private fun startListening() {
        statusText?.text = "Listening..."
        modelOutputText?.text = ""
        modelOutputText?.visibility = View.GONE

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(partialResults: Bundle?) {
                    val partialText = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()

                    if (!partialText.isNullOrBlank()) {
                        statusText?.text = "\"$partialText\""
                    }
                }

                override fun onResults(results: Bundle?) {
                    val spokenText = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()

                    if (!spokenText.isNullOrBlank()) {
                        statusText?.text = "\"$spokenText\""
                        runAgentLoop(spokenText)
                    } else {
                        statusText?.text = "No speech heard. Tap 🎙 or ⌨ to type"
                    }
                }

                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't hear speech (Error 7). Tap 🎙 or ⌨"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout (Error 6). Tap 🎙 or ⌨"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error (Error 3)"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission missing (Error 9)"
                        else -> "Speech error $error. Tap 🎙 or ⌨"
                    }
                    statusText?.text = msg
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { statusText?.text = "Thinking..." }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun extractJsonObject(input: String): JSONObject? {
        val cleaned = input.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            val jsonStr = cleaned.substring(start, end + 1)
            return try {
                JSONObject(jsonStr)
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    // Google Voice Access style Fast-Track Rule Engine
    private fun tryFastTrackAction(userGoal: String, accessibility: ScreenActionService): Boolean {
        val goal = userGoal.trim().lowercase()

        when {
            goal == "home" || goal == "go home" || goal == "take me home" -> {
                statusText?.text = "Going home..."
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "Fast-track: home"
                accessibility.goHome()
                return true
            }
            goal == "back" || goal == "go back" -> {
                statusText?.text = "Going back..."
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "Fast-track: back"
                accessibility.goBack()
                return true
            }
            goal == "recents" || goal == "open recents" || goal == "recent apps" -> {
                statusText?.text = "Opening recents..."
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "Fast-track: recents"
                accessibility.openRecents()
                return true
            }
            goal == "notifications" || goal == "open notifications" -> {
                statusText?.text = "Opening notifications..."
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "Fast-track: notifications"
                accessibility.openNotifications()
                return true
            }
            goal == "scroll down" || goal == "scroll" -> {
                statusText?.text = "Scrolling down..."
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "Fast-track: scroll_down"
                accessibility.scrollDown()
                return true
            }
            goal == "scroll up" -> {
                statusText?.text = "Scrolling up..."
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "Fast-track: scroll_up"
                accessibility.scrollUp()
                return true
            }
            goal == "swipe left" -> {
                statusText?.text = "Swiping left..."
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "Fast-track: swipe_left"
                accessibility.swipeLeft()
                return true
            }
            goal == "swipe right" -> {
                statusText?.text = "Swiping right..."
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "Fast-track: swipe_right"
                accessibility.swipeRight()
                return true
            }
            goal == "swipe up" -> {
                statusText?.text = "Swiping up..."
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "Fast-track: swipe_up"
                accessibility.swipeUp()
                return true
            }
            goal == "swipe down" -> {
                statusText?.text = "Swiping down..."
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "Fast-track: swipe_down"
                accessibility.swipeDown()
                return true
            }
        }

        val openAppRegex = Regex("""^(?:open|launch|start)\s+(.+)$""")
        val openMatch = openAppRegex.find(goal)
        if (openMatch != null) {
            val appName = openMatch.groupValues[1].trim()
            statusText?.text = "Opening $appName..."
            modelOutputText?.visibility = View.VISIBLE
            modelOutputText?.text = "Fast-track: open_app(\"$appName\")"
            val success = accessibility.openApp(appName)
            if (!success) {
                statusText?.text = "App \"$appName\" not found"
            }
            return true
        }

        val tapRegex = Regex("""^(?:tap|click|press)\s+(.+)$""")
        val tapMatch = tapRegex.find(goal)
        if (tapMatch != null) {
            val target = tapMatch.groupValues[1].trim()
            val idNumber = target.toIntOrNull()
            if (idNumber != null) {
                statusText?.text = "Clicking element $idNumber..."
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "Fast-track: click(id=$idNumber)"
                accessibility.clickNode(idNumber)
                return true
            } else {
                val clicked = accessibility.clickByLabel(target)
                if (clicked) {
                    statusText?.text = "Clicked \"$target\""
                    modelOutputText?.visibility = View.VISIBLE
                    modelOutputText?.text = "Fast-track: clickByLabel(\"$target\")"
                    return true
                }
            }
        }

        val typeRegex = Regex("""^(?:type|input|write)\s+(.+)$""")
        val typeMatch = typeRegex.find(goal)
        if (typeMatch != null) {
            val textToType = typeMatch.groupValues[1].trim()
            statusText?.text = "Typing \"$textToType\"..."
            modelOutputText?.visibility = View.VISIBLE
            modelOutputText?.text = "Fast-track: type(\"$textToType\")"
            accessibility.typeTextFocused(textToType)
            return true
        }

        return false
    }

    // 3. The Sense -> Think -> Act Loop
    private fun runAgentLoop(userGoal: String) {
        scope.launch(Dispatchers.Default) {
            val accessibility = ScreenActionService.instance
            if (accessibility == null) {
                withContext(Dispatchers.Main) {
                    statusText?.text = "Enable Accessibility Service first!"
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                return@launch
            }

            // 1. Try Google Voice Access style Fast-Track Rule Engine (<10ms execution)
            val handledByFastTrack = withContext(Dispatchers.Main) {
                tryFastTrackAction(userGoal, accessibility)
            }
            if (handledByFastTrack) return@launch

            // 2. Fallback to SLM AI Model
            val screenUI = accessibility.dumpScreenUI()

            withContext(Dispatchers.Main) {
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "SLM Output: ..."
            }

            val rawResponse = try {
                brain.decideNextAction(userGoal, screenUI) { partialText ->
                    scope.launch(Dispatchers.Main) {
                        modelOutputText?.visibility = View.VISIBLE
                        modelOutputText?.text = "SLM Output: $partialText"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText?.text = "Error generating action"
                    modelOutputText?.text = "Error: ${e.message}"
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                modelOutputText?.visibility = View.VISIBLE
                modelOutputText?.text = "SLM Output: $rawResponse"
            }

            val json = extractJsonObject(rawResponse)
            if (json == null) {
                withContext(Dispatchers.Main) {
                    statusText?.text = "Could not parse SLM JSON"
                }
                return@launch
            }

            val action = json.optString("action")

            when (action) {
                "open_app" -> {
                    val appName = json.optString("name")
                    withContext(Dispatchers.Main) {
                        statusText?.text = "Opening $appName..."
                        accessibility.openApp(appName)
                    }
                }
                "click" -> {
                    val id = json.optInt("id")
                    withContext(Dispatchers.Main) {
                        statusText?.text = "Clicking element $id..."
                        accessibility.clickNode(id)
                    }
                }
                "type" -> {
                    val id = json.optInt("id")
                    val text = json.optString("text")
                    withContext(Dispatchers.Main) {
                        statusText?.text = "Typing \"$text\"..."
                        accessibility.typeText(id, text)
                    }
                }
                "scroll_down", "scroll" -> {
                    withContext(Dispatchers.Main) {
                        statusText?.text = "Scrolling down..."
                        accessibility.scrollDown()
                    }
                }
                "scroll_up" -> {
                    withContext(Dispatchers.Main) {
                        statusText?.text = "Scrolling up..."
                        accessibility.scrollUp()
                    }
                }
                "home" -> {
                    withContext(Dispatchers.Main) {
                        statusText?.text = "Going home..."
                        accessibility.goHome()
                    }
                }
                "back" -> {
                    withContext(Dispatchers.Main) {
                        statusText?.text = "Going back..."
                        accessibility.goBack()
                    }
                }
                "recents" -> {
                    withContext(Dispatchers.Main) {
                        statusText?.text = "Opening recents..."
                        accessibility.openRecents()
                    }
                }
                "done" -> {
                    withContext(Dispatchers.Main) {
                        statusText?.text = "Done!"
                    }
                }
                else -> {
                    withContext(Dispatchers.Main) {
                        statusText?.text = "Action: $action"
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        scope.cancel()
    }
}
