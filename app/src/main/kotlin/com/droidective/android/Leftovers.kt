package com.droidective.android

import android.content.Context
import com.droidective.android.screenrecord.ScreenRecorder
import com.droidective.core.device.ScreenRecording
import com.droidective.core.shell.Shell
import com.droidective.core.shell.ShellUnavailableException
import com.droidective.platform.SharedStorage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a process that ended without cleaning up left behind (swiped away, force stopped, killed),
 * cleaned up by the next one: changes made for a while, which go back ([PutBacks]); a screen
 * recording cut short, which goes to Movies like any other; and files only ever meant to last a
 * moment. Only what's older than this process: the tap that started it may already be recording.
 */
object Leftovers {
    suspend fun clean(app: DroidectiveApplication, startedAt: Long) {
        app.access.settled()
        app.access.shell()?.let { shell ->
            app.putBacks.putBackAll(shell, app.setToggle, before = startedAt)
            rescueRecordings(app, shell, startedAt)
        }
        withContext(Dispatchers.IO) {
            // Anything else there was on its way to Photos or Downloads when the app went.
            SharedStorage.handover(app)?.let { folder ->
                olderFiles(folder, startedAt)
                    .filterNot { ScreenRecording.isRecording(it.name) }
                    .forEach(File::delete)
            }
            // Copies of other apps' files, made while the File Explorer or Sandbox Browser had
            // one open.
            listOfNotNull(app.getExternalFilesDir("files"), app.getExternalFilesDir("sandbox"))
                .forEach { folder -> olderFiles(folder, startedAt).forEach(File::delete) }
        }
    }

    /** Recordings screenrecord finished writing after the app went: saved to Movies. */
    private suspend fun rescueRecordings(context: Context, shell: Shell, startedAt: Long) {
        val folder = SharedStorage.handover(context) ?: return
        val recordings =
            withContext(Dispatchers.IO) {
                olderFiles(folder, startedAt).filter { ScreenRecording.isRecording(it.name) }
            }
        for (file in recordings) {
            try {
                // screenrecord wrote it as the shell user, for its own eyes only.
                if (!shell.run(ScreenRecording.readable(file.path)).succeeded) continue
                SharedStorage.publish(
                    context,
                    file,
                    ScreenRecorder.MIME,
                    SharedStorage.Place.Movies,
                )
            } catch (_: ShellUnavailableException) {
                return
            } catch (_: IOException) {
                // Left for the next start; Movies may have room by then.
            }
        }
    }

    private fun olderFiles(folder: File, startedAt: Long): List<File> =
        folder.listFiles().orEmpty().filter { it.isFile && it.lastModified() < startedAt }
}
