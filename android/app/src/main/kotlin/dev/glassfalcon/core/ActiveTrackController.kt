// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.view.TextureView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ActiveTrackMode { OFF, SEARCHING, LOCKED }

data class ActiveTrackStatus(
    val mode: ActiveTrackMode = ActiveTrackMode.OFF,
    // Last known bbox of the locked subject, fractional (0..1) within the analyzed video frame.
    val bboxFrac: RectF? = null,
    val label: String = "",
    // Follow-at-a-distance armed (drone drives forward/back to hold the subject's apparent size),
    // vs the default "watch me" that only yaws in place. Reflects [setFollow]'s live state.
    val following: Boolean = false,
)

/**
 * ActiveTrack with two behaviours the caller picks between via [setFollow]:
 *  - "Watch me" (default): the drone holds its current position (neutral roll/pitch/throttle,
 *    GPS position hold does the rest) and only yaws to keep the tapped subject's horizontal bbox
 *    center at frame-center. No forward-following, no orbiting.
 *  - Follow: on top of the same yaw-centering, the drone also drives forward/back to hold the
 *    subject's apparent SIZE constant. Monocular distance proxy: bigger bbox → subject closer →
 *    pitch back; smaller bbox → subject farther → pitch forward. This is a genuine
 *    follow-at-a-distance, but a deliberately conservative one, capped forward stick, a size
 *    deadband so it doesn't hunt, and the same forward-obstacle gate + telemetry-staleness bail
 *    TapFlyController already uses (see [attachObstacleState]).
 *
 * Subject detection is Google ML Kit Object Detection & Tracking
 * (`com.google.mlkit:object-detection:17.0.2`, verified against the real published artifact
 * at dl.google.com/android/maven2 and decompiled directly, same discipline as NanoCopilot's
 * genai-prompt precedent, not a version guessed from docs). STREAM_MODE + enableMultipleObjects()
 * so a tap has more than one candidate to choose from when several subjects are in frame;
 * classification is left off (default), unclassified "salient object" boxes are good enough to
 * pick a subject by tap and are more general-purpose than a fixed label set.
 *
 * Frame source is [TextureView.getBitmap], polled on a timer, there is no CameraX/ImageProxy
 * pipeline here, the live video is DUML H.264 decoded straight into a Surface (see
 * VideoDecoder/MainScreen). Per AOSP's TextureView source (getBitmap(Bitmap) calls
 * applyTransformMatrix() before copyInto(), i.e. it bakes in whatever matrix
 * `setTransform()` last applied), the captured bitmap already reflects MainScreen's own
 * `scaleVideoToFit` letterbox/pillarbox correction, so a bbox's fractional position within the
 * analyzed bitmap maps 1:1 onto the same fraction of the TextureView's own displayed size, with
 * no second letterbox correction needed on top. (This is a deliberate deviation from re-deriving
 * scaleVideoToFit's own scale factors a second time for the overlay: doing that too would
 * double-apply the correction and be wrong.)
 */
class ActiveTrackController(
    private val duml: DumlConnection,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(ActiveTrackStatus())
    val status: StateFlow<ActiveTrackStatus> = _status

    private var droneState: DroneState = DroneState()
    private var obstacleState: ObstacleState = ObstacleState()
    @Volatile private var lastStateMs = 0L
    private val STALE_MS = 1500L

    fun attachDroneState(state: DroneState) {
        droneState = state
        lastStateMs = System.currentTimeMillis()
    }
    fun attachObstacleState(state: ObstacleState) { obstacleState = state }

    private fun telemetryFresh() =
        lastStateMs != 0L && System.currentTimeMillis() - lastStateMs < STALE_MS

    // Tuning, same P-gain philosophy as MissionEngine.YAW_KP, not independently re-verified
    // against real flight for THIS control loop specifically; a reasonable starting point.
    private val YAW_KP = 0.8f
    private val POLL_MS = 150L       // ~6.7Hz frame-analysis cadence, within the 4-8Hz asked for
    private val STICK_MS = 50L       // 20Hz stick refresh, matches MissionEngine / virtual-RC
    private val MAX_MISSES = 8       // consecutive missed frames before auto-cancel (5-10 range)
    private val POLL_W = 480
    private val POLL_H = 270         // fixed poll-bitmap size; independent of the view's own
                                      // aspect (getBitmap always maps view content into whatever
                                      // size bitmap you hand it, so this need not match exactly)

    // Follow-mode tuning, same "reasonable starting point, unverified against real flight"
    // caveat as the yaw gain above. FOLLOW_KP acts on the RELATIVE size error (how far the
    // subject's apparent size has drifted from its locked reference, as a fraction of that
    // reference), so it's unit-independent. PITCH_CAP / OBSTACLE_STOP_CM are carried straight
    // over from TapFlyController so both vision modes share one forward-speed and one
    // obstacle-stop convention.
    private val FOLLOW_KP = 1.2f
    private val PITCH_CAP = 0.35f    // capped forward/back stick fraction (matches TapFly)
    private val SIZE_DEADBAND = 0.10f // ignore <10% size drift so it doesn't hunt in place
    private val OBSTACLE_STOP_CM = 50 // clamp forward pitch to 0 when something's this close ahead

    private var detectJob: Job? = null
    private var stickJob: Job? = null
    private var textureViewRef: TextureView? = null
    private var lockedTrackingId: Int? = null
    private var missCount = 0
    @Volatile private var yawOut = 0f
    @Volatile private var pitchOut = 0f
    // Follow is a sticky mode preference toggled by [setFollow]; it persists across arm cycles.
    @Volatile private var following = false
    // Linear apparent-size proxy sqrt(bboxArea/frameArea) of the locked subject: [referenceSize]
    // is the size captured when follow engaged (the distance to hold), [currentSize] the latest.
    @Volatile private var referenceSize = 0f
    @Volatile private var currentSize = 0f

    private var lastDetections: List<DetectedObject> = emptyList()
    private var lastFrameW = 0
    private var lastFrameH = 0
    private val pollBitmap by lazy { Bitmap.createBitmap(POLL_W, POLL_H, Bitmap.Config.ARGB_8888) }

    private val detector: ObjectDetector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .build()
        )
    }

    /** Arms ActiveTrack: starts polling frames and running detection, but sends no stick
     *  commands yet, that only starts once [selectAtTap] locks onto a subject. */
    fun arm(view: TextureView) {
        if (_status.value.mode != ActiveTrackMode.OFF) return
        textureViewRef = view
        lockedTrackingId = null
        missCount = 0
        referenceSize = 0f; currentSize = 0f; pitchOut = 0f
        _status.value = ActiveTrackStatus(
            mode = ActiveTrackMode.SEARCHING,
            label = "ActiveTrack armed, tap a subject on the video",
            following = following,
        )
        detectJob = scope.launch { detectLoop() }
    }

    /** Toggle follow-at-a-distance on/off. Sticky across arm cycles. Turning it on mid-track
     *  captures the subject's current apparent size as the distance to hold from here on; turning
     *  it off drops back to pure yaw-centering and zeroes any forward stick. */
    fun setFollow(on: Boolean) {
        following = on
        if (!on) { pitchOut = 0f }
        else if (currentSize > 0f) referenceSize = currentSize
        if (_status.value.mode != ActiveTrackMode.OFF) {
            _status.value = _status.value.copy(following = on)
        }
    }

    /** Tap-to-select: locks onto whichever detected object (from the most recently analyzed
     *  frame) is nearest the tapped point. [xFrac]/[yFrac] are 0..1 fractions of the video area
     *  the tap landed in, the same space the bbox overlay is drawn in. */
    fun selectAtTap(xFrac: Float, yFrac: Float) {
        if (_status.value.mode != ActiveTrackMode.SEARCHING) return
        if (lastFrameW <= 0 || lastFrameH <= 0 || lastDetections.isEmpty()) {
            _status.value = _status.value.copy(label = "ActiveTrack: nothing detected yet, try again")
            return
        }
        val tapX = xFrac * lastFrameW
        val tapY = yFrac * lastFrameH
        val nearest = lastDetections
            .filter { it.trackingId != null }
            .minByOrNull { obj ->
                val r = obj.boundingBox
                val dx = r.exactCenterX() - tapX
                val dy = r.exactCenterY() - tapY
                dx * dx + dy * dy
            }
        if (nearest == null) {
            _status.value = _status.value.copy(label = "ActiveTrack: no trackable subject there, try again")
            return
        }
        lockedTrackingId = nearest.trackingId
        missCount = 0
        currentSize = sizeProxy(nearest.boundingBox, lastFrameW, lastFrameH)
        referenceSize = currentSize   // hold whatever distance the subject is at when locked
        pitchOut = 0f
        _status.value = ActiveTrackStatus(
            mode = ActiveTrackMode.LOCKED,
            bboxFrac = rectToFrac(nearest.boundingBox, lastFrameW, lastFrameH),
            label = if (following) "FOLLOWING, holding distance, keeping subject centered"
                    else "TRACKING, holding position, panning to keep subject centered",
            following = following,
        )
        stickJob = scope.launch { stickLoop() }
    }

    /** User-initiated cancel, stops both loops, zeroes sticks. */
    fun stop() = cancelAll(label = "", cancelDetect = true, cancelStick = true)

    private fun cancelAll(label: String, cancelDetect: Boolean, cancelStick: Boolean) {
        if (cancelDetect) { detectJob?.cancel(); detectJob = null }
        if (cancelStick) { stickJob?.cancel(); stickJob = null }
        textureViewRef = null
        lockedTrackingId = null
        yawOut = 0f; pitchOut = 0f
        currentSize = 0f; referenceSize = 0f
        duml.send(FlyC.joystick(0f, 0f, 0f, 0f))
        // Preserve the sticky follow preference so the next arm keeps the pilot's choice.
        _status.value = ActiveTrackStatus(label = label, following = following)
    }

    private suspend fun detectLoop() {
        while (detectJob?.isActive == true) {
            val tv = textureViewRef
            if (tv == null || tv.width <= 0 || tv.height <= 0) { delay(POLL_MS); continue }
            // getBitmap() touches the view's hardware layer, keep it on the main thread rather
            // than assume background-thread safety that isn't documented either way.
            val bmp = runCatching { withContext(Dispatchers.Main) { tv.getBitmap(pollBitmap) } }.getOrNull()
            if (bmp != null) {
                lastFrameW = bmp.width
                lastFrameH = bmp.height
                val image = InputImage.fromBitmap(bmp, 0)
                val objects = withContext(Dispatchers.Default) {
                    runCatching { detector.process(image).awaitResult() }.getOrDefault(emptyList())
                }
                lastDetections = objects

                if (_status.value.mode == ActiveTrackMode.LOCKED) {
                    val id = lockedTrackingId
                    val match = objects.firstOrNull { it.trackingId == id }
                    if (match != null) {
                        missCount = 0
                        val centerFrac = match.boundingBox.exactCenterX() / lastFrameW
                        val err = (centerFrac - 0.5f) * 2f   // + = subject right of center
                        yawOut = (err * YAW_KP).coerceIn(-1f, 1f)
                        currentSize = sizeProxy(match.boundingBox, lastFrameW, lastFrameH)
                        pitchOut = followPitch()
                        _status.value = _status.value.copy(bboxFrac = rectToFrac(match.boundingBox, lastFrameW, lastFrameH))
                    } else {
                        missCount++
                        if (missCount >= MAX_MISSES) {
                            cancelAll(
                                label = "ActiveTrack auto-cancelled, lost the subject for $MAX_MISSES frames",
                                cancelDetect = false,   // we ARE detectJob; don't cancel our own coroutine
                                cancelStick = true,
                            )
                            return
                        }
                    }
                }
            }
            delay(POLL_MS)
        }
    }

    private suspend fun stickLoop() {
        while (stickJob?.isActive == true) {
            if (!telemetryFresh() || !droneState.hasGpsFix) {
                // Mirrors MissionEngine's staleness-bailout discipline: never fly on stale data,
                // and here specifically, no live link/GPS fix means cancel outright rather than
                // silently holding zero sticks hoping telemetry comes back.
                cancelAll(
                    label = "ActiveTrack cancelled, no live drone link / GPS fix",
                    cancelDetect = true,
                    cancelStick = false,   // we ARE stickJob; don't cancel our own coroutine
                )
                return
            }
            // Follow drives forward/back; watch-me leaves pitch at 0. Forward motion is gated on
            // front-obstacle clearance the same way TapFly stops, but here we only CLAMP forward
            // (a positive/forward pitch) to zero rather than cancel the whole track: backing off
            // and yaw-centering stay live so the subject isn't lost just because it walked near a
            // wall. frontClosest is the same live reading the HUD's obstacle glow shows.
            var pitch = if (following) pitchOut else 0f
            val front = obstacleState.frontClosest
            if (pitch > 0f && front != null && front < OBSTACLE_STOP_CM) pitch = 0f
            duml.send(FlyC.joystick(0f, pitch, 0f, yawOut))
            delay(STICK_MS)
        }
    }

    /** Linear apparent-size proxy of a bbox: sqrt(area / frameArea), in [0,1]. Square-rooting the
     *  area keeps it proportional to distance (a subject twice as far has ~half the linear size),
     *  so the follow controller's error term stays roughly linear in real-world distance. */
    private fun sizeProxy(r: Rect, w: Int, h: Int): Float {
        if (w <= 0 || h <= 0) return 0f
        val areaFrac = (r.width().toFloat() * r.height().toFloat()) / (w.toFloat() * h.toFloat())
        return kotlin.math.sqrt(areaFrac.coerceIn(0f, 1f))
    }

    /** Forward/back stick to hold the subject at its locked apparent size. Positive = forward
     *  (subject shrank/moved away). Zero unless following, and within a deadband so small size
     *  jitter doesn't twitch the sticks. */
    private fun followPitch(): Float {
        if (!following || referenceSize <= 0f || currentSize <= 0f) return 0f
        val relErr = (referenceSize - currentSize) / referenceSize   // + = subject farther now
        if (kotlin.math.abs(relErr) < SIZE_DEADBAND) return 0f
        return (relErr * FOLLOW_KP).coerceIn(-PITCH_CAP, PITCH_CAP)
    }

    private fun rectToFrac(r: Rect, w: Int, h: Int) = RectF(
        r.left.toFloat() / w, r.top.toFloat() / h, r.right.toFloat() / w, r.bottom.toFloat() / h,
    )

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { cont.resume(it) }
            addOnFailureListener { cont.resumeWithException(it) }
        }
}
