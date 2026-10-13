package com.spikest3r.cameracontrol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.spikest3r.cameracontrol.ui.theme.CameraControlTheme
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class CameraParam(
    val valueStr: String = "---",
    val index: Int = 0,
    val maxIndex: Int = 0,
    val allowed: List<Long> = emptyList()
)

enum class AppScreen {
    CONTROL, GALLERY, FEATURES
}

class MainActivity : ComponentActivity() {

    private var camState = mutableStateOf<PtpCamera?>(null)

    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val permAction by lazy { "$packageName.USB_PERM" }
    private var permissionPending = false
    @Volatile private var opening = false

    private fun isNikon(d: UsbDevice?) = d?.vendorId == 0x04B0

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                permAction -> {
                    permissionPending = false
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (isNikon(dev)) disconnect()
                }
            }
        }
    }

    /** Finds a plugged-in camera, asks for permission if needed, and opens a PTP session. No-op if already connected/connecting. */
    private fun connect() {
        if (camState.value != null || opening) return
        val dev = usb.deviceList.values.firstOrNull { isNikon(it) } ?: return
        if (!usb.hasPermission(dev)) {
            if (!permissionPending) {
                permissionPending = true
                usb.requestPermission(dev, PendingIntent.getBroadcast(
                    this, 0, Intent(permAction).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            return
        }
        opening = true
        thread {
            try {
                // The camera can enumerate before it is ready for PTP, so retry a few times.
                repeat(5) { attempt ->
                    if (usb.deviceList.values.none { it.deviceId == dev.deviceId }) return@thread   // unplugged meanwhile
                    try {
                        val cam = PtpCamera.open(usb, dev)
                        runOnUiThread { camState.value = cam }
                        return@thread
                    } catch (e: Exception) {
                        e.printStackTrace()
                        if (attempt < 4) Thread.sleep(1000)
                    }
                }
            } finally {
                opening = false
            }
        }
    }

    private fun disconnect() {
        val cam = camState.value ?: return
        camState.value = null   // stops the polling loop and disables the UI
        thread { runCatching { cam.close() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter().apply {
            addAction(permAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        enableEdgeToEdge()
        setContent {
            CameraControlTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.CONTROL) }

                    // Camera unplugged while on a camera-only screen: go back to the main screen
                    LaunchedEffect(camState.value) {
                        if (camState.value == null) currentScreen = AppScreen.CONTROL
                    }

                    BackHandler(enabled = currentScreen != AppScreen.CONTROL) {
                        currentScreen = AppScreen.CONTROL
                    }

                    when (currentScreen) {
                        AppScreen.CONTROL -> {
                            CameraControlScreen(
                                cam = camState.value,
                                modifier = Modifier.padding(innerPadding),
                                onOpenGallery = { currentScreen = AppScreen.GALLERY },
                                onOpenFeatures = { currentScreen = AppScreen.FEATURES }
                            )
                        }
                        AppScreen.GALLERY -> {
                            camState.value?.let { activeCam ->
                                GalleryScreen(
                                    cam = activeCam,
                                    modifier = Modifier.padding(innerPadding),
                                    onClose = { currentScreen = AppScreen.CONTROL }
                                )
                            }
                        }
                        AppScreen.FEATURES -> {
                            camState.value?.let { activeCam ->
                                FeaturesScreen(
                                    cam = activeCam,
                                    modifier = Modifier.padding(innerPadding),
                                    onClose = { currentScreen = AppScreen.CONTROL }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        connect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        disconnect()
    }
}

@Composable
fun CameraControlScreen(
    cam: PtpCamera?,
    modifier: Modifier = Modifier,
    onOpenGallery: () -> Unit = {},
    onOpenFeatures: () -> Unit = {}
) {
    var battery by remember { mutableStateOf("---") }

    var apertureState by remember { mutableStateOf(CameraParam()) }
    var shutterState by remember { mutableStateOf(CameraParam()) }
    var isoState by remember { mutableStateOf(CameraParam()) }
    var evState by remember { mutableStateOf(CameraParam()) }

    LaunchedEffect(cam) {
        if (cam == null) {
            battery = "---"
            apertureState = CameraParam()
            shutterState = CameraParam()
            isoState = CameraParam()
            evState = CameraParam()
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            while (isActive) {
                try {
                    val battProp = cam.getProp(0x5001)
                    battery = battProp?.toString() ?: "---"

                    fun fetchParam(propId: Int, formatter: (Long) -> String = { it.toString() }): CameraParam {
                        val desc = cam.getDesc(propId) ?: return CameraParam()
                        val curRawStr = desc.cur?.toString() ?: ""
                        val curRaw = curRawStr.toLongOrNull() ?: 0L

                        val rawAllowed = desc.allowed
                        val allowedList = mutableListOf<Long>()

                        when (rawAllowed) {
                            is LongArray -> allowedList.addAll(rawAllowed.toList())
                            is IntArray -> rawAllowed.forEach { allowedList.add(it.toLong()) }
                            is ShortArray -> rawAllowed.forEach { allowedList.add(it.toLong()) }
                            is Array<*> -> rawAllowed.forEach { item ->
                                item?.toString()?.toLongOrNull()?.let { allowedList.add(it) }
                            }
                            is Iterable<*> -> rawAllowed.forEach { item ->
                                item?.toString()?.toLongOrNull()?.let { allowedList.add(it) }
                            }
                        }

                        val idx = allowedList.indexOf(curRaw)
                        val displayStr = if (curRawStr.isEmpty()) "---" else formatter(curRaw)

                        return CameraParam(
                            valueStr = displayStr,
                            index = maxOf(0, idx),
                            maxIndex = maxOf(0, allowedList.size - 1),
                            allowed = allowedList
                        )
                    }

                    // Nikon returns f-stops and EV in millistops (value * 1000)
                    apertureState = fetchParam(0x5007) { "%.1f".format(it / 100f) }
                    evState = fetchParam(0x5010) { "%.1f".format(it / 1000f) }

                    isoState = fetchParam(0x500F)

                    // Shutter speed in PTP is represented in 0.1 millisecond units (1/10,000 sec).
                    shutterState = fetchParam(0x500D) { rawSS ->
                        if (rawSS == 0xFFFFFFFFL) {
                            "BULB"
                        } else if (rawSS >= 10000) {
                            val seconds = rawSS / 10000f
                            if (seconds % 1f == 0f) "${seconds.toInt()}\"" else "$seconds\""
                        } else if (rawSS > 0) {
                            var denom = (10000.0 / rawSS).roundToInt()

                            // snap rounding artifacts to standard stops
                            denom = when (denom) {
                                161 -> 160
                                322, 323 -> 320
                                666, 667 -> 640
                                833 -> 800
                                1666, 1667 -> 1600
                                3333 -> 3200
                                else -> denom
                            }
                            "1/$denom"
                        } else {
                            rawSS.toString()
                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(100)
            }
        }
    }

    fun sendProp(propId: Int, state: CameraParam, targetIndex: Int) {
        if (cam == null || state.allowed.isEmpty() || targetIndex !in state.allowed.indices) return
        thread {
            try { cam.setProp(propId, state.allowed[targetIndex]) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val lcd: @Composable () -> Unit = {
        LcdPanel(battery, apertureState, shutterState, isoState, evState)
    }
    val sliders: @Composable (Modifier) -> Unit = { m ->
        Column(
            modifier = m.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            BlindSlider("APERTURE", apertureState) { sendProp(0x5007, apertureState, it) }
            BlindSlider("SHUTTER SPEED", shutterState) { sendProp(0x500D, shutterState, it) }
            BlindSlider("ISO", isoState) { sendProp(0x500F, isoState, it) }
            BlindSlider("EXP COMP", evState) { sendProp(0x5010, evState, it) }
        }
    }
    // Landscape: one compact column, evenly spread over the full height (no scrolling)
    val slidersGrid: @Composable (Modifier) -> Unit = { m ->
        Column(
            modifier = m,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            BlindSlider("APERTURE", apertureState, compact = true) { sendProp(0x5007, apertureState, it) }
            BlindSlider("SHUTTER SPEED", shutterState, compact = true) { sendProp(0x500D, shutterState, it) }
            BlindSlider("ISO", isoState, compact = true) { sendProp(0x500F, isoState, it) }
            BlindSlider("EXP COMP", evState, compact = true) { sendProp(0x5010, evState, it) }
        }
    }
    val actions: @Composable (Modifier) -> Unit = { m ->
        ActionBar(cam, onOpenGallery, onOpenFeatures, m)
    }

    if (landscape) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF101010))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                lcd()
                actions(Modifier)
            }
            slidersGrid(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF101010))
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            lcd()
            sliders(Modifier.weight(1f).padding(vertical = 24.dp))
            actions(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun darkButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0xFF222222),
    contentColor = Color.White,
    disabledContainerColor = Color(0xFF161616),
    disabledContentColor = Color(0xFF555555)
)

@Composable
private fun LcdPanel(
    battery: String,
    aperture: CameraParam,
    shutter: CameraParam,
    iso: CameraParam,
    ev: CameraParam
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF8A9A86), shape = RoundedCornerShape(4.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LcdBatteryBar(levelStr = battery)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val fStop = if (aperture.valueStr == "---") "---" else "f/${aperture.valueStr}"
            LcdReadout(label = "F", value = fStop)
            LcdReadout(label = "SS", value = shutter.valueStr)
            LcdReadout(label = "ISO", value = iso.valueStr)
            LcdReadout(label = "EV", value = ev.valueStr)
        }
    }
}

@Composable
private fun ActionBar(
    cam: PtpCamera?,
    onOpenGallery: () -> Unit,
    onOpenFeatures: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onOpenGallery,
            colors = darkButtonColors(),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(60.dp).weight(1f).padding(end = 8.dp)
        ) {
            Text(text = "Gallery", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }

        Button(
            onClick = onOpenFeatures,
            colors = darkButtonColors(),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(60.dp).weight(1f).padding(horizontal = 4.dp)
        ) {
            Text(text = "Helpers", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = {
                if (cam != null) {
                    thread { try { cam.shoot() } catch (e: Exception) { e.printStackTrace() } }
                }
            },
            modifier = Modifier.size(76.dp),
            shape = CircleShape,
            colors = darkButtonColors(),
            contentPadding = PaddingValues(0.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
            enabled = cam != null
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(if (cam != null) Color(0xFFE53935) else Color(0xFF111111), CircleShape)
            )
        }
    }
}

@Composable
fun LcdBatteryBar(levelStr: String) {
    val level = levelStr.toIntOrNull() ?: -1
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (level >= 0) {
            Box(
                modifier = Modifier
                    .size(28.dp, 12.dp)
                    .border(1.dp, Color.Black, RoundedCornerShape(2.dp))
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(level / 100f)
                        .background(Color.Black)
                )
            }
            Box(
                modifier = Modifier
                    .size(2.dp, 6.dp)
                    .background(Color.Black, RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp))
            )
        } else {
            Text(
                text = "BATT ---",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Color.Black
            )
        }
    }
}

@Composable
fun LcdReadout(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFF333333)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = Color.Black,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
fun BlindSlider(
    label: String,
    state: CameraParam,
    compact: Boolean = false,
    onIndexChanged: (Int) -> Unit
) {
    val buttonSize = if (compact) 36.dp else 44.dp
    val enabled = state.maxIndex > 0 && state.allowed.isNotEmpty()
    var localValue by remember(state.index) { mutableFloatStateOf(state.index.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color(0xFF777777),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = if (compact) 2.dp else 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(if (compact) 1f else 0.85f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { onIndexChanged(state.index - 1) },
                enabled = enabled && state.index > 0,
                colors = darkButtonColors(),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(buttonSize)
            ) {
                Text("-", fontSize = 24.sp, modifier = Modifier.padding(bottom = 2.dp))
            }

            Slider(
                value = if (isDragging) localValue else state.index.toFloat(),
                onValueChange = {
                    isDragging = true
                    localValue = it
                },
                onValueChangeFinished = {
                    isDragging = false
                    onIndexChanged(localValue.roundToInt())
                },
                valueRange = 0f..maxOf(1f, state.maxIndex.toFloat()),
                steps = maxOf(0, state.maxIndex - 1),
                enabled = enabled,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF666666),
                    inactiveTrackColor = Color(0xFF222222),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                    disabledThumbColor = Color(0xFF333333),
                    disabledActiveTrackColor = Color(0xFF1A1A1A),
                    disabledInactiveTrackColor = Color(0xFF111111)
                )
            )

            Button(
                onClick = { onIndexChanged(state.index + 1) },
                enabled = enabled && state.index < state.maxIndex,
                colors = darkButtonColors(),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(buttonSize)
            ) {
                Text("+", fontSize = 20.sp)
            }
        }
    }
}