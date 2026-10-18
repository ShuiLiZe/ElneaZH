package com.shuilize.elneazh

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shuilize.elneazh.core.Pipeline
import com.shuilize.elneazh.core.Signer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

enum class Phase { IDLE, WORKING, DONE, ERROR }

data class UiState(
    val searching: Boolean = true,
    val game: GameInfo? = null,
    val pickingFile: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val step: Int = -1,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
    val outputs: List<File> = emptyList(),
    val gameInstalled: Boolean = false,
    val patchInfo: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val outDir get() = File(ctx.cacheDir, "out")

    init {
        _state.update { it.copy(patchInfo = readPatchInfo()?.optString("label")?.takeIf { s -> s.isNotEmpty() }) }
        search()
    }

    private fun readPatchInfo(): JSONObject? = try {
        ctx.assets.open("elnea_zh.json").use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (e: Exception) {
        null
    }

    fun search() {
        _state.update { it.copy(searching = true, error = null) }
        viewModelScope.launch {
            val g = withContext(Dispatchers.IO) { runCatching { GameFinder.find(ctx) }.getOrNull() }
            _state.update {
                it.copy(
                    searching = false, game = g,
                    gameInstalled = g != null,
                    phase = if (it.phase == Phase.ERROR) Phase.IDLE else it.phase,
                )
            }
        }
    }

    /** Called on resume: notices when the original game has been uninstalled. */
    fun refreshInstalled() {
        val g = _state.value.game ?: return
        val installed = GameFinder.isInstalled(ctx, g.packageName)
        _state.update { it.copy(gameInstalled = installed) }
    }

    fun onFilePicked(uri: Uri) {
        _state.update { it.copy(pickingFile = true, error = null) }
        viewModelScope.launch {
            try {
                val g = withContext(Dispatchers.IO) {
                    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    val dir = File(ctx.cacheDir, "picked").apply { deleteRecursively(); mkdirs() }
                    val dst = File(dir, "picked.apk")
                    (ctx.contentResolver.openInputStream(uri) ?: throw FileNotFoundException()).use { input ->
                        dst.outputStream().use { input.copyTo(it, 1 shl 16) }
                    }
                    if (Pipeline.findDataApk(listOf(dst)) < 0) throw Pipeline.FriendlyException("这个文件里没有游戏资源（data.unity3d），请选择 Elnea Kingdom 的 APK。")
                    val info = GameFinder.fromFile(ctx, dst, name)
                    info
                }
                _state.update {
                    it.copy(
                        pickingFile = false, game = g, phase = Phase.IDLE, outputs = emptyList(),
                        gameInstalled = GameFinder.isInstalled(ctx, g.packageName),
                    )
                }
            } catch (e: Pipeline.FriendlyException) {
                _state.update { it.copy(pickingFile = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(pickingFile = false, error = "读取所选文件失败：" + (e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun start() {
        val g = _state.value.game ?: return
        if (_state.value.phase == Phase.WORKING) return
        _state.update { it.copy(phase = Phase.WORKING, step = 0, progress = 0f, message = "准备中…", error = null, outputs = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                outDir.deleteRecursively()
                outDir.mkdirs()
                val need = g.apks.sumOf { it.length() } * 2 + 120L * 1024 * 1024
                if (outDir.usableSpace in 1 until need) {
                    throw Pipeline.FriendlyException("手机存储空间不足：大约需要 ${need / 1024 / 1024} MB 可用空间。")
                }
                val patch = File(outDir, "elnea_zh.patch")
                try {
                    ctx.assets.open("elnea_zh.patch").use { input -> patch.outputStream().use { input.copyTo(it, 1 shl 16) } }
                } catch (e: FileNotFoundException) {
                    throw Pipeline.FriendlyException("汉化器内缺少补丁文件（elnea_zh.patch），请下载完整的构建版本。")
                }
                val info = readPatchInfo()
                val signer = Signer(ctx.assets.open("sign.pk8"), ctx.assets.open("sign.x509.der"))
                val outs = Pipeline(outDir, signer).run(g.apks, patch, info?.optString("new_sha256")) { step, f, msg ->
                    _state.update { it.copy(step = step, progress = f.coerceIn(0f, 1f), message = msg) }
                }
                patch.delete()
                _state.update { it.copy(phase = Phase.DONE, step = 4, progress = 1f, message = "汉化版已生成", outputs = outs) }
            } catch (e: Pipeline.FriendlyException) {
                _state.update { it.copy(phase = Phase.ERROR, error = e.message) }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(phase = Phase.ERROR, error = "内存不足，请关闭其他应用后重试。") }
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                val msg = if (reason.contains("ENOSPC", true) || reason.contains("No space", true))
                    "手机存储空间不足，请清理后重试。" else "汉化失败：$reason"
                _state.update { it.copy(phase = Phase.ERROR, error = msg) }
            }
        }
    }
}
