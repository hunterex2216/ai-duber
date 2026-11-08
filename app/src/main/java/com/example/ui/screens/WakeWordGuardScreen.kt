package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.model.WakeWordPhase
import com.example.service.CubeSentinelBus
import com.example.service.CubeSentinelState
import com.example.service.DeviceHardwareSnapshot
import com.example.service.VoiceBridgeState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.PulseRose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GREETING_PRESETS = listOf(
    "জি স্যার, বলুন কি আদেশ? আপনি কি জানতে চান বা কি কাজ করতে হবে?",
    "জি স্যার, কিউব হাজির! বলুন আপনার জন্য কি করতে পারি?",
    "হ্যালো স্যার! কিউব এআই প্রস্তুত, আপনার নির্দেশ বলুন।",
    "Yes Sir! Cube AI is online. What is your command?"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WakeWordGuardScreen(
    sentinelState: CubeSentinelState,
    voiceState: VoiceBridgeState,
    hardwareState: DeviceHardwareSnapshot,
    onToggleSentinel: (Boolean) -> Unit,
    onSimulateHeyCube: () -> Unit,
    onEnterLockScreenStandby: (Boolean) -> Unit,
    onNavigateToAgentHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        if (sentinelState.isLockScreenHudMode) {
            onEnterLockScreenStandby(false)
        } else {
            onNavigateToAgentHub()
        }
    }

    val context = LocalContext.current
    var customWakeInput by rememberSaveable { mutableStateOf("") }
    var greetingInput by rememberSaveable { mutableStateOf(sentinelState.bossGreeting) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            onToggleSentinel(true)
        }
    }

    val requestAndEnableSentinel: (Boolean) -> Unit = { enable ->
        if (!enable) {
            onToggleSentinel(false)
        } else {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                onToggleSentinel(true)
            } else {
                permissionsLauncher.launch(perms.toTypedArray())
            }
        }
    }

    // Full-screen AMOLED Lock-Screen / Screen-Off Standby HUD
    if (sentinelState.isLockScreenHudMode) {
        AmoledCubeLockScreenStandbyHud(
            sentinelState = sentinelState,
            voiceState = voiceState,
            hardwareState = hardwareState,
            onTriggerHeyCube = {
                onSimulateHeyCube()
            },
            onExitLockStandby = {
                onEnterLockScreenStandby(false)
            }
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("wake_word_guard_list"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        item {
            Text(
                text = "Hey Cube ভয়েস ওয়েক-ওয়ার্ড ও লক-স্ক্রিন গার্ড",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Text(
                text = "\"Hey Cube\" বা \"Cube\" বলে ডাকলেই মাইক অ্যাক্টিভ হবে এবং বলবে: \"জি বস, কি আদেশ?\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Animated 3D-Style Cube Core & Master Switch Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        brush = Brush.horizontalGradient(
                            if (sentinelState.isSentinelActive) {
                                listOf(EmeraldSuccess, ElectricCyan, NeuralViolet)
                            } else {
                                listOf(ElectricCyan, NeuralViolet)
                            }
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .testTag("hey_cube_master_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0E172C))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = if (sentinelState.isSentinelActive) EmeraldSuccess else ElectricCyan
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "অলওয়েজ-অন \"Hey Cube\" গার্ড",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = if (sentinelState.isSentinelActive) {
                                        "সক্রিয় আছে • ব্যাকগ্রাউন্ড ও লক স্ক্রিনে প্রস্তুত"
                                    } else {
                                        "অফ আছে • সুইচ অন করে ভয়েস গার্ড চালু করুন"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (sentinelState.isSentinelActive) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = sentinelState.isSentinelActive,
                            onCheckedChange = { requestAndEnableSentinel(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF031810),
                                checkedTrackColor = EmeraldSuccess
                            ),
                            modifier = Modifier.testTag("hey_cube_sentinel_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Animated Holographic Cube Emblem
                    AnimatedCyberCubeEmblem(
                        isActive = sentinelState.isSentinelActive || voiceState.isListening,
                        phase = sentinelState.phase,
                        onClick = {
                            onSimulateHeyCube()
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = when (sentinelState.phase) {
                            WakeWordPhase.GREETING_BOSS -> "\"${sentinelState.bossGreeting}\""
                            WakeWordPhase.AWAITING_BOSS_COMMAND -> "জি স্যার, আমি শুনছি... এখন আপনার আদেশ বলুন!"
                            WakeWordPhase.PROCESSING_COMMAND -> "স্যারের আদেশ কার্যকর করা হচ্ছে..."
                            WakeWordPhase.LISTENING_FOR_WAKE_WORD -> "কান পেতে আছি — \"Hey Cube\" বা \"Cube\" বলে ডাকুন"
                            WakeWordPhase.INACTIVE -> "নিচের বাটনে ট্যাপ করে অথবা গার্ড অন করে \"Hey Cube\" বলে ডাকুন"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = ElectricCyan,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                onSimulateHeyCube()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = Color(0xFF04141C)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("simulate_hey_cube_button")
                        ) {
                            Icon(imageVector = Icons.Default.RecordVoiceOver, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "\"Hey Cube\" ডাকুন",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                requestAndEnableSentinel(true)
                                onEnterLockScreenStandby(true)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("lock_screen_hud_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenLockPortrait,
                                contentDescription = null,
                                tint = ElectricCyan
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "স্ক্রিন-অফ স্ট্যান্ডবাই",
                                style = MaterialTheme.typography.labelLarge,
                                color = ElectricCyan
                            )
                        }
                    }
                }
            }
        }

        // Boss Greeting Customizer Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "\"Hey Cube\" ডাকলে কিউব কি বলে সাড়া দেবে?",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = greetingInput,
                        onValueChange = { greetingInput = it },
                        label = { Text("স্যার গ্রিটিং ডায়ালগ (Sir Greeting)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("boss_greeting_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GREETING_PRESETS.forEach { preset ->
                            AssistChip(
                                onClick = {
                                    greetingInput = preset
                                    CubeSentinelBus.updateBossGreeting(preset)
                                },
                                label = {
                                    Text(
                                        text = preset,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    FilledTonalButton(
                        onClick = { CubeSentinelBus.updateBossGreeting(greetingInput) },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("গ্রিটিং সেভ করুন")
                    }
                }
            }
        }

        // Active Wake Words Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "সক্রিয় ওয়েক-ওয়ার্ড সমূহ (Wake Words):",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sentinelState.wakeWords.forEach { word ->
                            Surface(
                                color = ElectricCyan.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.border(
                                    width = 1.dp,
                                    color = ElectricCyan.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(50)
                                )
                            ) {
                                Text(
                                    text = "\"$word\"",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = ElectricCyan,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customWakeInput,
                            onValueChange = { customWakeInput = it },
                            placeholder = { Text("নতুন ওয়েক-ওয়ার্ড যোগ করুন (যেমন: ওস্তাদ / জার্ভিস)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("custom_wake_word_input")
                        )
                        Button(
                            onClick = {
                                if (customWakeInput.isNotBlank()) {
                                    CubeSentinelBus.addCustomWakeWord(customWakeInput)
                                    customWakeInput = ""
                                }
                            },
                            modifier = Modifier.height(52.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "যোগ করুন")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnimatedCyberCubeEmblem(
    isActive: Boolean,
    phase: WakeWordPhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cube_anim")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 5000 else 11000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cube_angle"
    )
    val scalePulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = if (phase == WakeWordPhase.GREETING_BOSS || phase == WakeWordPhase.AWAITING_BOSS_COMMAND) 1.2f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cube_pulse"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(136.dp)
            .clip(CircleShape)
            .clickable { onClick() }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.36f * scalePulse

            val primaryColor = when (phase) {
                WakeWordPhase.GREETING_BOSS -> EmeraldSuccess
                WakeWordPhase.AWAITING_BOSS_COMMAND -> PulseRose
                else -> ElectricCyan
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.38f), Color.Transparent),
                    center = center,
                    radius = radius * 1.4f
                ),
                radius = radius * 1.4f,
                center = center
            )

            rotate(degrees = angle, pivot = center) {
                val boxSize = radius * 1.1f
                drawRect(
                    color = primaryColor,
                    topLeft = Offset(center.x - boxSize / 2f, center.y - boxSize / 2f),
                    size = Size(boxSize, boxSize),
                    style = Stroke(width = 3.dp.toPx())
                )
            }

            rotate(degrees = -angle * 0.7f, pivot = center) {
                val boxSize = radius * 0.85f
                drawRect(
                    color = NeuralViolet,
                    topLeft = Offset(center.x - boxSize / 2f, center.y - boxSize / 2f),
                    size = Size(boxSize, boxSize),
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Hey Cube",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
            Text(
                text = "CUBE",
                style = MaterialTheme.typography.labelLarge,
                color = ElectricCyan,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AmoledCubeLockScreenStandbyHud(
    sentinelState: CubeSentinelState,
    voiceState: VoiceBridgeState,
    hardwareState: DeviceHardwareSnapshot,
    onTriggerHeyCube: () -> Unit,
    onExitLockStandby: () -> Unit
) {
    val currentTime = SimpleDateFormat("hh:mm", Locale.getDefault()).format(Date())
    val currentDate = SimpleDateFormat("EEEE, dd MMM", Locale.getDefault()).format(Date())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp)
            .testTag("amoled_lock_screen_hud")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Lock-Screen Clock & Battery Bar
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = Color(0xFF101A2E),
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = null,
                            tint = EmeraldSuccess,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ব্যাটারি ${hardwareState.batteryPercent}% • স্ক্রিন-অফ কিউব গার্ড সক্রিয়",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFB0C4DE)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = currentTime,
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = currentDate,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF8FA4C4)
                )
            }

            // Center Glowing Cube Sentinel
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedCyberCubeEmblem(
                    isActive = true,
                    phase = sentinelState.phase,
                    onClick = onTriggerHeyCube
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = when (sentinelState.phase) {
                        WakeWordPhase.GREETING_BOSS -> "\"${sentinelState.bossGreeting}\""
                        WakeWordPhase.AWAITING_BOSS_COMMAND -> "জি স্যার! এখন আপনার আদেশ বলুন..."
                        else -> "\"Hey Cube\" বা \"Cube\" বলে ডাকুন"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = ElectricCyan,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (voiceState.partialTranscript.isNotBlank()) {
                        "শোনা গেছে: \"${voiceState.partialTranscript}\""
                    } else {
                        "মোবাইল লক বা স্ক্রিন অন্ধকার থাকলেও কিউব আপনার ডাক শুনবে"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7D92B5),
                    textAlign = TextAlign.Center
                )
            }

            // Bottom Wake / Exit Controls
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onTriggerHeyCube,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color(0xFF04141C)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(imageVector = Icons.Default.RecordVoiceOver, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("\"Hey Cube\" বলে জাগিয়ে তুলুন")
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onExitLockStandby,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.LockOpen, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("পূর্ণ ড্যাশবোর্ডে ফিরে যান", color = Color.White)
                }
            }
        }
    }
}
