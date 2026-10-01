package com.didyar.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DidyarScreen()
                }
            }
        }
    }
}

@Composable
private fun DidyarScreen() {
    val context = LocalContext.current
    val rootView = LocalView.current
    val accessibilityManager = remember {
        context.getSystemService(AccessibilityManager::class.java)
    }
    val scope = rememberCoroutineScope()
    val player = remember { ExoPlayer.Builder(context).build() }
    val tts = remember { PersianTts(context) }
    val scrollState = rememberScrollState()
    val restoredProject = remember { ProjectStore.load(context) }

    var selectedUri by remember { mutableStateOf(restoredProject?.uri) }
    var fileName by remember {
        mutableStateOf(restoredProject?.fileName ?: "هنوز فیلمی انتخاب نشده است")
    }
    var durationMs by remember { mutableLongStateOf(restoredProject?.durationMs ?: 0L) }
    var playerPosition by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var analysisProgress by remember { mutableIntStateOf(0) }
    var status by remember {
        mutableStateOf(
            if (restoredProject != null) {
                "پروژهٔ ذخیره‌شده بازیابی شد."
            } else {
                "دیدیار آماده است."
            }
        )
    }

    val scenes = remember {
        mutableStateListOf<ScenePoint>().apply {
            addAll(restoredProject?.scenes.orEmpty())
        }
    }
    var selectedSceneIndex by remember {
        mutableIntStateOf(if (scenes.isNotEmpty()) 0 else -1)
    }
    var descriptionDraft by remember {
        mutableStateOf(scenes.firstOrNull()?.description.orEmpty())
    }
    var descriptionFieldFocused by remember { mutableStateOf(false) }

    var apiKey by remember {
        mutableStateOf(SecureSecretStore.load(context, "avalai_api_key").orEmpty())
    }
    var showAiSettings by remember { mutableStateOf(apiKey.isBlank()) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiConnectionStatus by remember { mutableStateOf("وضعیت اتصال: هنوز آزمایش نشده است.") }
    var batchBusy by remember { mutableStateOf(false) }
    var batchProgress by remember { mutableIntStateOf(0) }
    var batchTotal by remember { mutableIntStateOf(0) }
    var batchCancelRequested by remember { mutableStateOf(false) }
    var lastErrorDetails by remember { mutableStateOf("") }
    var autoNarrationEnabled by remember { mutableStateOf(false) }
    var narrationInProgress by remember { mutableStateOf(false) }
    var lastNarratedSceneIndex by remember { mutableIntStateOf(-1) }

    fun persistProject() {
        val uri = selectedUri ?: return
        ProjectStore.save(
            context = context,
            uri = uri,
            fileName = fileName,
            durationMs = durationMs,
            scenes = scenes.toList()
        )
    }

    fun estimatedNarrationDurationMs(text: String): Long {
        val wordCount = text.trim()
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
            .coerceAtLeast(1)
        return (1_000L + wordCount * 350L).coerceIn(2_500L, 22_000L)
    }

    fun readDescriptionNow(text: String) {
        val talkBackLikeReaderActive =
            accessibilityManager?.isEnabled == true &&
            accessibilityManager.isTouchExplorationEnabled

        if (talkBackLikeReaderActive) {
            rootView.announceForAccessibility(text)
        } else {
            tts.speak(text)
        }
    }

    fun selectScene(index: Int) {
        if (index !in scenes.indices) return
        selectedSceneIndex = index
        val scene = scenes[index]
        descriptionDraft = scene.description
        status = "صحنه ${scene.index} از ${scenes.size} انتخاب شد؛ زمان ${formatTime(scene.timeMs)}."
    }

    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }

            selectedUri = uri
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "فیلم انتخاب‌شده"
            scenes.clear()
            selectedSceneIndex = -1
            descriptionDraft = ""
            autoNarrationEnabled = false
            narrationInProgress = false
            lastNarratedSceneIndex = -1
            status = "فیلم انتخاب شد. در حال خواندن مشخصات."

            scope.launch {
                try {
                    val info = withContext(Dispatchers.IO) {
                        VideoInfoReader.read(context, uri)
                    }
                    durationMs = info.durationMs
                    status = "فیلم آماده است. مدت ${formatTime(durationMs)}."
                } catch (e: Exception) {
                    status = "خواندن فیلم ناموفق بود: ${e.message ?: "خطای نامشخص"}"
                }
            }
        }
    }

    LaunchedEffect(selectedUri) {
        selectedUri?.let { uri ->
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val currentPosition = player.currentPosition.coerceAtLeast(0L)
            playerPosition = currentPosition
            isPlaying = player.isPlaying

            if (
                autoNarrationEnabled &&
                player.isPlaying &&
                !narrationInProgress &&
                scenes.isNotEmpty()
            ) {
                val currentSceneIndex = scenes.indexOfLast { it.timeMs <= currentPosition }
                if (
                    currentSceneIndex >= 0 &&
                    currentSceneIndex != lastNarratedSceneIndex
                ) {
                    lastNarratedSceneIndex = currentSceneIndex
                    val scene = scenes[currentSceneIndex]
                    val description = scene.description.trim()

                    if (description.isNotBlank()) {
                        narrationInProgress = true
                        player.pause()
                        status = "در حال خواندن توضیح صحنه ${scene.index}."
                        readDescriptionNow(description)
                        delay(estimatedNarrationDurationMs(description))

                        narrationInProgress = false
                        if (autoNarrationEnabled) {
                            player.play()
                            status = "پخش فیلم ادامه یافت."
                        }
                    }
                }
            }

            delay(250)
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            tts.shutdown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "دیدیار ۰٫۴٫۲",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = status,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
            }
        )

        Button(
            onClick = { openDocument.launch(arrayOf("video/*", "application/octet-stream")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("انتخاب فیلم")
        }

        Text("فایل: $fileName")
        if (durationMs > 0) {
            Text("مدت فیلم: ${formatTime(durationMs)}")
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = "تصویر فیلم؛ کنترل‌های پخش در پایین قرار دارند" }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                    status = "ده ثانیه عقب رفت."
                },
                enabled = selectedUri != null && !aiBusy,
                modifier = Modifier.weight(1f)
            ) { Text("۱۰ ثانیه عقب") }

            Button(
                onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                },
                enabled = selectedUri != null && !aiBusy,
                modifier = Modifier.weight(1f)
            ) { Text(if (isPlaying) "مکث" else "پخش") }

            Button(
                onClick = {
                    val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 10_000L).coerceAtMost(max))
                    status = "ده ثانیه جلو رفت."
                },
                enabled = selectedUri != null && !aiBusy,
                modifier = Modifier.weight(1f)
            ) { Text("۱۰ ثانیه جلو") }
        }

        Text("موقعیت پخش: ${formatTime(playerPosition)}")

        Button(
            onClick = {
                val uri = selectedUri ?: return@Button
                analyzing = true
                analysisProgress = 0
                status = "تحلیل پنج دقیقهٔ اول آغاز شد."
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.Default) {
                            SceneAnalyzer.analyzeFirstFiveMinutes(context, uri) { progress ->
                                scope.launch { analysisProgress = progress }
                            }
                        }
                        scenes.clear()
                        scenes.addAll(result)
                        if (scenes.isNotEmpty()) {
                            selectScene(0)
                        } else {
                            selectedSceneIndex = -1
                            descriptionDraft = ""
                        }
                        status = "تحلیل تمام شد. ${scenes.size} نقطهٔ احتمالی صحنه پیدا شد."
                    } catch (e: Exception) {
                        status = "تحلیل ناموفق بود: ${e.message ?: "خطای نامشخص"}"
                    } finally {
                        analyzing = false
                    }
                }
            },
            enabled = selectedUri != null && !analyzing && !aiBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (analyzing) "در حال تحلیل؛ $analysisProgress درصد" else "تحلیل ۵ دقیقهٔ اول")
        }

        Button(
            onClick = { showAiSettings = !showAiSettings },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showAiSettings) "بستن تنظیمات هوش مصنوعی" else "تنظیمات هوش مصنوعی")
        }

        if (showAiSettings) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "موتور توضیح خودکار",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text("AvalAI — مدل Gemini 3.8 Flash")
                    Text(
                        "کلید AvalAI داخل فایل برنامه قرار نمی‌گیرد. کلیدی که اینجا وارد می‌کنید با Android Keystore روی همین گوشی رمزگذاری می‌شود."
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim() },
                        label = { Text("کلید API AvalAI") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            SecureSecretStore.save(context, "avalai_api_key", apiKey.trim())
                            val message = "کلید AvalAI با موفقیت ذخیره شد."
                            status = message
                            rootView.announceForAccessibility(message)
                        },
                        enabled = apiKey.isNotBlank() && !aiBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ذخیره کلید")
                    }

                    Button(
                        onClick = {
                            val key = apiKey.trim()
                            if (key.isBlank()) return@Button
                            aiBusy = true
                            val starting = "در حال آزمایش اتصال به AvalAI."
                            status = starting
                            aiConnectionStatus = "وضعیت اتصال: در حال آزمایش..."
                            rootView.announceForAccessibility(starting)
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        AvalAiVisionProvider.testConnection(key)
                                    }
                                    SecureSecretStore.save(context, "avalai_api_key", key)
                                    val success = "اتصال به AvalAI برقرار شد و کلید معتبر است."
                                    status = success
                                    aiConnectionStatus = "وضعیت اتصال: برقرار شد. کلید معتبر است."
                                    rootView.announceForAccessibility(success)
                                } catch (e: Exception) {
                                    val failure = "آزمایش اتصال ناموفق بود: ${e.message ?: "خطای نامشخص"}"
                                    status = failure
                                    aiConnectionStatus = failure
                                    rootView.announceForAccessibility(failure)
                                } finally {
                                    aiBusy = false
                                }
                            }
                        },
                        enabled = apiKey.isNotBlank() && !aiBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (aiBusy) "در حال آزمایش..." else "آزمایش اتصال AvalAI")
                    }

                    Text(
                        text = aiConnectionStatus,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Assertive
                        }
                    )

                    Button(
                        onClick = {
                            SecureSecretStore.clear(context, "avalai_api_key")
                            apiKey = ""
                            aiConnectionStatus = "وضعیت اتصال: کلید پاک شده است."
                            val message = "کلید AvalAI از گوشی پاک شد."
                            status = message
                            rootView.announceForAccessibility(message)
                        },
                        enabled = apiKey.isNotBlank() && !aiBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("پاک کردن کلید")
                    }
                }
            }
        }

        if (scenes.isNotEmpty()) {
            Text(
                text = "صحنه‌ها: ${scenes.size} مورد",
                style = MaterialTheme.typography.titleMedium
            )

            val pendingDescriptions = scenes.count { it.description.isBlank() }
            val readyDescriptions = scenes.size - pendingDescriptions

            Text("توضیح آماده: $readyDescriptions از ${scenes.size} صحنه")

            if (!batchBusy) {
                Button(
                    onClick = {
                        val uri = selectedUri ?: return@Button
                        val key = apiKey.trim()
                        if (key.isBlank()) return@Button

                        val pendingIndices = scenes.indices.filter {
                            scenes[it].description.isBlank()
                        }
                        if (pendingIndices.isEmpty()) {
                            val message = "همهٔ صحنه‌ها از قبل توضیح دارند."
                            status = message
                            rootView.announceForAccessibility(message)
                            return@Button
                        }

                        player.pause()
                        autoNarrationEnabled = false
                        batchBusy = true
                        aiBusy = true
                        batchCancelRequested = false
                        batchProgress = 0
                        batchTotal = pendingIndices.size

                        val startMessage =
                            "ساخت توضیح برای ${pendingIndices.size} صحنه آغاز شد."
                        status = startMessage
                        rootView.announceForAccessibility(startMessage)

                        scope.launch {
                            try {
                                lastErrorDetails = ""
                                for ((position, sceneIndex) in pendingIndices.withIndex()) {
                                    if (batchCancelRequested) break

                                    val scene = scenes[sceneIndex]
                                    val nextSceneStart =
                                        scenes.getOrNull(sceneIndex + 1)?.timeMs ?: durationMs

                                    status =
                                        "در حال ساخت توضیح صحنه ${scene.index}؛ " +
                                            "مورد ${position + 1} از ${pendingIndices.size}."

                                    val description = try {
                                        withContext(Dispatchers.IO) {
                                            val frames = SceneFrameExtractor.extractBase64Jpegs(
                                                context = context,
                                                uri = uri,
                                                startMs = scene.timeMs,
                                                endMs = nextSceneStart
                                            )
                                            AvalAiVisionProvider.describeScene(
                                                apiKey = key,
                                                framesBase64 = frames,
                                                sceneIndex = scene.index,
                                                sceneTotal = scenes.size,
                                                timecode = formatTime(scene.timeMs)
                                            )
                                        }
                                    } catch (e: Exception) {
                                        lastErrorDetails =
                                            "نسخه دیدیار: ۰٫۴٫۱\n" +
                                                "صحنه: ${scene.index} از ${scenes.size}\n" +
                                                "زمان: ${formatTime(scene.timeMs)}\n" +
                                                "نوع خطا: ${e::class.java.simpleName}\n" +
                                                "پیام: ${e.message ?: "خطای نامشخص"}"
                                        throw e
                                    }

                                    if (sceneIndex in scenes.indices) {
                                        scenes[sceneIndex] =
                                            scenes[sceneIndex].copy(description = description)
                                        if (sceneIndex == selectedSceneIndex) {
                                            descriptionDraft = description
                                        }
                                    }

                                    batchProgress = position + 1
                                    rootView.announceForAccessibility(
                                        "توضیح ${position + 1} از ${pendingIndices.size} آماده شد."
                                    )

                                    if (position < pendingIndices.lastIndex) {
                                        delay(12_000)
                                    }
                                }

                                val finalMessage = if (batchCancelRequested) {
                                    "ساخت توضیحات متوقف شد. " +
                                        "$batchProgress از $batchTotal صحنه آماده شد."
                                } else {
                                    "ساخت توضیحات تمام شد. " +
                                        "$batchProgress صحنه توضیح‌دار شد."
                                }
                                status = finalMessage
                                rootView.announceForAccessibility(finalMessage)
                            } catch (e: Exception) {
                                if (lastErrorDetails.isBlank()) {
                                    lastErrorDetails =
                                        "نسخه دیدیار: ۰٫۴٫۱\n" +
                                            "نوع خطا: ${e::class.java.simpleName}\n" +
                                            "پیام: ${e.message ?: "خطای نامشخص"}"
                                }
                                val failure =
                                    "ساخت توضیحات متوقف شد. جزئیات خطا پایین صفحه ذخیره شد."
                                status = failure
                                rootView.announceForAccessibility(failure)
                            } finally {
                                batchBusy = false
                                aiBusy = false
                            }
                        }
                    },
                    enabled =
                        selectedUri != null &&
                            apiKey.isNotBlank() &&
                            !aiBusy &&
                            pendingDescriptions > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (pendingDescriptions > 0) {
                            "ساخت توضیح برای همهٔ صحنه‌ها؛ $pendingDescriptions درخواست"
                        } else {
                            "همهٔ صحنه‌ها توضیح دارند"
                        }
                    )
                }
            } else {
                Text(
                    text = "پیشرفت ساخت توضیحات: $batchProgress از $batchTotal",
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
                )

                Button(
                    onClick = {
                        batchCancelRequested = true
                        val message = "پس از پایان صحنهٔ جاری، ساخت توضیحات متوقف می‌شود."
                        status = message
                        rootView.announceForAccessibility(message)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("توقف پس از صحنهٔ جاری")
                }
            }

            Button(
                onClick = {
                    autoNarrationEnabled = !autoNarrationEnabled
                    lastNarratedSceneIndex = -1
                    narrationInProgress = false

                    val message = if (autoNarrationEnabled) {
                        "توضیح هنگام پخش روشن شد. فیلم هنگام خواندن توضیح موقتاً مکث می‌کند."
                    } else {
                        "توضیح هنگام پخش خاموش شد."
                    }
                    status = message
                    rootView.announceForAccessibility(message)
                },
                enabled = readyDescriptions > 0 && !aiBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (autoNarrationEnabled) {
                        "توضیح هنگام پخش: روشن"
                    } else {
                        "توضیح هنگام پخش: خاموش"
                    }
                )
            }

            Button(
                onClick = {
                    player.pause()
                    player.seekTo(0L)
                    lastNarratedSceneIndex = -1
                    narrationInProgress = false
                    autoNarrationEnabled = true
                    status = "پخش توضیح‌دار از ابتدا آغاز شد."
                    player.play()
                },
                enabled = readyDescriptions > 0 && !aiBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("پخش توضیح‌دار از ابتدا")
            }

            Text(
                "در این نسخهٔ آزمایشی، فیلم هنگام خواندن هر توضیح موقتاً مکث می‌کند و سپس ادامه می‌یابد."
            )

            if (lastErrorDetails.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "آخرین خطا",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(lastErrorDetails)
                        Button(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(ClipboardManager::class.java)
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(
                                        "Didyar error",
                                        lastErrorDetails
                                    )
                                )
                                val message = "جزئیات آخرین خطا کپی شد."
                                status = message
                                rootView.announceForAccessibility(message)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("کپی آخرین خطا")
                        }
                    }
                }
            }

            val currentScene = scenes[selectedSceneIndex.coerceIn(0, scenes.lastIndex)]

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "صحنه ${currentScene.index} از ${scenes.size}، زمان ${formatTime(currentScene.timeMs)}",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { selectScene(selectedSceneIndex - 1) },
                            enabled = selectedSceneIndex > 0 && !aiBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("صحنه قبلی")
                        }

                        Button(
                            onClick = { selectScene(selectedSceneIndex + 1) },
                            enabled = selectedSceneIndex < scenes.lastIndex && !aiBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("صحنه بعدی")
                        }
                    }

                    Button(
                        onClick = {
                            player.seekTo(currentScene.timeMs)
                            player.play()
                            status = "پخش از صحنه ${currentScene.index}."
                        },
                        enabled = !aiBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("پخش از این صحنه")
                    }

                    Button(
                        onClick = {
                            val uri = selectedUri ?: return@Button
                            val key = apiKey.trim()
                            val index = selectedSceneIndex
                            if (key.isBlank() || index !in scenes.indices) return@Button

                            val scene = scenes[index]
                            val nextSceneStart = scenes.getOrNull(index + 1)?.timeMs ?: durationMs

                            aiBusy = true
                            status = "در حال ساخت توضیح خودکار برای صحنه ${scene.index}."

                            scope.launch {
                                try {
                                    val description = withContext(Dispatchers.IO) {
                                        val frames = SceneFrameExtractor.extractBase64Jpegs(
                                            context = context,
                                            uri = uri,
                                            startMs = scene.timeMs,
                                            endMs = nextSceneStart
                                        )
                                        AvalAiVisionProvider.describeScene(
                                            apiKey = key,
                                            framesBase64 = frames,
                                            sceneIndex = scene.index,
                                            sceneTotal = scenes.size,
                                            timecode = formatTime(scene.timeMs)
                                        )
                                    }

                                    if (index in scenes.indices) {
                                        scenes[index] = scenes[index].copy(description = description)
                                        descriptionDraft = description
                                    }
                                    status = "توضیح خودکار صحنه ${scene.index} آماده شد."
                                } catch (e: Exception) {
                                    status = "ساخت توضیح خودکار ناموفق بود: ${e.message ?: "خطای نامشخص"}"
                                } finally {
                                    aiBusy = false
                                }
                            }
                        },
                        enabled = !aiBusy && selectedUri != null && apiKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (aiBusy) "در حال ساخت توضیح..."
                            else "ساخت توضیح خودکار این صحنه"
                        )
                    }

                    if (apiKey.isBlank()) {
                        Text("برای فعال شدن توضیح خودکار، ابتدا در تنظیمات هوش مصنوعی کلید AvalAI را وارد کنید.")
                    }

                    OutlinedTextField(
                        value = descriptionDraft,
                        onValueChange = { descriptionDraft = it },
                        label = { Text("توضیح صحنه") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    Button(
                        onClick = {
                            scenes[selectedSceneIndex] = currentScene.copy(
                                description = descriptionDraft.trim()
                            )
                            status = "توضیح صحنه ${currentScene.index} ذخیره شد."
                        },
                        enabled = !aiBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ذخیره توضیح")
                    }

                    Button(
                        onClick = {
                            val text = descriptionDraft.trim()
                            if (text.isBlank()) {
                                status = "توضیحی برای خواندن وجود ندارد."
                                return@Button
                            }

                            readDescriptionNow(text)
                            status = "توضیح برای خواندن ارسال شد."
                        },
                        enabled = descriptionDraft.isNotBlank() && !aiBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("شنیدن توضیح")
                    }
                }
            }
        }
    }
}
