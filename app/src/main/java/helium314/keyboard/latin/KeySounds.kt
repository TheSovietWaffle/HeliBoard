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
import kotlin.random.Random

/**
 * Fork: custom key sound packs (synthesized by tools/keysounds/synth.py, free to use),
 * played through a SoundPool for low latency. "system" keeps Android's own click sounds.
 */
object KeySounds {
    const val PREF_KEY_SOUND_PACK = "key_sound_pack"
    const val PACK_SYSTEM = "system"
    const val PACK_SOFT = "soft"
    const val PACK_THOCK = "thock"
    const val DEFAULT_PACK = PACK_SOFT
    /** used when the volume slider is on "system default" */
    private const val DEFAULT_VOLUME = 0.55f
    private const val TAG = "KeySounds"

    private class Pack(val keys: IntArray, val space: Int, val delete: Int, val enter: Int)

    private val resources = mapOf(
        PACK_SOFT to listOf(R.raw.keysound_soft_key_1, R.raw.keysound_soft_key_2, R.raw.keysound_soft_key_3,
            R.raw.keysound_soft_space, R.raw.keysound_soft_delete, R.raw.keysound_soft_enter),
        PACK_THOCK to listOf(R.raw.keysound_thock_key_1, R.raw.keysound_thock_key_2, R.raw.keysound_thock_key_3,
            R.raw.keysound_thock_space, R.raw.keysound_thock_delete, R.raw.keysound_thock_enter),
    )

    private var soundPool: SoundPool? = null
    private var loadedPackName: String? = null
    private var pack: Pack? = null
    private var lastKeyVariant = -1

    @JvmStatic
    fun currentPack(prefs: SharedPreferences): String =
        prefs.getString(PREF_KEY_SOUND_PACK, DEFAULT_PACK) ?: DEFAULT_PACK

    /** (re)loads the selected pack if it changed; cheap to call on every settings change */
    @JvmStatic
    @JvmOverloads
    fun update(context: Context, packOverride: String? = null) {
        val wanted = packOverride ?: try { currentPack(context.prefs()) } catch (e: Exception) { DEFAULT_PACK }
        if (wanted == loadedPackName) return
        release()
        loadedPackName = wanted
        val ids = resources[wanted] ?: return // system pack: nothing to load
        try {
            val pool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .build()
            val loaded = ids.map { pool.load(context, it, 1) }
            soundPool = pool
            pack = Pack(intArrayOf(loaded[0], loaded[1], loaded[2]), loaded[3], loaded[4], loaded[5])
        } catch (e: Exception) {
            Log.w(TAG, "could not load key sounds, falling back to system sounds", e)
            release()
        }
    }

    /**
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
                if (v == lastKeyVariant) v = (v + 1) % p.keys.size
                lastKeyVariant = v
                p.keys[v]
            }
        }
        val vol = if (volume < 0f) DEFAULT_VOLUME else volume.coerceIn(0f, 1f)
        val rate = 0.98f + Random.nextFloat() * 0.04f // tiny pitch wobble, sounds more natural
        return pool.play(id, vol, vol, 1, 0, rate) != 0
    }

    private fun release() {
        soundPool?.release()
        soundPool = null
        pack = null
        loadedPackName = null
    }
}
