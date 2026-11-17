package app.amipro.editor

import android.app.Application

class AmiProApp : Application() {
    /** One editor for the whole process, shared by every activity instance. */
    val editor: EditorViewModel by lazy { EditorViewModel(this) }
    /** Update check, download and install; only ever started by the person's tap. */
    val updater: Updater by lazy { Updater(this) }
}
