package ml.melun.mangaview.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, CURRENT, DOWNLOADING, READY, FAILED }
internal data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val visible: Boolean = false,
    val release: UpdateRelease? = null,
    val percent: Int? = null,
    val file: File? = null,
    val message: String = "",
)

internal class AppUpdateViewModel(application: Application, private val repository: AppUpdateRepository) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AppUpdateRepository(application))
    private val mutable = MutableStateFlow(AppUpdateState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var automaticChecked = false
    private var checkGeneration = 0L
    private var pendingAutomaticInstall = false
    @Suppress("DEPRECATION")
    private val installedVersion = application.packageManager.getPackageInfo(application.packageName, 0).longVersionCode

    fun check() {
        if (operation?.isActive == true) { mutable.update { it.copy(visible = true) }; return }
        operation = viewModelScope.launch { checkRelease(silent = false) }
    }

    /** The Activity runs this only while resumed; it cannot continue into a reading session. */
    suspend fun checkAutomatically() {
        if (automaticChecked || operation?.isActive == true || mutable.value.phase != UpdatePhase.IDLE) return
        try { checkRelease(silent = true); automaticChecked = true }
        catch (cancelled: CancellationException) { throw cancelled }
    }

    private suspend fun checkRelease(silent: Boolean) {
        val generation = ++checkGeneration
        if (!silent) mutable.value = AppUpdateState(UpdatePhase.CHECKING, visible = true)
        try {
            val release = repository.latest()
            // A manual check/download takes precedence over an automatic result arriving later.
            if (generation != checkGeneration) return
            mutable.value = AppUpdateState(if (release.newerThan(installedVersion)) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT,
                visible = !silent || release.newerThan(installedVersion), release = release,
                message = "현재 버전: $installedVersion\n배포 버전: ${release.label}")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!silent) mutable.value = AppUpdateState(UpdatePhase.FAILED, visible = true,
                message = failure.message ?: "업데이트 정보를 가져오지 못했습니다")
        }
    }

    fun download() {
        val release = mutable.value.release ?: return
        if (!release.newerThan(installedVersion) || operation?.isActive == true) return
        operation = viewModelScope.launch {
            mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, visible = true, percent = null, file = null) }
            try {
                val file = repository.download(release) { value -> mutable.update { it.copy(percent = value) } }
                pendingAutomaticInstall = true
                mutable.update { it.copy(phase = UpdatePhase.READY, file = file, message = "업데이트 파일을 확인했습니다. 설치를 계속해 주세요.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutable.update { it.copy(phase = UpdatePhase.FAILED, message = failure.message ?: "다운로드에 실패했습니다") }
            }
        }
    }

    fun dismiss() { mutable.update { it.copy(visible = false) } }
    fun consumePendingInstall(file: File): Boolean {
        if (!pendingAutomaticInstall || mutable.value.file != file) return false
        pendingAutomaticInstall = false
        return true
    }
    fun installationFailure(message: String) { mutable.update { it.copy(visible = true, message = message) } }
}
