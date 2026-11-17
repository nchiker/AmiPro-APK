package app.amipro.editor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How the app picks light or dark: follow the phone (default) or a fixed choice. */
enum class ThemeChoice(val label: String) { SYSTEM("Follow the phone"), LIGHT("Light"), DARK("Dark") }

class AmiProApp : Application() {
    /** One editor for the whole process, shared by every activity instance. */
    val editor: EditorViewModel by lazy { EditorViewModel(this) }
    /** Update check, download and install; only ever started by the person's tap. */
    val updater: Updater by lazy { Updater(this) }

    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    var themeChoice by mutableStateOf(ThemeChoice.SYSTEM)
        private set

    override fun onCreate() {
        super.onCreate()
        themeChoice = runCatching { ThemeChoice.valueOf(settings.getString("theme", null) ?: "SYSTEM") }.getOrDefault(ThemeChoice.SYSTEM)
    }

    fun chooseTheme(choice: ThemeChoice) {
        themeChoice = choice
        settings.edit().putString("theme", choice.name).apply()
    }
}
