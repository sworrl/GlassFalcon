// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.core.FlyC
import dev.glassfalcon.core.send
import dev.glassfalcon.ui.GlassFalconRoot

class MainActivity : ComponentActivity() {
    private val vm: FlightViewModel by viewModels()
    private val ACTION_USB_PERMISSION = "dev.glassfalcon.USB_PERMISSION"
    private val ACTION_AOA_PERMISSION = "dev.glassfalcon.AOA_PERMISSION"

    // ── Landscape-only auto-rotate with a debounce delay ──────────────────────
    // The manifest declares a single fixed "landscape" as the initial orientation, with the
    // RC/phone mount, whichever physical side ends up "up" is arbitrary, so the app needs to
    // detect that itself rather than assume one fixed rotation (the earlier bug: it was always
    // rendering as if mounted one specific way, upside-down if actually mounted the other way).
    // A plain `sensorLandscape` manifest orientation would auto-flip instantly on every sensor
    // reading, which is distracting on a control mounted to hardware that isn't perfectly
    // still, so rotation is driven here instead, only committing to a new landscape side
    // after it's been steady for [rotationDelayMs].
    private val rotationHandler = Handler(Looper.getMainLooper())
    private var pendingRotation: Int? = null
    private val rotationDelayMs = 700L
    // Last landscape side actually committed, used to snap straight back to it when Settings
    // closes, instead of waiting out another rotationDelayMs of "steady reading" before the
    // listener (re-enabled below) settles on one itself.
    private var lastLandscapeOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    // Settings is the one screen allowed out of the landscape lock (see setSettingsOpen), it's
    // plain scrollable Column/Card content, not a hand-tuned instrument layout the way the
    // flight HUD is, so it reflows fine in portrait. The flight HUD itself stays landscape-only.
    private var settingsOpen = false
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                if (settingsOpen) return   // Settings is the one screen allowed to be portrait
                // Classify into one of the two landscape zones; ignore near-portrait readings
                // (0°/180°-ish) since those only occur transiently while handling the phone, 
                // this app is landscape-only, there's no real target orientation there.
                // NOTE: if this ends up choosing the mirrored landscape on real hardware, swap
                // which constant each zone maps to below, the raw sensor angle's clockwise/
                // counter-clockwise convention wasn't verified against a physical rotation.
                val target = when {
                    orientation in 45..135  -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    orientation in 225..315 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> null
                } ?: return

                if (target == requestedOrientation) {
                    pendingRotation = null
                    rotationHandler.removeCallbacksAndMessages(null)
                    return
                }
                if (target == pendingRotation) return  // already debouncing toward this target
                pendingRotation = target
                rotationHandler.removeCallbacksAndMessages(null)
                rotationHandler.postDelayed({
                    if (pendingRotation == target) {
                        requestedOrientation = target
                        lastLandscapeOrientation = target
                        pendingRotation = null
                    }
                }, rotationDelayMs)
            }
        }
    }

    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val locListener = LocationListener { loc: Location ->
        vm.updatePhoneLocation(loc.latitude, loc.longitude, loc.accuracy)
    }
    private val locPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPhoneLocation()
        }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val mgr = getSystemService(USB_SERVICE) as UsbManager
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        vm.connectUsb(device, mgr)
                    } else {
                        vm.log("USB permission denied for ${device.productName}")
                    }
                }
                ACTION_AOA_PERMISSION -> {
                    val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY) ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        vm.connectAccessory(acc, mgr)
                    } else {
                        vm.log("AOA permission denied for ${acc.model}")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (isDjiDevice(device)) requestUsbPermission(device)
                }
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    val acc = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY) ?: return
                    requestAoaPermission(acc)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    vm.disconnect()
                    vm.log("USB disconnected")
                }
            }
        }
    }

    // ── Bench-test command trigger (DEBUG builds only) ────────────────────────
    // Fires the UNVERIFIED wm240 flight opcodes for a props-off bench test so the raw
    // DUML can be captured and diffed, the guarded on-screen slider sits in the
    // gesture-nav zone and can't be driven reliably by `adb input swipe`. Delivered as
    // an extra on the ALREADY-exported launcher activity (no new exported component /
    // RCE surface) and gated to debug builds:
    //   adb shell am start -n dev.glassfalcon.debug/dev.glassfalcon.MainActivity --es bench takeoff
    // cmd ∈ takeoff|land|rth|arm|disarm. Ignored in release builds.
    private fun handleBench(intent: Intent?) {
        if (!dev.glassfalcon.BuildConfig.DEBUG) return
        when (intent?.getStringExtra("bench")) {
            "takeoff" -> vm.autoTakeoff()
            "land"    -> vm.autoLand()
            "rth"     -> vm.sendRth()
            "arm"     -> vm.motorArm()
            "disarm"  -> vm.motorDisarm()
            // Harmless info queries, the FC/camera answer these WITHOUT flight
            // authorization, so an ack proves our uplink framing/CRC is valid and
            // isolates "no motor response" to authorization vs malformed frames.
            "ping"    -> { vm.duml.send(0x03, 0x00, 0x00, byteArrayOf());          vm.log("BENCH ping →FC") }
            "version" -> { vm.duml.send(0x03, 0x00, 0x01, byteArrayOf(0x00));      vm.log("BENCH version →FC") }
            "status"  -> { vm.duml.send(0x03, 0x03, 0x01, byteArrayOf());          vm.log("BENCH flyc_status →FC") }
            "fcinfo"  -> { vm.duml.send(0x03, 0x03, 0x37, byteArrayOf());          vm.log("BENCH fcinfo →FC") }
            "activate" -> { vm.duml.send(FlyC.activationInfo());                  vm.log("BENCH activationInfo →FC") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Per-device auto-launch suppression. The manifest's USB_ACCESSORY_ATTACHED filter (and its
        // restored real accessory filter) makes EVERY install auto-launch on controller attach and
        // offer the system's persistent "always open" grant, which is what we want on the flight
        // phone. On the DEV phone that same auto-launch steals the AOA link from DJI GO 4 mid
        // kprobe-capture, so there we set this flag (glassfalcon_debug/suppress_auto_launch=true via
        // adb run-as). When the launch was TRIGGERED by the accessory attach (cold start with that
        // intent action) and the flag is set, bail out before touching USB so the link stays with
        // whatever app the dev is actually capturing. Manual launches (icon tap → ACTION_MAIN) and
        // all other installs are unaffected.
        val launchedByAccessory = intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED
        val suppressAutoLaunch = getSharedPreferences("glassfalcon_debug", MODE_PRIVATE)
            .getBoolean("suppress_auto_launch", false)
        if (launchedByAccessory && suppressAutoLaunch) {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // setDecorFitsSystemWindows(false) alone only grants edge-to-edge for the system bars, 
        // the display cutout is separate, and its default mode (LAYOUT_IN_DISPLAY_CUTOUT_MODE_
        // DEFAULT) only allows drawing into it while the cutout sits inside a system bar. This
        // app is locked to landscape (see manifest), which rotates a phone's usual top-center
        // punch-hole onto a SIDE edge instead, nowhere near a system bar, so without this,
        // the window itself gets letterboxed away from that whole edge before Compose ever
        // renders a pixel, no matter what padding any composable does or doesn't have.
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        hideSystemBars()

        // Register USB broadcast receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(ACTION_AOA_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)

        // Handle USB device that triggered the launch (user plugged in while app was closed)
        intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let { device ->
            if (isDjiDevice(device)) {
                val mgr = getSystemService(USB_SERVICE) as UsbManager
                if (mgr.hasPermission(device)) {
                    vm.connectUsb(device, mgr)
                } else {
                    requestUsbPermission(device)
                }
            }
        }

        // Also auto-connect to any already-attached device or accessory
        probeAttachedUsb()
        probeAttachedAccessory()

        handleBench(intent)
        setContent { GlassFalconRoot(vm, onSettingsVisibilityChanged = ::setSettingsOpen) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Immersive full-screen: hide status + nav bars; swipe brings them back transiently. */
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // A re-plugged AOA accessory is delivered to the singleTask activity as a NEW
    // intent (not always a runtime broadcast), so handle it here to reconnect.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED ->
                intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)?.let { requestAoaPermission(it) }
            UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let {
                    if (isDjiDevice(it)) requestUsbPermission(it)
                }
        }
        handleBench(intent)
    }

    override fun onResume() {
        super.onResume()
        // Catch-all reconnect: if a cable was re-plugged while we were running,
        // re-probe for any attached drone/RC and reconnect when not already linked. Preview
        // mode's `connected = true` (see FlightViewModel.enterPreviewMode) is a fake,
        // hardware-free session, not a real link, it must not block this probe, or plugging
        // the real controller in while sitting in preview mode would never be noticed until a
        // full app restart. A real link (any host other than "preview") still skips the probe
        // as before.
        if (!vm.app.value.connected || vm.app.value.host == "preview") {
            probeAttachedUsb()
            probeAttachedAccessory()
        }
        ensurePhoneLocation()
        // Don't re-enable the landscape-lock listener if Settings is open, it'd fight the
        // portrait unlock the moment the app resumes (e.g. after a permission dialog).
        if (!settingsOpen && orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        super.onPause()
        try { locationManager.removeUpdates(locListener) } catch (_: Exception) {}
        orientationListener.disable()
        rotationHandler.removeCallbacksAndMessages(null)
        pendingRotation = null
    }

    /** Settings is the one screen allowed out of the landscape lock, plain scrollable Column/
     *  Card content that reflows fine in portrait, unlike the flight HUD's hand-tuned instrument
     *  layout. Called from Compose (see GlassFalconRoot's onSettingsVisibilityChanged) whenever
     *  the Settings overlay opens/closes. */
    fun setSettingsOpen(open: Boolean) {
        settingsOpen = open
        if (open) {
            orientationListener.disable()
            rotationHandler.removeCallbacksAndMessages(null)
            pendingRotation = null
            // NOT a direct jump to SCREEN_ORIENTATION_UNSPECIFIED, reported as "it did rotate
            // eventually," i.e. laggy. UNSPECIFIED hands rotation entirely to Android's own
            // auto-rotate, which has its own settle/hysteresis delay before committing to a
            // reading, the exact reason this app already built a custom listener for the main
            // flight view instead of trusting stock auto-rotate there too. Take one immediate
            // sensor reading ourselves first so Settings opens already matching however the
            // phone is ACTUALLY being held, then hand off to FULL_SENSOR for any further turns
            // while it stays open.
            snapToCurrentPhysicalOrientation()
        } else {
            // Snap straight back to whichever landscape side was last committed, rather than
            // sitting in portrait for another rotationDelayMs while the listener (re-enabled
            // below) waits for a fresh steady reading before it would otherwise correct this.
            requestedOrientation = lastLandscapeOrientation
            if (orientationListener.canDetectOrientation()) orientationListener.enable()
        }
    }

    /** One-shot: reads the CURRENT device tilt via a throwaway [OrientationEventListener] (the
     *  same raw signal Android's own auto-rotate uses), sets [requestedOrientation] to the
     *  matching specific orientation immediately, then switches to
     *  [ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR] so any further physical rotation while
     *  Settings stays open is still tracked live by the OS afterward. */
    private fun snapToCurrentPhysicalOrientation() {
        lateinit var oneShot: OrientationEventListener
        oneShot = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                requestedOrientation = when {
                    orientation in 45..135   -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    orientation in 135..225  -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    orientation in 225..315  -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else                     -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                oneShot.disable()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            }
        }
        if (oneShot.canDetectOrientation()) oneShot.enable()
        else requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    private fun ensurePhoneLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            startPhoneLocation()
        else locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneLocation() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener)
            if (LocationManager.NETWORK_PROVIDER in locationManager.allProviders)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, locListener)
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                vm.updatePhoneLocation(it.latitude, it.longitude, it.accuracy)
            }
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        vm.stopVirtualRc()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    private fun isDjiDevice(device: UsbDevice) = device.vendorId == 0x2ca3

    private fun requestUsbPermission(device: UsbDevice) {
        val mgr = getSystemService(USB_SERVICE) as UsbManager
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        mgr.requestPermission(device, pi)
        vm.log("Requesting USB permission for ${device.productName ?: "device"}…")
    }

    private fun requestAoaPermission(acc: UsbAccessory) {
        val mgr = getSystemService(USB_SERVICE) as UsbManager
        if (mgr.hasPermission(acc)) {
            vm.connectAccessory(acc, mgr)
            return
        }
        val pi = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_AOA_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        mgr.requestPermission(acc, pi)
        vm.log("Requesting AOA permission for ${acc.manufacturer} ${acc.model}…")
    }

    private fun probeAttachedUsb() {
        val mgr = getSystemService(USB_SERVICE) as UsbManager
        mgr.deviceList.values.firstOrNull { isDjiDevice(it) }?.let { device ->
            if (mgr.hasPermission(device)) vm.connectUsb(device, mgr)
            else requestUsbPermission(device)
        }
    }

    private fun probeAttachedAccessory() {
        val mgr = getSystemService(USB_SERVICE) as UsbManager
        mgr.accessoryList?.firstOrNull()?.let { acc -> requestAoaPermission(acc) }
    }
}
