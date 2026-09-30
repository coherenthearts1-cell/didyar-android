package com.didyar.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
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
import androidx.compose.ui.platform.LocalContext
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
    val scope = rememberCoroutineScope()
    val player = remember { ExoPlayer.Builder(context).build() }
    val tts = remember { PersianTts(context) }
    val scrollState = rememberScrollState()
    val prefs = remember {
        context.getSharedPreferences("didyar_ai", Context.MODE_PRIVATE)
    }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("هنوز فیلمی انتخاب نشده است") }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playerPosition by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var analysisProgress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("دیدیار آماده است.") }

    val scenes = remember { mutableStateListOf<ScenePoint>() }
    var selectedSceneIndex by remember { mutableIntStateOf(-1) }
    var descriptionDraft by remember { mutableStateOf("") }

    var aiEndpoint by remember {
        mutableStateOf(prefs.getString("endpoint", "").orEmpty())
    }
    var aiToken by remember {
        mutableStateOf(prefs.getString("token", "").orEmpty())
    }
    var showAiSettings by remember { mutableStateOf(aiEndpoint.isBlank() || aiToken.isBlank()) }
    var aiBusy by remember { mutableStateOf(false) }

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
            playerPosition = player.currentPosition.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            delay(500)
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
            text = "دیدیار ۰٫۲",
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
                enabled = selectedUri != null,
                modifier = Modifier.weight(1f)
            ) { Text("۱۰ ثانیه عقب") }

            Button(
                onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                },
                enabled = selectedUri != null,
                modifier = Modifier.weight(1f)
            ) { Text(if (isPlaying) "مکث" else "پخش") }

            Button(
                onClick = {
                    val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
                    player.seekTo((player.currentPosition + 10_000L).coerceAtMost(max))
                    status = "ده ثانیه جلو رفت."
                },
                enabled = selectedUri != null,
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
                        "تنظیمات سرویس توضیح خودکار",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        "کلید OpenAI داخل برنامه وارد نمی‌شود. این قسمت فقط نشانی بک‌اند دیدیار و رمز دسترسی همان بک‌اند را نگه می‌دارد."
                    )

                    OutlinedTextField(
                        value = aiEndpoint,
                        onValueChange = { aiEndpoint = it },
                        label = { Text("نشانی سرویس AI") },
                        placeholder = { Text("https://.../api/describe") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = aiToken,
                        onValueChange = { aiToken = it },
                        label = { Text("رمز دسترسی سرویس") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            prefs.edit()
                                .putString("endpoint", aiEndpoint.trim())
                                .putString("token", aiToken.trim())
                                .apply()
                            status = "تنظیمات سرویس هوش مصنوعی ذخیره شد."
                            showAiSettings = false
                        },
                        enabled = aiEndpoint.startsWith("https://") && aiToken.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ذخیره تنظیمات AI")
                    }
                }
            }
        }

        if (scenes.isNotEmpty()) {
            Text(
                text = "صحنه‌ها: ${scenes.size} مورد",
                style = MaterialTheme.typography.titleMedium
            )

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
                            val index = selectedSceneIndex
                            if (index !in scenes.indices) return@Button

                            val scene = scenes[index]
                            val nextSceneStart = scenes.getOrNull(index + 1)?.timeMs ?: durationMs
                            val config = AiServiceConfig(
                                endpoint = aiEndpoint.trim(),
                                accessToken = aiToken.trim()
                            )

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
                                        AiDescriptionClient.describeScene(
                                            config = config,
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
                        enabled = !aiBusy &&
                            selectedUri != null &&
                            aiEndpoint.startsWith("https://") &&
                            aiToken.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (aiBusy) "در حال ساخت توضیح..."
                            else "ساخت توضیح خودکار این صحنه"
                        )
                    }

                    if (aiEndpoint.isBlank() || aiToken.isBlank()) {
                        Text("برای فعال شدن توضیح خودکار، ابتدا تنظیمات هوش مصنوعی را وارد کنید.")
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
                            status = if (tts.speak(text)) {
                                "توضیح با صدای گوشی پخش شد."
                            } else {
                                "برای پخش، ابتدا یک توضیح بنویسید یا موتور گفتار گوشی هنوز آماده نیست."
                            }
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
