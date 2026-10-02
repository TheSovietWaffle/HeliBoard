// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.File
import kotlin.random.Random

/**
 * Fork: key sound packs, played through a SoundPool for low latency, at MEDIA volume.
 * Built-in packs are linear switch recordings from Mechvibes (MIT, (c) Hai Nguyen), see tools/keysounds/CREDITS.md.
 * Each pack has a press sound (on touch down) and a quieter release sound (on finger up), like a real keystroke.
 * "custom" plays the user's own files, "system" keeps Android's own click sounds.
 */
object KeySounds {
    const val PREF_KEY_SOUND_PACK = "key_sound_pack"
    const val PACK_SYSTEM = "system"
    const val PACK_CREAM = "cream"
    const val PACK_MXBLACK = "mxblack"
    const val PACK_CUSTOM = "custom"
    const val DEFAULT_PACK = PACK_CREAM

    /** custom sound slots; the setting key is also used as the stored file name */
    const val PREF_CUSTOM_KEY = "key_sound_custom_key"
    const val PREF_CUSTOM_SPACE = "key_sound_custom_space"
    const val PREF_CUSTOM_DELETE = "key_sound_custom_delete"
    const val PREF_CUSTOM_ENTER = "key_sound_custom_enter"
    val CUSTOM_SLOTS = listOf(PREF_CUSTOM_KEY, PREF_CUSTOM_SPACE, PREF_CUSTOM_DELETE, PREF_CUSTOM_ENTER)

    /** used when the volume slider is on "system default" */
    private const val DEFAULT_VOLUME = 0.6f
    private const val TAG = "KeySounds"

    private class PackRes(
        val keys: List<Int>, val space: Int, val delete: Int, val enter: Int,
        val release: Int, val releaseSpace: Int, val releaseDelete: Int, val releaseEnter: Int,
    )

    /** loaded SoundPool ids, 0 = none */
    private class Pack(
        val keys: IntArray, val space: Int, val delete: Int, val enter: Int,
        val release: Int, val releaseSpace: Int, val releaseDelete: Int, val releaseEnter: Int,
    )

    private val builtIn = mapOf(
        PACK_CREAM to PackRes(
            listOf(R.raw.keysound_cream_key_1, R.raw.keysound_cream_key_2, R.raw.keysound_cream_key_3,
                R.raw.keysound_cream_key_4, R.raw.keysound_cream_key_5),
            R.raw.keysound_cream_space, R.raw.keysound_cream_delete, R.raw.keysound_cream_enter,
            R.raw.keysound_cream_release, R.raw.keysound_cream_release_space,
            R.raw.keysound_cream_release_delete, R.raw.keysound_cream_release_enter),
        PACK_MXBLACK to PackRes(
            listOf(R.raw.keysound_mxblack_key_1, R.raw.keysound_mxblack_key_2, R.raw.keysound_mxblack_key_3,
                R.raw.keysound_mxblack_key_4, R.raw.keysound_mxblack_key_5),
            R.raw.keysound_mxblack_space, R.raw.keysound_mxblack_delete, R.raw.keysound_mxblack_enter,
            R.raw.keysound_mxblack_release, R.raw.keysound_mxblack_release_space,
            R.raw.keysound_mxblack_release_delete, R.raw.keysound_mxblack_release_enter),
    )

    private var soundPool: SoundPool? = null
    private var loadedPackName: String? = null
    private var loadedCustomStamp = 0L
    private var pack: Pack? = null
    private var lastKeyVariant = -1

    @JvmStatic
    fun currentPack(prefs: SharedPreferences): String {
        val p = prefs.getString(PREF_KEY_SOUND_PACK, DEFAULT_PACK) ?: DEFAULT_PACK
        // packs from older builds of this fork were removed
        return if (p == PACK_SYSTEM || p == PACK_CUSTOM || builtIn.containsKey(p)) p else DEFAULT_PACK
    }

    fun customFile(context: Context, slot: String) = File(File(context.filesDir, "keysounds"), slot)

    /** (re)loads the selected pack if it changed; cheap to call on every settings change */
    @JvmStatic
    @JvmOverloads
    fun update(context: Context, packOverride: String? = null) {
        val wanted = packOverride ?: try { currentPack(context.prefs()) } catch (e: Exception) { DEFAULT_PACK }
        val customStamp = if (wanted == PACK_CUSTOM) CUSTOM_SLOTS.sumOf { customFile(context, it).lastModified() } else 0L
        if (wanted == loadedPackName && customStamp == loadedCustomStamp) return
        release()
        loadedPackName = wanted
        loadedCustomStamp = customStamp
        if (wanted == PACK_SYSTEM) return
        try {
            val pool = SoundPool.Builder()
                .setMaxStreams(6)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA) // follows the media volume
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .build()
            soundPool = pool
            pack = if (wanted == PACK_CUSTOM) loadCustom(context, pool) else loadBuiltIn(context, pool, builtIn.getValue(wanted))
        } catch (e: Exception) {
            Log.w(TAG, "could not load key sounds, falling back to system sounds", e)
            release()
        }
    }

    private fun loadBuiltIn(context: Context, pool: SoundPool, r: PackRes): Pack {
        fun l(id: Int) = pool.load(context, id, 1)
        return Pack(r.keys.map { l(it) }.toIntArray(), l(r.space), l(r.delete), l(r.enter),
            l(r.release), l(r.releaseSpace), l(r.releaseDelete), l(r.releaseEnter))
    }

    /** missing slots fall back to the normal key sound; no key sound at all = system sounds */
    private fun loadCustom(context: Context, pool: SoundPool): Pack? {
        fun l(slot: String): Int {
            val f = customFile(context, slot)
            return if (f.isFile && f.length() > 0) pool.load(f.absolutePath, 1) else 0
        }
        val key = l(PREF_CUSTOM_KEY)
        if (key == 0) return null
        fun orKey(id: Int) = if (id == 0) key else id
        return Pack(intArrayOf(key), orKey(l(PREF_CUSTOM_SPACE)), orKey(l(PREF_CUSTOM_DELETE)), orKey(l(PREF_CUSTOM_ENTER)),
            0, 0, 0, 0)
    }

    /**
     * Press sound.
     * @param volume 0..1, or negative for "system default"
     * @return false if the system sound should be played instead
     */
    @JvmStatic
    fun play(code: Int, volume: Float): Boolean {
        val pool = soundPool ?: return false
        val p = pack ?: return false
        val id = when (code) {
            KeyCode.DELETE -> p.delete
            Constants.CODE_ENTER -> p.enter
            Constants.CODE_SPACE -> p.space
            else -> {
                // pick a different variant than last time, so fast typing doesn't sound like a machine gun
                var v = Random.nextInt(p.keys.size)
                if (p.keys.size > 1 && v == lastKeyVariant) v = (v + 1) % p.keys.size
                lastKeyVariant = v
                p.keys[v]
            }
        }
        return playId(pool, id, volume)
    }

    /** release (finger up) sound, if the pack has one */
    @JvmStatic
    fun playRelease(code: Int, volume: Float) {
        val pool = soundPool ?: return
        val p = pack ?: return
        val id = when (code) {
            KeyCode.DELETE -> p.releaseDelete
            Constants.CODE_ENTER -> p.releaseEnter
            Constants.CODE_SPACE -> p.releaseSpace
            else -> p.release
        }
        playId(pool, id, volume)
    }

    private fun playId(pool: SoundPool, id: Int, volume: Float): Boolean {
        if (id == 0) return false
        val vol = if (volume < 0f) DEFAULT_VOLUME else volume.coerceIn(0f, 1f)
        val rate = 0.985f + Random.nextFloat() * 0.03f // tiny pitch wobble, sounds more natural
        return pool.play(id, vol, vol, 1, 0, rate) != 0
    }

    private fun release() {
        soundPool?.release()
        soundPool = null
        pack = null
        loadedPackName = null
        loadedCustomStamp = 0L
    }
}
