package io.github.floatingclock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Process-owned disk repositories. Loading settings never starts a service or a time source. */
internal object AppStorage {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val current = MutableStateFlow(UserPreferences())
    val preferences = current.asStateFlow()
    var ready by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var initialized = false
    lateinit var settings: SettingsRepository
        private set
    lateinit var diagnostics: DiagnosticRepository
        private set

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        settings = SettingsRepository.open(File(app.noBackupFilesDir, "settings.pb"), diskScope)
        diagnostics = DiagnosticRepository(Room.databaseBuilder(app, DiagnosticDatabase::class.java,
            File(app.noBackupFilesDir, "diagnostics.db").absolutePath).addMigrations(DiagnosticDatabase.MIGRATION_1_2).build())
        scope.launch {
            try {
                settings.update { it } // Canonicalize old/invalid fields once, atomically.
                settings.preferences.collect { value ->
                    current.value = value
                    OverlayState.preferences = value
                    if (!OverlayState.running && !OverlayState.requested) {
                        OverlayState.sourceChoice = value.sourceChoice
                        OverlayState.httpUrl = value.httpUrl
                    }
                    OverlayState.configure(value.overlay)
                    ready = true
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "配置读取失败，未启动悬浮服务；请释放空间后重新打开应用"
            }
        }
        work { diagnostics.prune() }
    }

    fun update(change: (UserPreferences) -> UserPreferences) = work { settings.update(change) }
    fun configure(change: (OverlayConfig) -> OverlayConfig) = update { it.copy(overlay = change(it.overlay)) }
    fun reportReadFailure() { error = "诊断数据读取失败；未清除数据库，请检查存储空间" }
    fun work(action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "本地数据写入失败，请检查可用存储空间；本次更改可能未保存"
            }
        }
    }
}
