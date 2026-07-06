// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.content.Context

/** Actions assignable to a custom RC button, mirroring what DJI GO 4 lets you bind to C1/C2. */
enum class RcButtonAction(val label: String) {
    NONE("None"),
    PUSH_TO_TALK("Push-to-Talk (Co-Pilot)"),
    TOGGLE_LANDING_LIGHT("Toggle Landing Light"),
    RETURN_HOME("Return to Home"),
    TAKE_PHOTO("Take Photo"),
    TOGGLE_RECORD("Start / Stop Record"),
    TOGGLE_CAMERA_MODE("Switch Photo / Video"),
}

/**
 * A learned "this bit pattern means this physical button is pressed" signature. cmd_id 0x51's
 * low 7 bits are now CONFIRMED (2026-07-03, live device, see sdk/docs/protocol.md) as one
 * distinct bit per RC240 control (both triggers + all five states of the screen-side dial), 
 * but that mapping isn't hardcoded here, since the whole point of this flow is per-unit, no-
 * guessing calibration: the pilot teaches GlassFalcon which raw value means "Button 1" once,
 * live, the same way the takeoff opcode was ultimately confirmed, except here it's a simple
 * in-app press-and-detect flow instead of a kernel capture, since a wrong guess has zero
 * flight-safety consequence.
 */
data class RcButtonSignature(val cmdId: Int, val mask: Int, val value: Int) {
    fun matches(payload: Int) = (payload and mask) == value

    fun encode() = "$cmdId:$mask:$value"

    companion object {
        fun decode(s: String): RcButtonSignature? {
            val p = s.split(":").mapNotNull { it.toIntOrNull() }
            return if (p.size == 3) RcButtonSignature(p[0], p[1], p[2]) else null
        }
    }
}

/** One button slot: its learned signature (null until the pilot runs "Learn") and the action
 *  it's bound to. Two slots by default, matching the RC240's two physical custom buttons. */
data class RcButtonSlot(val signature: RcButtonSignature?, val action: RcButtonAction)

/** A transient on-screen announcement that a physical RC button was just pressed, the slot's
 *  name and the item it's bound to, shown at the top of the HUD. [sticky] keeps it up (a toggle
 *  that latched ON, like the landing light) instead of auto-fading (a momentary press, like RTH
 *  or a photo). [id] is a monotonic stamp so the HUD's auto-hide timer only clears the exact
 *  banner it started counting on, not a newer one that replaced it. */
data class RcButtonFlash(val text: String, val sticky: Boolean, val id: Long)

/** Persists slot bindings across app restarts, unlike the AI API keys (deliberately
 *  session-only), a button mapping is exactly the kind of thing a pilot sets up once and
 *  expects to stick, so this uses plain SharedPreferences instead.
 *
 *  Slot count starts at 2 (the RC240's two dedicated custom/trigger buttons) but isn't capped
 *  there, the RC240 also has a 5-way dial near the screen (up/down/left/right/press) whose
 *  raw signature is just as learnable as C1/C2, so [addSlot] lets the pilot keep going instead
 *  of hardcoding a fixed physical button count that turned out to be wrong. */
object RcButtonStore {
    private const val PREFS = "rc_buttons"
    private const val COUNT_KEY = "slot_count"
    private const val DEFAULT_COUNT = 2

    fun load(ctx: Context): List<RcButtonSlot> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(COUNT_KEY, DEFAULT_COUNT)
        return (1..count).map { i ->
            val sig = prefs.getString("sig$i", null)?.let(RcButtonSignature::decode)
            val action = prefs.getString("action$i", null)
                ?.let { runCatching { RcButtonAction.valueOf(it) }.getOrNull() }
                // Default bindings per the pilot's layout: C1 = landing-light toggle, C2 = Co-Pilot
                // push-to-talk. (Only applied until the slot is explicitly reassigned in Settings.)
                ?: when (i) { 1 -> RcButtonAction.TOGGLE_LANDING_LIGHT; 2 -> RcButtonAction.PUSH_TO_TALK; else -> RcButtonAction.NONE }
            RcButtonSlot(sig, action)
        }
    }

    fun save(ctx: Context, slotIndex: Int, slot: RcButtonSlot) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (slot.signature != null) putString("sig$slotIndex", slot.signature.encode())
            putString("action$slotIndex", slot.action.name)
        }.apply()
    }

    /** Adds one more blank slot (default action NONE) and returns its 1-based index. */
    fun addSlot(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val newCount = prefs.getInt(COUNT_KEY, DEFAULT_COUNT) + 1
        prefs.edit().putInt(COUNT_KEY, newCount).apply()
        return newCount
    }
}
