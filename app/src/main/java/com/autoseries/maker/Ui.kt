package com.autoseries.maker

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import java.text.DateFormat
import java.util.Date

@Composable
fun AppUi(vm: AppViewModel) {
    vm.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.error = null },
            confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } },
            title = { Text("Notice") },
            text = { Text(msg) },
        )
    }
    when (vm.screen) {
        Screen.HOME -> HomeScreen(vm)
        Screen.SETUP -> SetupScreen(vm)
        Screen.EXPORT -> ExportScreen(vm)
        Screen.DONE -> DoneScreen(vm)
    }
}

private fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(t: String) =
    Text(t, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

@Composable
private fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(label) })
        }
    }
}

// ---------------------------------------------------------------- HOME
@Composable
private fun HomeScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::pick) }
    val free = remember(vm.history.size) { Storage.freeBytes(ctx) / 1_000_000_000.0 }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("Auto Series Video Maker", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Landscape → 9:16 series, automatically", color = Color.Gray)
        }
        item {
            Button(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text("＋  UPLOAD VIDEO", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
        item {
            Card {
                Text("Storage", fontWeight = FontWeight.SemiBold)
                Text("%.1f GB free".format(free), color = if (free < 2) Color(0xFFFF8A80) else Color.Gray)
            }
        }
        item { Text("Recent projects / Exported videos", fontWeight = FontWeight.SemiBold) }
        if (vm.history.isEmpty()) item { Text("No projects yet. Upload a video to begin.", color = Color.Gray) }
        items(vm.history) { r ->
            Card {
                Text("${r.folder} · ${r.source}", fontWeight = FontWeight.Medium)
                Text(
                    "${r.parts} parts · ${r.series} series · ${if (r.complete) "complete" else "incomplete"} · " +
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.date)),
                    color = Color.Gray, fontSize = 13.sp,
                )
                Text("Gallery → Movies/${Storage.ROOT}/${r.folder}", color = Color.Gray, fontSize = 12.sp)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------- SETUP
@Composable
private fun SetupScreen(vm: AppViewModel) {
    val m = vm.meta ?: return
    val splitChoices = listOf(15, 30, 40, 50, 60, 90)
    var customSplit by remember { mutableStateOf(vm.splitSec !in splitChoices) }
    val perChoices = listOf(5, 10, 15, 20)
    var customPer by remember { mutableStateOf(vm.partsPerSeries !in perChoices) }
    val plans = vm.plans

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::home) { Text("← Back") }
                Text("Setup & Preview", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Card {
                Text(m.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    "${fmtDur(m.durationMs)} · ${m.width}×${m.height} · ${m.aspect} · ${m.sizeBytes / 1_000_000} MB" +
                        (m.fps?.let { " · %.0f fps".format(it) } ?: "") + if (m.hasAudio) "" else " · no audio",
                    color = Color.Gray, fontSize = 13.sp,
                )
            }
        }
        item { Preview(vm) }
        item {
            SectionTitle("Title")
            OutlinedTextField(
                value = vm.spec.title, onValueChange = { vm.spec = vm.spec.copy(title = it) },
                modifier = Modifier.fillMaxWidth(), label = { Text("Title (Tamil / English)") },
            )
            OutlinedTextField(
                value = vm.spec.seriesName, onValueChange = { vm.spec = vm.spec.copy(seriesName = it) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Series name") }, singleLine = true,
            )
        }
        item {
            SectionTitle("Banner template")
            Chips(TEMPLATES.map { it.name }, vm.spec.template) { vm.spec = vm.spec.copy(template = it) }
            SectionTitle("Font")
            Chips(FONTS.map { it.first }, vm.spec.font) { vm.spec = vm.spec.copy(font = it) }
            SectionTitle("Text size")
            Chips(SIZES, vm.spec.sizeLevel) { vm.spec = vm.spec.copy(sizeLevel = it) }
            SectionTitle("Alignment")
            Chips(ALIGNS, vm.spec.align) { vm.spec = vm.spec.copy(align = it) }
            SectionTitle("Title colour")
            Chips(TITLE_COLORS.map { it.first }, TITLE_COLORS.indexOfFirst { it.second == vm.spec.titleColor }.coerceAtLeast(0)) {
                vm.spec = vm.spec.copy(titleColor = TITLE_COLORS[it].second)
            }
            SectionTitle("Part label")
            Chips(PART_STYLES, vm.spec.partStyle) { vm.spec = vm.spec.copy(partStyle = it) }
            SectionTitle("Part position")
            Chips(PART_POSITIONS, vm.spec.partPos) { vm.spec = vm.spec.copy(partPos = it) }
        }
        item {
            SectionTitle("Split duration")
            Chips(splitChoices.map { "$it s" } + "Custom", if (customSplit) splitChoices.size else splitChoices.indexOf(vm.splitSec)) {
                if (it == splitChoices.size) customSplit = true else { customSplit = false; vm.splitSec = splitChoices[it] }
            }
            if (customSplit) NumberField("Seconds per part", vm.splitSec) { vm.splitSec = it.coerceIn(5, 600) }
            SectionTitle("Parts per series")
            Chips(perChoices.map { "$it" } + "Custom", if (customPer) perChoices.size else perChoices.indexOf(vm.partsPerSeries)) {
                if (it == perChoices.size) customPer = true else { customPer = false; vm.partsPerSeries = perChoices[it] }
            }
            if (customPer) NumberField("Parts per series", vm.partsPerSeries) { vm.partsPerSeries = it.coerceIn(1, 100) }
            SectionTitle("Quality")
            Chips(listOf("720p", "1080p"), if (vm.quality == 720) 0 else 1) { vm.quality = if (it == 0) 720 else 1080 }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Card {
                Text("${plans.size} parts · ${plans.lastOrNull()?.seriesIndex ?: 0} series", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                plans.lastOrNull()?.let { Text("Last part: ${it.durationMs / 1000}s", color = Color.Gray) }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = vm::start, enabled = plans.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp),
            ) { Text("SPLIT ALL VIDEOS", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s -> text = s.filter(Char::isDigit).take(4); text.toIntOrNull()?.let(onChange) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun Preview(vm: AppViewModel) {
    val thumb = vm.thumb
    val firstPlan = vm.plans.firstOrNull()
    val overlay = remember(vm.spec, firstPlan) {
        Banner.render(vm.spec, 540, 960, firstPlan?.seriesIndex ?: 1, firstPlan?.partInSeries ?: 1).asImageBitmap()
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.height(440.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
            if (thumb != null) {
                val img = thumb.asImageBitmap()
                Image(img, null, Modifier.fillMaxSize().blur(24.dp), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color(0x40000000)))
                Image(img, null, Modifier.fillMaxWidth().align(Alignment.Center), contentScale = ContentScale.FillWidth)
            }
            Image(overlay, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}

// ---------------------------------------------------------------- EXPORT
@Composable
private fun ExportScreen(vm: AppViewModel) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val total = vm.plans.size
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${(vm.progress * 100).toInt()}%", fontSize = 56.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)))
        Spacer(Modifier.height(16.dp))
        Text(vm.status, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("Completed ${vm.results.size} · Remaining ${total - vm.results.size}", color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Text("Screen-ஐ ON-ஆக வைத்து, app-ஐ மூடாமல் இருங்கள்.", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
    }
}

// ---------------------------------------------------------------- DONE
@Composable
private fun DoneScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    fun share(uris: List<Uri>) {
        val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "video/mp4"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Share"))
    }
    fun play(u: Uri) = ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))

    val bySeries = vm.results.groupBy { it.plan.seriesIndex }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                when {
                    vm.cancelled -> "Cancelled"
                    vm.results.size == vm.plans.size -> "✅ All ${vm.results.size} parts saved"
                    else -> "Stopped"
                },
                fontSize = 24.sp, fontWeight = FontWeight.Bold,
            )
            Text("Gallery → Movies/${Storage.ROOT}/${vm.projectFolder}", color = Color.Gray)
        }
        bySeries.forEach { (s, parts) ->
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Series %02d  (${parts.size} parts)".format(s), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { share(parts.map { it.uri }) }) { Text("Share series") }
                    }
                    parts.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { play(p.uri) }) { Text("Play") }
                            TextButton(onClick = { share(listOf(p.uri)) }) { Text("Share") }
                        }
                    }
                }
            }
        }
        item { Button(onClick = vm::home, modifier = Modifier.fillMaxWidth()) { Text("Done") } }
    }
}
