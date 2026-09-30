package com.didyar.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "دیدیار ۰٫۱",
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
                .semantics { contentDescription = "تصویر فیلم؛ کنترل‌ها در پایین قرار دارند" }
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
                        selectedSceneIndex = if (scenes.isNotEmpty()) 0 else -1
                        descriptionDraft = scenes.firstOrNull()?.description.orEmpty()
                        status = "تحلیل تمام شد. ${scenes.size} نقطهٔ احتمالی صحنه پیدا شد."
                    } catch (e: Exception) {
                        status = "تحلیل ناموفق بود: ${e.message ?: "خطای نامشخص"}"
                    } finally {
                        analyzing = false
                    }
                }
            },
            enabled = selectedUri != null && !analyzing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (analyzing) "در حال تحلیل؛ $analysisProgress درصد" else "تحلیل ۵ دقیقهٔ اول")
        }

        if (scenes.isNotEmpty()) {
            Text(
                text = "صحنه‌ها؛ ${scenes.size} مورد",
                style = MaterialTheme.typography.titleMedium
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(scenes, key = { _, item -> item.index }) { index, scene ->
                    val state = if (scene.description.isBlank()) "بدون توضیح" else "دارای توضیح"
                    Button(
                        onClick = {
                            selectedSceneIndex = index
                            descriptionDraft = scene.description
                            status = "صحنه ${scene.index} انتخاب شد؛ زمان ${formatTime(scene.timeMs)}."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("صحنه ${scene.index}، ${formatTime(scene.timeMs)}، $state")
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (selectedSceneIndex in scenes.indices) {
            val selectedScene = scenes[selectedSceneIndex]
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("صحنه ${selectedScene.index}، زمان ${formatTime(selectedScene.timeMs)}")

                    OutlinedTextField(
                        value = descriptionDraft,
                        onValueChange = { descriptionDraft = it },
                        label = { Text("توضیح صحنه") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scenes[selectedSceneIndex] = selectedScene.copy(
                                    description = descriptionDraft.trim()
                                )
                                status = "توضیح صحنه ${selectedScene.index} ذخیره شد."
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("ذخیره توضیح") }

                        Button(
                            onClick = {
                                player.seekTo(selectedScene.timeMs)
                                player.play()
                                status = "پخش از صحنه ${selectedScene.index}."
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("پخش این صحنه") }
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
                        enabled = descriptionDraft.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("شنیدن توضیح")
                    }
                }
            }
        }
    }
}
