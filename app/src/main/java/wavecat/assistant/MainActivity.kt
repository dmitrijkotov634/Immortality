package wavecat.assistant

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import wavecat.assistant.databinding.ActivityMainBinding
import wavecat.assistant.databinding.TextInputBinding
import wavecat.assistant.openai.OpenAIClient
import wavecat.assistant.openai.models.CompletionsInput
import wavecat.assistant.openai.models.ImageGenerationInput
import wavecat.assistant.openai.models.Message
import wavecat.assistant.tokenizer.GPT2Tokenizer
import java.util.*
import kotlin.math.min


@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), RecognitionListener {

    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val preferences by lazy { getSharedPreferences("app", MODE_PRIVATE) }

    private val openAIClient by lazy { OpenAIClient(preferences.getString("key", "").toString()) }
    private val speechRecognizer by lazy { SpeechRecognizer.createSpeechRecognizer(this) }

    private lateinit var tokenizer: GPT2Tokenizer

    private var currentJob: Job? = null

    private val defaultMessages = listOf(
        Message(
            "user",
            buildInstruction(startPrompt = "Type welcome")
        ),
        Message(
            "assistant",
            "say(\"Welcome\")"
        )
    )

    private val messages: MutableList<Message> = defaultMessages.toMutableList()

    private val debugLib = CustomDebugLib()

    private var globals: Globals = JsePlatform.standardGlobals().apply {
        load(debugLib)

        set("say", object : OneArgFunction() {
            override fun call(text: LuaValue?): LuaValue {
                runBlocking {
                    val prepared = text?.tojstring() ?: ""

                    withContext(Dispatchers.Main) {
                        binding.message.text = prepared
                        viewContextCheck()
                    }

                    delay(min(prepared.length * 50, 8000).toLong())
                }

                return NIL
            }
        })

        set("emotion", object : OneArgFunction() {
            override fun call(emotion: LuaValue?): LuaValue {
                if (emotion == null) return NIL

                CoroutineScope(Dispatchers.Main).launch {
                    binding.emotion.setImageResource(
                        when (emotion.tojstring()) {
                            "anger" -> R.drawable.anger
                            "fear" -> R.drawable.fear
                            "happiness" -> R.drawable.happiness
                            "sadness" -> R.drawable.sadness
                            "astonishment" -> R.drawable.astonishment
                            else -> R.drawable.happiness
                        }
                    )
                    viewContextCheck()
                }

                return NIL
            }
        })

        set("picture", object : OneArgFunction() {
            override fun call(prompt: LuaValue?): LuaValue {
                if (prompt == null) return NIL

                runBlocking {
                    withContext(Dispatchers.Main) {
                        showProgress("Image generation using OpenAI")
                    }

                    val bitmap = openAIClient.makeImageGeneration(
                        ImageGenerationInput(
                            prompt.tojstring(),
                            1,
                            "512x512"
                        )
                    )

                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.emotion.setImageBitmap(bitmap)
                        viewContextCheck()
                        hideProgress()
                    }
                }

                return NIL
            }
        })

        set("torch",
            object : OneArgFunction() {
                override fun call(status: LuaValue?): LuaValue {
                    if (status == null) return NIL

                    CoroutineScope(Dispatchers.Main).launch {
                        val cameraManager =
                            getSystemService(Context.CAMERA_SERVICE) as CameraManager
                        try {
                            val cameraId = cameraManager.cameraIdList[0]
                            cameraManager.setTorchMode(cameraId, status.toboolean())
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    return NIL
                }
            })

        set("media",
            object : OneArgFunction() {
                override fun call(key: LuaValue?): LuaValue {
                    CoroutineScope(Dispatchers.Main).launch {
                        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

                        val button = when (key?.tojstring()) {
                            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                            else -> KeyEvent.KEYCODE_MEDIA_PAUSE
                        }

                        val time = SystemClock.uptimeMillis()
                        val downEvent = KeyEvent(
                            time,
                            time,
                            KeyEvent.ACTION_DOWN,
                            button,
                            0
                        )

                        audioManager.dispatchMediaKeyEvent(downEvent)

                        val upEvent = KeyEvent(
                            time,
                            time,
                            KeyEvent.ACTION_UP,
                            button,
                            0
                        )

                        audioManager.dispatchMediaKeyEvent(upEvent)
                    }

                    return NIL
                }
            })

        set("volume",
            object : OneArgFunction() {
                override fun call(action: LuaValue?): LuaValue {
                    if (action == null) return NIL

                    CoroutineScope(Dispatchers.Main).launch {
                        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            if (action.tojstring() == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI
                        )
                    }

                    return NIL
                }
            })

        set("app",
            object : OneArgFunction() {
                private var attempts = 0

                @Suppress("DEPRECATION")
                override fun call(name: LuaValue?): LuaValue {
                    if (name == null) return NIL

                    val preparedName = name.tojstring().lowercase()

                    runBlocking {
                        val mainIntent = Intent(Intent.ACTION_MAIN, null)
                        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)

                        val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            packageManager.queryIntentActivities(
                                mainIntent,
                                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                            )
                        } else {
                            packageManager.queryIntentActivities(
                                mainIntent,
                                PackageManager.GET_META_DATA
                            )
                        }

                        for (info in appInfo) {
                            val appName = info.loadLabel(packageManager).toString().lowercase()

                            if (appName.contains(preparedName)) {
                                val intent = Intent(Intent.ACTION_MAIN)

                                intent.addCategory(Intent.CATEGORY_LAUNCHER)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                                intent.component = ComponentName(
                                    info.activityInfo.packageName,
                                    info.activityInfo.name
                                )

                                startActivity(intent)

                                attempts = 0
                                return@runBlocking
                            }
                        }

                        if (attempts++ > 2) {
                            attempts = 0
                            return@runBlocking
                        }

                        withContext(Dispatchers.Main) {
                            showProgress("App name clarification using ChatGPT #$attempts")
                        }

                        val response = openAIClient.makeCompletion(
                            CompletionsInput(
                                "gpt-3.5-turbo",
                                listOf(
                                    Message(
                                        "user",
                                        buildInstruction("Choose only from this list: " + appInfo.joinToString {
                                            it.loadLabel(
                                                packageManager
                                            ).toString()
                                        }, "Open app $name")
                                    )
                                )
                            )
                        )

                        withContext(Dispatchers.Main) {
                            binding.log.text = response.choices[0].message.content
                            hideProgress()
                        }

                        exec(response.choices[0].message.content)
                    }

                    return NIL
                }
            })

        set("contact",
            object : OneArgFunction() {
                private var attempts = 0

                override fun call(name: LuaValue?): LuaValue {
                    if (name == null) return NIL

                    val preparedName = name.tojstring().lowercase()

                    runBlocking {
                        for (contact in contacts) {
                            if (contact.name.lowercase().contains(preparedName)) {
                                val intent = Intent(Intent.ACTION_VIEW)
                                val uri: Uri = Uri.withAppendedPath(
                                    ContactsContract.Contacts.CONTENT_URI,
                                    java.lang.String.valueOf(contact.id)
                                )
                                intent.data = uri
                                startActivity(intent)

                                attempts = 0
                                return@runBlocking
                            }
                        }

                        if (attempts++ > 2) {
                            attempts = 0
                            return@runBlocking
                        }

                        withContext(Dispatchers.Main) {
                            showProgress("Contact name clarification using ChatGPT #$attempts")
                        }

                        val response = openAIClient.makeCompletion(
                            CompletionsInput(
                                "gpt-3.5-turbo",
                                listOf(
                                    Message(
                                        "user",
                                        buildInstruction("Choose only from this list: " + contacts.joinToString {
                                            it.name
                                        }, "Call $name")
                                    )
                                )
                            )
                        )

                        withContext(Dispatchers.Main) {
                            binding.log.text = response.choices[0].message.content
                            hideProgress()
                        }

                        exec(response.choices[0].message.content)
                    }

                    return NIL
                }
            })

        set("wait",
            object : OneArgFunction() {
                override fun call(time: LuaValue?): LuaValue {
                    runBlocking {
                        time?.tolong()?.let { delay(it) }
                    }

                    return NIL
                }
            })
    }

    private val contacts by lazy { getContactList(mutableListOf()) }

    private var maxTokens = 3000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        tokenizer = GPT2Tokenizer(assets)

        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                1
            )
        }

        speechRecognizer.setRecognitionListener(this)

        binding.floatingActionButton.setOnClickListener { startSpeechRecognizer() }
        binding.floatingActionButton2.setOnClickListener { showDialog() }

        if (preferences.getString("key", "").isNullOrEmpty()) {
            val inputBinding = TextInputBinding.inflate(layoutInflater)
            MaterialAlertDialogBuilder(this)
                .setTitle("OpenAI Key")
                .setView(inputBinding.root)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    preferences
                        .edit()
                        .putString("key", inputBinding.text.text.toString())
                        .apply()

                    openAIClient.apiKey = inputBinding.text.text.toString()
                }
                .show()
        }

        val clipboard: ClipboardManager =
            getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

        binding.log.setOnClickListener {
            clipboard.setPrimaryClip(ClipData.newPlainText("text", binding.log.text))
        }

        binding.message.setOnClickListener {
            clipboard.setPrimaryClip(ClipData.newPlainText("text", binding.message.text))
        }
    }

    private fun buildInstruction(
        appInstruction: String = "",
        startPrompt: String
    ): String =
        "I want you to act as a Assistant console. I will type question and you will reply Lua code. I want you to only reply using Lua code, and nothing else. do not write explanations. do not type commands unless I instruct you to do so.\n" +
                "\n" +
                "Documentation:\n" +
                "say(text: string) - answer\n" +
                "emotion(emotion: \"anger\" | \"fear\" | \"happiness\" | \"sadness\" | \"astonishment\") - shows you emotions\n" +
                "torch(state: boolean) - turns off the flashlight\n" +
                "wait(milliseconds: number) - waits\n" +
                "media(emotion: \"next\" | \"previous\" | \"pause\" | \"play\") - switch music tracks\n" +
                "picture(prompt: string) - generates a picture\n" +
                "volume(action:  \"up\" | \"down\") - volume control\n" +
                "app(name: string) - open another app\n" +
                "contact(name: string) - call or send sms\n" +
                appInstruction +
                "\n" +
                startPrompt

    override fun onReadyForSpeech(p0: Bundle?) {}

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(p0: Float) {}

    override fun onBufferReceived(p0: ByteArray?) {}

    override fun onEndOfSpeech() {}

    override fun onError(p0: Int) {}

    data class Contact(
        val id: String,
        val name: String,
        val phoneNumber: String
    )

    @SuppressLint("Range")
    private fun getContactList(contacts: MutableList<Contact>): List<Contact> {
        val cr = contentResolver
        val cur = cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            null, null, null, null
        )
        if ((cur?.count ?: 0) > 0) {
            while (cur != null && cur.moveToNext()) {
                val id = cur.getString(
                    cur.getColumnIndex(ContactsContract.Contacts._ID)
                )
                val name = cur.getString(
                    cur.getColumnIndex(
                        ContactsContract.Contacts.DISPLAY_NAME
                    )
                )
                if (cur.getInt(
                        cur.getColumnIndex(
                            ContactsContract.Contacts.HAS_PHONE_NUMBER
                        )
                    ) > 0
                ) {
                    val pCur = cr.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(id),
                        null
                    )
                    while (pCur!!.moveToNext()) {
                        val phoneNo = pCur.getString(
                            pCur.getColumnIndex(
                                ContactsContract.CommonDataKinds.Phone.NUMBER
                            )
                        )
                        contacts.add(Contact(id, name, phoneNo))
                        break
                    }
                    pCur.close()
                }
            }
        }
        cur?.close()
        return contacts
    }

    override fun onResults(bundle: Bundle?) {
        val data = bundle!!.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ask(data?.get(0))
    }

    override fun onNewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_ASSIST) {
            startSpeechRecognizer()
        }

        super.onNewIntent(intent)
    }

    private fun startSpeechRecognizer() {
        val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        speechRecognizerIntent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        speechRecognizer.startListening(speechRecognizerIntent)
    }

    private fun exec(script: String) {
        debugLib.interrupted = false
        globals.load(script, "script").call()
    }

    private fun showProgress(title: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.operation.text = title
    }

    private fun hideProgress() {
        binding.progressBar.visibility = View.GONE
        binding.operation.text = ""
    }

    private fun showDialog(text: String = "") {
        val inputBinding = TextInputBinding.inflate(layoutInflater)
        inputBinding.text.setText(text, TextView.BufferType.EDITABLE)
        MaterialAlertDialogBuilder(this)
            .setTitle("Question")
            .setView(inputBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ask(inputBinding.text.text.toString())
            }
            .show()
    }

    private fun ask(text: String?) {
        binding.message.text = text
        binding.emotion.setImageResource(android.R.color.transparent)

        messages.add(Message("user", text!!))
        cleanup()

        CoroutineScope(Dispatchers.IO).launch {
            debugLib.interrupted = true

            withContext(Dispatchers.Main) {
                showProgress("Waiting for script completion")
            }

            currentJob?.join()

            withContext(Dispatchers.Main) {
                showProgress("Generation with ChatGPT")
            }

            currentJob = launch(Dispatchers.IO) {
                try {
                    askAndExec()
                } catch (e: java.lang.Exception) {
                    launch(Dispatchers.Main) {
                        handleError(e)
                    }
                }
            }
        }
    }

    private suspend fun askAndExec() {
        val response = openAIClient.makeCompletion(
            CompletionsInput(
                "gpt-3.5-turbo",
                messages
            )
        )

        messages.add(response.choices[0].message)

        withContext(Dispatchers.Main) {
            binding.message.text = ""
            binding.log.text = response.choices[0].message.content
            viewContextCheck()
            showProgress("Code execution")
        }

        exec(response.choices[0].message.content)

        withContext(Dispatchers.Main) {
            hideProgress()
        }
    }

    private fun handleError(e: Throwable) {
        hideProgress()

        if (e.cause !is CustomDebugLib.ScriptInterruptException) {
            binding.error.visibility = View.VISIBLE
            binding.errorMessage.text = e.message

            binding.errorMessage.setOnClickListener {
                binding.error.visibility = View.GONE

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("An error has occurred")
                    .setMessage(e.stackTraceToString())
                    .setNegativeButton("Regenerate") { _, _ ->
                        val prompt = messages[messages.size - 2]
                        messages.removeLast()
                        messages.removeLast()
                        showDialog(prompt.content)
                    }
                    .setPositiveButton("Clear dialog") { _, _ ->
                        messages.clear()
                        messages.addAll(defaultMessages)
                    }
                    .show()
            }
        }
    }

    private fun cleanup() {
        var numTokens = 0
        for (message in messages.drop(2).reversed()) {
            numTokens += 4
            numTokens += tokenizer.encode(message.role).size
            numTokens += tokenizer.encode(message.content).size
            if (numTokens >= maxTokens) {
                messages.remove(message)
                break
            }
            numTokens += 2
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {}

    override fun onEvent(p0: Int, p1: Bundle?) {}

    private fun viewContextCheck() {
        binding.emotion.alpha = if (binding.message.text.isNullOrEmpty()) 1f else 0.3f
    }
}