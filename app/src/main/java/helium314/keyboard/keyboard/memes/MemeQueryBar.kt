// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.memes

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.util.Consumer
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * Fork feature: the one-line search box shown in the strip area while typing a new meme search.
 * The keyboard's own keys feed it (see KeyboardActionListenerImpl), since an IME can't type into its own views.
 */
class MemeQueryBar(context: Context) : LinearLayout(context) {
    private val text: TextView
    private val backButton: ImageButton
    private val searchButton: ImageButton
    private val buffer = StringBuilder()

    /** called with true to search, false to go back to the results unchanged */
    var onDone: Consumer<Boolean>? = null

    val query: String get() = buffer.toString().trim()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        backButton = ImageButton(context, null, R.attr.suggestionWordStyle).apply {
            setImageResource(R.drawable.ic_arrow_left)
            contentDescription = "Back to results"
            setOnClickListener { onDone?.accept(false) }
        }
        text = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START // keep the end (where you type) visible
            setPadding(dp(8), 0, dp(8), 0)
        }
        searchButton = ImageButton(context, null, R.attr.suggestionWordStyle).apply {
            setImageResource(R.drawable.ic_meme_search)
            contentDescription = "Search"
            setOnClickListener { onDone?.accept(true) }
        }
        addView(backButton, LayoutParams(dp(BUTTON_DP), LayoutParams.MATCH_PARENT))
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(searchButton, LayoutParams(dp(BUTTON_DP), LayoutParams.MATCH_PARENT))
    }

    fun start(initial: String) {
        buffer.setLength(0)
        buffer.append(initial)
        Settings.getValues()?.mColors?.let { colors ->
            colors.setBackground(this, ColorType.STRIP_BACKGROUND)
            text.setTextColor(colors.get(ColorType.KEY_TEXT))
            colors.setColor(backButton, ColorType.TOOL_BAR_KEY)
            colors.setColor(searchButton, ColorType.TOOL_BAR_KEY)
        }
        render()
    }

    fun append(s: CharSequence) {
        buffer.append(s)
        render()
    }

    fun deleteLast() {
        if (buffer.isEmpty()) return
        val cp = Character.codePointBefore(buffer, buffer.length)
        buffer.setLength(buffer.length - Character.charCount(cp))
        render()
    }

    private fun render() {
        text.text = "🔍 $buffer▏"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BUTTON_DP = 44
    }
}
