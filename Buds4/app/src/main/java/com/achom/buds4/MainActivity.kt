package com.achom.buds4

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color as AColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ───────────────────────── палитра ─────────────────────────

private val GlassColor = Color(0xE6141416)
private val GlassBorder = Color(0x1AFFFFFF)
private val SelectedMode = Color(0xFF3C404B)
private val IdleMode = Color(0xFF1C1C20)
private val RingColor = Color(0xFFC6C5D6)
private val SubText = Color(0xFF9A9AA8)
private val AccentOn = Color(0xFF8A93B8)

// ───────────────────────── activity ─────────────────────────

class MainActivity : ComponentActivity() {

    companion object {
        /** Запуск с этим extra = сразу показать шторку кейса (использует и попап-сценарий). */
        const val EXTRA_POPUP = "popup"
    }

    private val sheetVisible = mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (BudsController.hasPermission(this)) BudsController.connect(this)
        }

    private val aclReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (!BudsController.hasPermission(context)) return
            val device = deviceFrom(intent) ?: return
            if (BudsController.isBuds(device)) {
                BudsController.connect(applicationContext)
                sheetVisible.value = true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // обои пользователя просвечивают сквозь интерфейс
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 30 }
        }
        window.setBackgroundDrawable(ColorDrawable(AColor.TRANSPARENT))
        WindowCompat.setDecorFitsSystemWindows(window, false)

        sheetVisible.value = intent.getBooleanExtra(EXTRA_POPUP, false)

        setContent {
            Buds4App(
                sheetVisible = sheetVisible.value,
                onDismissSheet = { sheetVisible.value = false },
                onReconnect = { ensureConnected() },
                onOpenSettings = {
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                }
            )
        }

        ensureConnected()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_POPUP, false)) sheetVisible.value = true
        ensureConnected()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            aclReceiver,
            IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(aclReceiver) }
        super.onStop()
    }

    private fun ensureConnected() {
        if (BudsController.hasPermission(this)) {
            BudsController.connect(this)
        } else {
            permissionLauncher.launch(BudsController.requiredPermissions())
        }
    }
}

// ───────────────────────── экран ─────────────────────────

@Composable
private fun Buds4App(
    sheetVisible: Boolean,
    onDismissSheet: () -> Unit,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by BudsController.state.collectAsState()

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x22000000), Color(0x66000000), Color(0xCC050507))
                    )
                )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Header(state, onReconnect, onOpenSettings)
                HeroCard(state)
                SoundCard(state)
                GesturesCard()
                CallsCard()
                Spacer(Modifier.height(24.dp))
            }

            CaseSheet(visible = sheetVisible, state = state, onDismiss = onDismissSheet)
        }
    }
}

@Composable
private fun Header(state: BudsState, onReconnect: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "OnePlus Buds 4",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold
            )
            val status = when {
                state.connected -> "Подключено"
                state.connecting -> "Подключение…"
                else -> (state.error ?: "Не подключено") + " · нажми, чтобы подключиться"
            }
            Text(
                status,
                color = if (state.connected) Color(0xFF8FD3A8) else SubText,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable(enabled = !state.connected && !state.connecting, onClick = onReconnect)
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Color.White)
        }
    }
}

// ───────────────────────── карточки ─────────────────────────

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassColor, shape)
            .border(1.dp, GlassBorder, shape)
            .padding(18.dp),
        content = content
    )
}

@Composable
private fun HeroCard(state: BudsState) {
    val known = listOf(state.left, state.right, state.case).filter { it >= 0 }
    val percent = if (known.isEmpty()) -1 else known.average().roundToInt()

    GlassCard {
        Row(
            Modifier
                .fillMaxWidth()
                .height(150.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BudsPairArt(Modifier.weight(1f).fillMaxHeight())
            CaseArt(open = false, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(16.dp))
        ChargeRing(percent)
    }
}

@Composable
private fun ChargeRing(percent: Int) {
    val target = if (percent < 0) 0f else percent / 100f
    val progress by animateFloatAsState(target, tween(600), label = "ring")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                color = RingColor,
                trackColor = Color(0x22FFFFFF),
                strokeWidth = 5.dp
            )
            Text(
                if (percent < 0) "—" else "$percent%",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Заряд комплекса", color = SubText, fontSize = 12.sp)
    }
}

@Composable
private fun SoundCard(state: BudsState) {
    GlassCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Название",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(state.deviceName, color = SubText, fontSize = 15.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Режимы звука",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        ModeSwitcher(selected = state.anc, onSelect = { BudsController.setAnc(it) })
    }
}

private data class ModeItem(val mode: AncMode, val label: String)

private val modeItems = listOf(
    ModeItem(AncMode.ANC, "Шумоподавление (ANC)"),
    ModeItem(AncMode.OFF, "Выкл"),
    ModeItem(AncMode.TRANSPARENCY, "Прозрачность")
)

@Composable
private fun ModeSwitcher(selected: AncMode?, onSelect: (AncMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(20.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        modeItems.forEach { item ->
            val isSelected = item.mode == selected
            val bg by animateColorAsState(
                if (isSelected) SelectedMode else IdleMode,
                tween(250),
                label = "modeBg"
            )
            val fg = if (isSelected) Color.White else Color(0xFFB4B4C2)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(bg)
                    .clickable { onSelect(item.mode) }
                    .padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                ModeIcon(item.mode, fg, Modifier.size(26.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    item.label,
                    color = fg,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun GesturesCard() {
    GlassCard {
        SectionTitle("Жесты нажатия")
        SettingRow(
            title = "Левый",
            subtitle = "Режим звука",
            prefKey = "gesture_left",
            default = true
        ) { ModeIcon(AncMode.ANC, Color.White, Modifier.size(22.dp)) }
        SettingRow(
            title = "Правый",
            subtitle = "Голосовой ассистент",
            prefKey = "gesture_right",
            default = true
        ) { MicIcon(Color.White, muted = false, modifier = Modifier.size(22.dp)) }
    }
}

@Composable
private fun CallsCard() {
    GlassCard {
        SectionTitle("Управление звонками")
        SettingRow(title = "Ответить", prefKey = "call_answer", default = true) {
            Icon(Icons.Default.Call, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        SettingRow(title = "Ответить на звонок", prefKey = "call_answer_incoming", default = false) {
            Icon(Icons.Default.Phone, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        SettingRow(title = "Выключить микрофон / Отключение микрофона", prefKey = "call_mute", default = false) {
            MicIcon(Color.White, muted = true, modifier = Modifier.size(22.dp))
        }
        SettingRow(title = "Повесить трубку / Завершение вызова", prefKey = "call_hangup", default = false) {
            Icon(
                Icons.Default.Call,
                null,
                tint = Color(0xFFFF6B6B),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(135f)
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

/** Строка с иконкой и переключателем. Значение хранится локально в SharedPreferences. */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    prefKey: String,
    default: Boolean,
    icon: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("buds4_ui", Context.MODE_PRIVATE) }
    var checked by remember { mutableStateOf(prefs.getBoolean(prefKey, default)) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0x1FFFFFFF)),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = SubText, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                prefs.edit().putBoolean(prefKey, it).apply()
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentOn,
                uncheckedThumbColor = Color(0xFFB4B4C2),
                uncheckedTrackColor = Color(0xFF2A2A30),
                uncheckedBorderColor = Color(0x33FFFFFF)
            )
        )
    }
}

// ───────────────────────── шторка кейса ─────────────────────────

@Composable
private fun CaseSheet(visible: Boolean, state: BudsState, onDismiss: () -> Unit) {
    LaunchedEffect(visible) {
        if (visible) {
            delay(10_000)
            onDismiss()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(250))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDismiss() }
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(450, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(300)),
            exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(200))
        ) {
            var drag by remember { mutableFloatStateOf(0f) }
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(GlassColor, shape)
                    .border(1.dp, GlassBorder, shape)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (drag > 150f) onDismiss()
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f }
                        ) { _, dy -> drag += dy }
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color(0x40FFFFFF))
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BudsPairArt(Modifier.weight(1f).fillMaxHeight())
                    CaseArt(open = true, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BatteryChip("Л", state.left, Modifier.weight(1f))
                    BatteryChip("П", state.right, Modifier.weight(1f))
                    BatteryChip("Кейс", state.case, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun BatteryChip(label: String, value: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color(0x22FFFFFF))
            .border(1.dp, GlassBorder, CircleShape)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = SubText, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            if (value < 0) "—" else "$value%",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ───────────────────────── картинки / 3D-заглушки ─────────────────────────

/**
 * Если в res/drawable лежат buds_left_right / buds_case / buds_case_open (png, webp или xml) —
 * берутся они. Иначе рисуется Canvas-заглушка, сборка не ломается.
 */
@SuppressLint("DiscouragedApi")
@Composable
private fun drawableIdOf(name: String): Int {
    val ctx = LocalContext.current
    return remember(name) { ctx.resources.getIdentifier(name, "drawable", ctx.packageName) }
}

@Composable
private fun BudsPairArt(modifier: Modifier = Modifier) {
    val id = drawableIdOf("buds_left_right")
    if (id != 0) {
        Image(painterResource(id), contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        Canvas(modifier) {
            val s = size.minDimension * 0.34f
            drawBud(size.width * 0.30f, size.height * 0.36f, s, mirror = false)
            drawBud(size.width * 0.70f, size.height * 0.36f, s, mirror = true)
        }
    }
}

@Composable
private fun CaseArt(open: Boolean, modifier: Modifier = Modifier) {
    val id = drawableIdOf(if (open) "buds_case_open" else "buds_case")
    if (id != 0) {
        Image(painterResource(id), contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        Canvas(modifier) { drawCase(open) }
    }
}

private fun DrawScope.drawBud(cx: Float, cy: Float, s: Float, mirror: Boolean) {
    val dir = if (mirror) -1f else 1f

    // ножка
    val stemW = s * 0.28f
    val stemH = s * 0.95f
    rotate(degrees = 12f * dir, pivot = Offset(cx, cy)) {
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF3A3B40), Color(0xFF111114)),
                startY = cy,
                endY = cy + stemH
            ),
            topLeft = Offset(cx - stemW / 2f, cy),
            size = Size(stemW, stemH),
            cornerRadius = CornerRadius(stemW / 2f)
        )
    }

    // головка
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF5A5C64), Color(0xFF1B1C20), Color(0xFF0B0B0D)),
            center = Offset(cx - s * 0.15f * dir, cy - s * 0.2f),
            radius = s * 0.9f
        ),
        radius = s * 0.55f,
        center = Offset(cx, cy)
    )

    // глянцевый блик
    drawArc(
        color = Color(0x55FFFFFF),
        startAngle = if (mirror) 280f else 200f,
        sweepAngle = 60f,
        useCenter = false,
        topLeft = Offset(cx - s * 0.42f, cy - s * 0.42f),
        size = Size(s * 0.84f, s * 0.84f),
        style = Stroke(width = s * 0.05f, cap = StrokeCap.Round)
    )

    // микрофонное отверстие
    drawCircle(Color(0xFF2B2C31), radius = s * 0.06f, center = Offset(cx + s * 0.15f * dir, cy + s * 0.1f))
}

private fun DrawScope.drawCase(open: Boolean) {
    val w = size.width
    val h = size.height
    val bw = minOf(w * 0.8f, h * 1.15f)
    val bh = bw * 0.62f
    val left = (w - bw) / 2f
    val top = (h - bh) / 2f + if (open) bh * 0.14f else 0f

    // тень
    drawOval(
        color = Color(0x55000000),
        topLeft = Offset(left + bw * 0.05f, top + bh * 0.9f),
        size = Size(bw * 0.9f, bh * 0.18f)
    )

    // корпус
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF2E2F34), Color(0xFF0E0E10)),
            startY = top,
            endY = top + bh
        ),
        topLeft = Offset(left, top),
        size = Size(bw, bh),
        cornerRadius = CornerRadius(bh * 0.5f)
    )

    if (open) {
        // крышка откинута назад
        rotate(degrees = -24f, pivot = Offset(left + bw / 2f, top + bh * 0.25f)) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF3B3C42), Color(0xFF17171A)),
                    startY = top - bh * 0.55f,
                    endY = top + bh * 0.05f
                ),
                topLeft = Offset(left + bw * 0.04f, top - bh * 0.55f),
                size = Size(bw * 0.92f, bh * 0.6f),
                cornerRadius = CornerRadius(bh * 0.3f)
            )
        }
        // гнёзда под наушники
        drawOval(
            color = Color(0xFF050506),
            topLeft = Offset(left + bw * 0.18f, top + bh * 0.16f),
            size = Size(bw * 0.26f, bh * 0.52f)
        )
        drawOval(
            color = Color(0xFF050506),
            topLeft = Offset(left + bw * 0.56f, top + bh * 0.16f),
            size = Size(bw * 0.26f, bh * 0.52f)
        )
        // индикатор
        drawCircle(Color(0xFF9AA0B5), radius = bh * 0.03f, center = Offset(left + bw / 2f, top + bh * 0.82f))
    } else {
        // шов крышки
        drawLine(
            color = Color(0x33FFFFFF),
            start = Offset(left + bh * 0.3f, top + bh * 0.42f),
            end = Offset(left + bw - bh * 0.3f, top + bh * 0.42f),
            strokeWidth = 2f
        )
        // блик
        drawArc(
            color = Color(0x44FFFFFF),
            startAngle = 200f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(left + bh * 0.08f, top + bh * 0.06f),
            size = Size(bh * 0.84f, bh * 0.84f),
            style = Stroke(width = bh * 0.035f, cap = StrokeCap.Round)
        )
        // индикатор
        drawCircle(Color(0xFF9AA0B5), radius = bh * 0.03f, center = Offset(left + bw / 2f, top + bh * 0.72f))
    }
}

// ───────────────────────── иконки (без material-icons-extended) ─────────────────────────

@Composable
private fun ModeIcon(mode: AncMode, tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        val sw = r * 0.16f
        when (mode) {
            AncMode.ANC -> {
                drawCircle(tint, r * 0.26f, c)
                drawCircle(tint.copy(alpha = 0.75f), r * 0.58f, c, style = Stroke(sw))
                drawCircle(tint.copy(alpha = 0.4f), r * 0.9f, c, style = Stroke(sw))
            }
            AncMode.OFF -> {
                drawCircle(tint, r * 0.82f, c, style = Stroke(sw))
            }
            AncMode.TRANSPARENCY -> {
                drawCircle(
                    tint,
                    r * 0.82f,
                    c,
                    style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw * 1.4f, sw * 1.8f)))
                )
                drawCircle(tint, r * 0.26f, c)
            }
        }
    }
}

@Composable
private fun MicIcon(tint: Color, muted: Boolean, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        drawRoundRect(tint, Offset(w * 0.36f, h * 0.06f), Size(w * 0.28f, h * 0.5f), CornerRadius(w * 0.14f))
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.22f, h * 0.22f),
            size = Size(w * 0.56f, h * 0.5f),
            style = Stroke(sw, cap = StrokeCap.Round)
        )
        drawLine(tint, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.92f), sw, StrokeCap.Round)
        if (muted) {
            drawLine(
                Color(0xFFFF6B6B),
                Offset(w * 0.12f, h * 0.1f),
                Offset(w * 0.88f, h * 0.9f),
                sw * 1.1f,
                StrokeCap.Round
            )
        }
    }
}
