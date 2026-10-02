// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Intent
import android.provider.OpenableColumns
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.edit
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.KeySounds
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.filePicker

/** fork: pick an audio file for one custom key sound slot; it's copied into the app's own storage */
@Composable
fun CustomKeySoundPreference(setting: Setting) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    val nameKey = setting.key + "_name"
    val file = KeySounds.customFile(ctx, setting.key)
    val currentName = if (file.isFile) prefs.getString(nameKey, null) ?: "set" else null

    val picker = filePicker { uri ->
        try {
            val cr = ctx.contentResolver
            val name = cr.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
            file.parentFile?.mkdirs()
            cr.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            prefs.edit { putString(nameKey, name ?: "set") } // also refreshes the settings screen
            KeySounds.update(ctx)
            val volume = prefs.getFloat(Settings.PREF_KEYPRESS_SOUND_VOLUME, Defaults.PREF_KEYPRESS_SOUND_VOLUME)
            AudioAndHapticFeedbackManager.previewKeySound(ctx, volume)
        } catch (e: Exception) {
            Log.w("CustomKeySound", "could not copy sound file", e)
        }
    }
    Preference(
        name = setting.title,
        description = currentName ?: if (setting.key == KeySounds.PREF_CUSTOM_KEY) "Not set (tap to pick an audio file)"
            else "Not set (uses the keys sound)",
        onClick = {
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*"))
        },
        value = {
            if (currentName != null) {
                IconButton(onClick = {
                    file.delete()
                    prefs.edit { remove(nameKey) }
                    KeySounds.update(ctx)
                }) { Icon(painterResource(R.drawable.ic_bin), "Remove") }
            }
        }
    )
}
