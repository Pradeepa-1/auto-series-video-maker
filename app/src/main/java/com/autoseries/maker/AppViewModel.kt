package com.autoseries.maker

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val engine = ExportEngine(app)

    var screen by mutableStateOf(Screen.HOME)
    var uri by mutableStateOf<Uri?>(null)
    var meta by mutableStateOf<VideoMeta?>(null)
    var thumb by mutableStateOf<Bitmap?>(null)
    var spec by mutableStateOf(DesignSpec(template = Storage.loadInt(app, "template", 0)))
    var splitSec by mutableIntStateOf(Storage.loadInt(app, "split", 50))
    var partsPerSeries by mutableIntStateOf(Storage.loadInt(app, "perSeries", 10))
    var quality by mutableIntStateOf(Storage.loadInt(app, "quality", 1080))
    var error by mutableStateOf<String?>(null)

    var progress by mutableStateOf(0f)
    var status by mutableStateOf("")
    var finished by mutableStateOf(false)
    var cancelled by mutableStateOf(false)
    var projectFolder by mutableStateOf("")
    val results = mutableStateListOf<ExportedPart>()
    val history = mutableStateListOf<ProjectRecord>().also { it.addAll(Storage.loadHistory(app).reversed()) }
    private var job: Job? = null

    val plans: List<PartPlan>
        get() = meta?.let { SeriesPlanner.plan(it.durationMs, splitSec, partsPerSeries) } ?: emptyList()

    fun pick(u: Uri) {
        viewModelScope.launch {
            try {
                val m = withContext(Dispatchers.IO) { Storage.readMeta(ctx, u) }
                val t = withContext(Dispatchers.IO) { Storage.thumbnail(ctx, u, minOf(1000L, m.durationMs / 2)) }
                uri = u; meta = m; thumb = t
                spec = spec.copy(title = spec.title.ifBlank { m.name.substringBeforeLast('.').replace('_', ' ') })
                screen = Screen.SETUP
            } catch (e: Exception) {
                error = "இந்த வீடியோவைப் படிக்க முடியவில்லை (unsupported / corrupted): ${e.message}"
            }
        }
    }

    fun start() {
        val m = meta ?: return
        val src = uri ?: return
        val list = plans
        if (list.isEmpty()) { error = "No parts to create"; return }
        val need = Storage.estimateTotalBytes(m.durationMs, quality)
        val free = Storage.freeBytes(ctx)
        if (free < need * 12 / 10) {
            error = "Storage குறைவு: தேவை ≈ ${need / 1_000_000} MB, உள்ளது ${free / 1_000_000} MB"
            return
        }
        Storage.saveInt(ctx, "split", splitSec); Storage.saveInt(ctx, "perSeries", partsPerSeries)
        Storage.saveInt(ctx, "quality", quality); Storage.saveInt(ctx, "template", spec.template)

        results.clear(); progress = 0f; finished = false; cancelled = false
        projectFolder = "Project_%03d".format(Storage.nextProjectNumber(ctx))
        screen = Screen.EXPORT
        val began = System.currentTimeMillis()
        job = viewModelScope.launch {
            val tmp = File(ctx.cacheDir, "part_tmp.mp4")
            var failed: String? = null
            try {
                for ((i, p) in list.withIndex()) {
                    status = "Part ${i + 1} / ${list.size}  (Series ${p.seriesIndex} · Part ${p.partInSeries})"
                    engine.render(src, m, p, spec, quality, tmp) { f -> progress = (i + f) / list.size }
                    val saved = withContext(Dispatchers.IO) {
                        Storage.saveVideo(ctx, tmp, projectFolder, p.seriesIndex, p.partInSeries).also { tmp.delete() }
                    }
                    results.add(ExportedPart(p, saved, "Part_%02d".format(p.partInSeries)))
                    progress = (i + 1f) / list.size
                    val done = i + 1
                    val avg = (System.currentTimeMillis() - began) / done
                    val left = avg * (list.size - done) / 1000
                    if (done < list.size) status += "  ·  ~${left / 60}m ${left % 60}s left"
                }
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                failed = e.message ?: e.javaClass.simpleName
            } finally {
                tmp.delete()
            }
            val complete = results.size == list.size
            val seriesCount = list.last().seriesIndex
            withContext(Dispatchers.IO + NonCancellable) {
                Storage.saveSummary(ctx, projectFolder, buildString {
                    appendLine("Original: ${m.name}")
                    appendLine("Original duration: ${m.durationMs / 1000}s")
                    appendLine("Split duration: ${splitSec}s")
                    appendLine("Total parts: ${list.size} (exported ${results.size})")
                    appendLine("Total series: $seriesCount")
                    appendLine("Resolution: ${if (quality == 720) "720x1280" else "1080x1920"}")
                    appendLine("Status: ${if (complete) "complete" else if (cancelled) "cancelled" else "failed: $failed"}")
                })
            }
            val rec = ProjectRecord(projectFolder, m.name, results.size, seriesCount, System.currentTimeMillis(), complete)
            Storage.addHistory(ctx, rec); history.add(0, rec)
            if (failed != null) error = "Export நின்றுவிட்டது: $failed\n${results.size} parts சேமிக்கப்பட்டன."
            finished = true
            screen = Screen.DONE
        }
    }

    fun cancel() { job?.cancel() }

    fun home() { screen = Screen.HOME }
}
