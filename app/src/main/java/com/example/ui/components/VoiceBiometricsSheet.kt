package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.SpeakerRole
import com.example.service.VoiceBiometricsEngine
import com.example.service.VoiceBiometricsState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.PulseRose

@Composable
fun AnimatedVoiceWaveformCanvas(
    bars: List<Float>,
    accentColor: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = bars.size.coerceAtLeast(1)
    val animatedHeights = bars.mapIndexed { idx, value ->
        val animated by animateFloatAsState(
            targetValue = if (isActive) value.coerceIn(0.10f, 1f) else (0.12f + (idx % 3) * 0.04f),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "waveBar_$idx"
        )
        animated
    }

    Canvas(modifier = modifier) {
        val totalWidth = size.width
        val totalHeight = size.height
        val slotWidth = totalWidth / barCount
        val barWidth = (slotWidth * 0.56f).coerceAtLeast(2.5f)
        val gap = (slotWidth - barWidth) / 2f

        for (i in 0 until barCount) {
            val frac = animatedHeights.getOrElse(i) { 0.1f }
            val barH = (totalHeight * frac).coerceAtLeast(4f)
            val top = (totalHeight - barH) / 2f
            val left = i * slotWidth + gap
            val barAlpha = if (isActive) (0.55f + 0.45f * frac) else 0.35f
            drawRoundRect(
                color = accentColor.copy(alpha = barAlpha),
                topLeft = Offset(left, top),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun SpeakerConfidenceRing(
    percent: Int,
    accentColor: Color,
    ringSize: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val animProgress by animateFloatAsState(
        targetValue = (percent.coerceIn(0, 100)) / 100f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "confidenceRing"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(ringSize)
    ) {
        Canvas(modifier = Modifier.size(ringSize)) {
            val stroke = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = ObsidianSurfaceVariant,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke
            )
            drawArc(
                color = accentColor,
                startAngle = -90f,
                sweepAngle = 360f * animProgress,
                useCenter = false,
                style = stroke
            )
        }
        Text(
            text = if (percent > 0) "$percent%" else "--",
            color = accentColor,
            fontSize = if (ringSize >= 50.dp) 12.sp else 9.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun UpgradedVoiceBiometricsHudBar(
    onOpenDetailSheet: (initialTab: Int) -> Unit,
    onQuickBossEnroll: () -> Unit,
    onQuickGuestEnroll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bioState by VoiceBiometricsEngine.state.collectAsState()

    val accentColor by animateColorAsState(
        targetValue = when {
            bioState.isSuspectedSpoof -> PulseRose
            bioState.isEnrolling || bioState.isNoiseCalibrating -> AmberWarning
            bioState.isOwnerBossVerified && bioState.hasOwnerBossProfile -> EmeraldSuccess
            bioState.isKnownEnrolledSpeaker -> ElectricCyan
            else -> NeuralViolet
        },
        label = "hudAccentColor"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(ObsidianCard, ObsidianSurface)
                )
            )
            .border(1.1.dp, accentColor.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .clickable { onOpenDetailSheet(0) }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("hud_voice_biometrics_pill")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Left: Live Multi-Band Waveform Box
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianBackground)
                    .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVoiceWaveformCanvas(
                    bars = bioState.waveformBars.take(10),
                    accentColor = accentColor,
                    isActive = bioState.isCurrentlySpeaking || bioState.isEnrolling || bioState.isNoiseCalibrating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                )
            }

            // Center: Identity + Role + Mood + Acoustic Metrics
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = when {
                            bioState.isSuspectedSpoof -> "⚠️ রেকর্ডেড অডিও সতর্কতা!"
                            bioState.isEnrolling -> "🎙️ ভয়েস স্ক্যান (${bioState.enrollmentStepIndex + 1}/৩)"
                            bioState.isNoiseCalibrating -> "🎛️ নয়েজ ক্যালিব্রেট হচ্ছে..."
                            else -> "🎙️ ${bioState.activeSpeakerName}"
                        },
                        color = accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        color = accentColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = bioState.activeSpeakerRole.labelEn,
                            color = accentColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Surface(
                        color = ObsidianSurfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${bioState.detectedMood.emoji} ${bioState.detectedMood.labelEn}",
                            color = Color(0xFFE2E8F0),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = buildString {
                        append(bioState.detectedGender.labelBn)
                        if (bioState.currentPitchHz > 0) append(" • ${bioState.currentPitchHz}Hz")
                        append(" • SNR ${bioState.snrDb}dB")
                        append(" • Liveness ${bioState.antiSpoofLivenessScore}%")
                    },
                    color = Color(0xFF94A3B8),
                    fontSize = 9.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Right: Confidence Ring + Shield Icon
            SpeakerConfidenceRing(
                percent = bioState.similarityScorePercent,
                accentColor = accentColor,
                ringSize = 36.dp
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Action Pills Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onQuickBossEnroll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (bioState.hasOwnerBossProfile) EmeraldSuccess.copy(alpha = 0.9f) else ElectricCyan,
                    contentColor = Color.Black
                ),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("enroll_boss_voice_lock_button")
            ) {
                Icon(
                    imageVector = if (bioState.hasOwnerBossProfile) Icons.Default.VerifiedUser else Icons.Default.Lock,
                    contentDescription = "বসের ভয়েস লক",
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (bioState.isEnrolling && bioState.enrollmentIsOwnerBoss) {
                        "ধাপ ${bioState.enrollmentStepIndex + 1}/৩ (${bioState.enrollmentProgressPercent}%)"
                    } else if (bioState.hasOwnerBossProfile) {
                        "বসের লক ✓"
                    } else {
                        "বসের ভয়েস লক"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            OutlinedButton(
                onClick = onQuickGuestEnroll,
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("enroll_guest_voice_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = "নতুন বক্তা যোগ করুন",
                    tint = ElectricCyan,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "বক্তা (${bioState.enrolledProfiles.size})",
                    color = ElectricCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(
                onClick = { onOpenDetailSheet(0) },
                border = BorderStroke(1.dp, NeuralViolet.copy(alpha = 0.7f)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("open_biometrics_detail_sheet_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "বিস্তারিত বায়োমেট্রিক্স প্যানেল খুলুন",
                    tint = Color(0xFFD8B4FE),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "প্যানেল",
                    color = Color(0xFFD8B4FE),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (bioState.isEnrolling && bioState.currentCalibrationSentenceBn.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = Color(0xFF1F2937),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "🗣️ এখন বলুন (ধাপ ${bioState.enrollmentStepIndex + 1}/৩): \"${bioState.currentCalibrationSentenceBn}\"",
                    color = AmberWarning,
                    fontSize = 10.4.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }

        if (bioState.lastSecurityGateEventBn.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = bioState.lastSecurityGateEventBn,
                color = if (bioState.isSuspectedSpoof || bioState.lastSecurityGateEventBn.contains("ব্লক") || bioState.lastSecurityGateEventBn.contains("দুঃখিত")) {
                    PulseRose
                } else {
                    Color(0xFFA7F3D0)
                },
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceBiometricsDetailSheet(
    initialTab: Int = 0,
    onSpeakStatus: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bioState by VoiceBiometricsEngine.state.collectAsState()
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianBackground,
        contentColor = Color.White,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .align(Alignment.CenterHorizontally)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ElectricCyan.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "ভয়েস বায়োমেট্রিক্স",
                            tint = ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "অ্যাডভান্সড ভয়েস বায়োমেট্রিক্স",
                            color = Color.White,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "লাইভ অ্যাকোস্টিক, অ্যান্টি-স্পুফ ও রোল সিকিউরিটি",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.5.sp
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_biometrics_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "বন্ধ করুন",
                        tint = Color(0xFFCBD5E1)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Segmented Tab Switcher
            val tabs = listOf("লাইভ মিটার", "বক্তা ও রোল", "সিকিউরিটি লগ")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianSurface)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabs.forEachIndexed { idx, label ->
                    val isSelected = selectedTab == idx
                    Surface(
                        color = if (isSelected) ElectricCyan.copy(alpha = 0.2f) else Color.Transparent,
                        shape = RoundedCornerShape(9.dp),
                        border = if (isSelected) BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.6f)) else null,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { selectedTab = idx }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = label,
                                color = if (isSelected) ElectricCyan else Color(0xFF94A3B8),
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> LiveTelemetryTab(
                        bioState = bioState,
                        onCalibrateNoise = {
                            VoiceBiometricsEngine.startTwoSecondNoiseCalibration(context) { msg ->
                                onSpeakStatus(msg)
                            }
                        },
                        onStartBossTraining = {
                            VoiceBiometricsEngine.startFiveSecondVoiceEnrollment(
                                context = context,
                                speakerName = "বস",
                                isOwnerBoss = true,
                                onComplete = { msg -> onSpeakStatus(msg) }
                            )
                        },
                        onCancelTraining = {
                            VoiceBiometricsEngine.cancelActiveEnrollment()
                        }
                    )
                    1 -> SpeakersAndRolesTab(
                        bioState = bioState,
                        onEnrollSpeaker = { name ->
                            val clean = name.trim().ifBlank { "মেহমান" }
                            val isBoss = clean.equals("বস", ignoreCase = true) || clean.equals("Boss", ignoreCase = true)
                            VoiceBiometricsEngine.startFiveSecondVoiceEnrollment(
                                context = context,
                                speakerName = clean,
                                isOwnerBoss = isBoss,
                                onComplete = { msg -> onSpeakStatus(msg) }
                            )
                        },
                        onChangeRole = { idOrName, role, chat, apps, sensitive ->
                            VoiceBiometricsEngine.updateSpeakerRoleAndPermissions(
                                context = context,
                                profileIdOrName = idOrName,
                                newRole = role,
                                allowGeneralChat = chat,
                                allowAppControl = apps,
                                allowSensitiveActions = sensitive
                            )
                        },
                        onDeleteProfile = { id ->
                            VoiceBiometricsEngine.deleteVoiceProfile(context, id)
                        }
                    )
                    else -> SecurityAndLogsTab(
                        bioState = bioState,
                        onToggleVoiceLock = { enabled ->
                            VoiceBiometricsEngine.setVoiceSecurityLockEnabled(context, enabled)
                        },
                        onToggleAntiSpoofStrict = { strict ->
                            VoiceBiometricsEngine.setAntiSpoofStrictMode(context, strict)
                        },
                        onClearLogs = {
                            VoiceBiometricsEngine.clearSecurityLogs(context)
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun LiveTelemetryTab(
    bioState: VoiceBiometricsState,
    onCalibrateNoise: () -> Unit,
    onStartBossTraining: () -> Unit,
    onCancelTraining: () -> Unit
) {
    val activeAccent = when {
        bioState.isSuspectedSpoof -> PulseRose
        bioState.isOwnerBossVerified && bioState.hasOwnerBossProfile -> EmeraldSuccess
        else -> ElectricCyan
    }

    // 1. Live Multi-Band Waveform & Speaker Telemetry Card
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, activeAccent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${bioState.activeSpeakerName} (${bioState.activeSpeakerRole.labelBn})",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${bioState.detectedGender.labelBn} • পিচ: ${bioState.currentPitchHz}Hz • স্পেকট্রাম: ${bioState.spectralCentroidHz}Hz",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
                Surface(
                    color = NeuralViolet.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${bioState.detectedMood.emoji} ${bioState.detectedMood.labelBn}",
                        color = Color(0xFFE9D5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianBackground)
                    .border(1.dp, activeAccent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVoiceWaveformCanvas(
                    bars = bioState.waveformBars,
                    accentColor = activeAccent,
                    isActive = bioState.isCurrentlySpeaking || bioState.isEnrolling || bioState.isNoiseCalibrating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                )
            }

            // Dual Confidence & Anti-Spoof Liveness Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = ObsidianSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SpeakerConfidenceRing(
                            percent = bioState.similarityScorePercent,
                            accentColor = activeAccent,
                            ringSize = 48.dp
                        )
                        Column {
                            Text("ম্যাচ কনফিডেন্স", color = Color(0xFF94A3B8), fontSize = 10.sp)
                            Text(
                                text = if (bioState.isOwnerBossVerified) "ভেরিফায়েড" else "গেস্ট / নতুন",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Surface(
                    color = ObsidianSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val spoofColor = if (bioState.isSuspectedSpoof) PulseRose else EmeraldSuccess
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SpeakerConfidenceRing(
                            percent = bioState.antiSpoofLivenessScore,
                            accentColor = spoofColor,
                            ringSize = 48.dp
                        )
                        Column {
                            Text("অ্যান্টি-স্পুফ শিল্ড", color = Color(0xFF94A3B8), fontSize = 10.sp)
                            Text(
                                text = if (bioState.isSuspectedSpoof) "⚠️ প্লেব্যাক রিস্ক" else "লাইভ মানুষ ✓",
                                color = spoofColor,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // 2. Adaptive Noise Filter & SNR Calibration Card
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🎛️ অ্যাডাপটিভ নয়েজ ফিল্টার ও SNR",
                    color = ElectricCyan,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "রুম নয়েজ ফ্লোর: ${bioState.noiseFloorRms.toInt()} RMS • সিগন্যাল SNR: ${bioState.snrDb}dB",
                    color = Color(0xFFCBD5E1),
                    fontSize = 10.8.sp
                )
            }
            Button(
                onClick = onCalibrateNoise,
                enabled = !bioState.isNoiseCalibrating && !bioState.isEnrolling,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = Color.Black
                ),
                modifier = Modifier
                    .height(42.dp)
                    .testTag("calibrate_noise_floor_button")
            ) {
                Text(
                    text = if (bioState.isNoiseCalibrating) "মাপা হচ্ছে..." else "২সে. ক্যালিব্রেট",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }

    // 3. Boss 3-Sentence Guided Calibration Studio
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "🔐 বসের মেইন ভয়েস লক ক্যালিব্রেশন (৩ ধাপ)",
                color = EmeraldSuccess,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
            VoiceBiometricsEngine.BOSS_CALIBRATION_SENTENCES.forEachIndexed { idx, sentence ->
                val activeStep = bioState.isEnrolling && bioState.enrollmentStepIndex == idx
                Surface(
                    color = if (activeStep) Color(0xFF123524) else ObsidianSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (activeStep) EmeraldSuccess else Color(0xFF283548)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = sentence,
                        color = if (activeStep) Color.White else Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontWeight = if (activeStep) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
            if (bioState.isEnrolling) {
                LinearProgressIndicator(
                    progress = { (bioState.enrollmentProgressPercent / 100f).coerceIn(0f, 1f) },
                    color = AmberWarning,
                    trackColor = ObsidianSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartBossTraining,
                    enabled = !bioState.isEnrolling,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldSuccess,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("sheet_start_boss_calibration_button")
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (bioState.isEnrolling) "কণ্ঠ শোনা হচ্ছে (${bioState.enrollmentProgressPercent}%)..." else "বসের ভয়েস ট্রেনিং শুরু করুন",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                if (bioState.isEnrolling) {
                    OutlinedButton(
                        onClick = onCancelTraining,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("বাতিল", color = PulseRose, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeakersAndRolesTab(
    bioState: VoiceBiometricsState,
    onEnrollSpeaker: (String) -> Unit,
    onChangeRole: (String, SpeakerRole, Boolean, Boolean, Boolean) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    var guestNameInput by remember { mutableStateOf("") }

    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "➕ নতুন বক্তার কণ্ঠ ও রোল এনরোল করুন",
                color = ElectricCyan,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "কেউ নিজে নাম বললেই (যেমন: 'আমার নাম সিয়াম') অটো-সেভ হয়, অথবা নিচে নাম লিখে দ্রুত রেকর্ড করুন:",
                color = Color(0xFF94A3B8),
                fontSize = 10.8.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = guestNameInput,
                    onValueChange = { guestNameInput = it },
                    placeholder = { Text("যেমন: সিয়াম, রাহাত, মীম", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        onEnrollSpeaker(guestNameInput)
                        guestNameInput = ""
                    },
                    enabled = !bioState.isEnrolling,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("রেকর্ড", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }

    if (bioState.enrolledProfiles.isEmpty()) {
        Surface(
            color = ObsidianSurface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(32.dp))
                Text(
                    text = "এখনো কোনো ভয়েস প্রোফাইল সেভ করা হয়নি",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "প্রথমে 'লাইভ মিটার' ট্যাব থেকে বসের ৩-বাক্য ক্যালিব্রেশন সম্পন্ন করুন।",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
        }
    } else {
        bioState.enrolledProfiles.forEach { profile ->
            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (profile.isOwnerBoss) EmeraldSuccess.copy(alpha = 0.6f) else Color(0xFF283548)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${profile.speakerName} • ${profile.gender.labelBn} (${profile.meanPitchHz.toInt()}Hz)",
                                color = if (profile.isOwnerBoss) EmeraldSuccess else Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = profile.speakingStyleSummaryBn,
                                color = Color(0xFF94A3B8),
                                fontSize = 10.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { onDeleteProfile(profile.id) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "প্রোফাইল মুছুন",
                                tint = PulseRose
                            )
                        }
                    }

                    // Role Selector Chips
                    Text("রোল নির্বাচন:", color = Color(0xFFCBD5E1), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val selectableRoles = if (profile.isOwnerBoss) {
                            listOf(SpeakerRole.OWNER_BOSS)
                        } else {
                            listOf(SpeakerRole.TRUSTED_FAMILY, SpeakerRole.GUEST_FRIEND, SpeakerRole.RESTRICTED_CHILD)
                        }
                        selectableRoles.forEach { role ->
                            val selected = profile.role == role
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    onChangeRole(
                                        profile.id,
                                        role,
                                        role.defaultGeneralChat,
                                        role.defaultAppControl,
                                        role.defaultSensitiveActions
                                    )
                                },
                                label = { Text(role.labelBn, fontSize = 10.5.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                                    selectedLabelColor = ElectricCyan
                                )
                            )
                        }
                    }

                    // Granular Permission Toggles
                    if (!profile.isOwnerBoss) {
                        HorizontalDivider(color = Color(0xFF1E293B))
                        PermissionToggleRow(
                            label = "সাধারণ কথাবার্তা ও চ্যাট",
                            checked = profile.allowGeneralChat,
                            onCheckedChange = { v ->
                                onChangeRole(profile.id, profile.role, v, profile.allowAppControl, profile.allowSensitiveActions)
                            }
                        )
                        PermissionToggleRow(
                            label = "সাধারণ অ্যাপ খোলা (YouTube/Browser)",
                            checked = profile.allowAppControl,
                            onCheckedChange = { v ->
                                onChangeRole(profile.id, profile.role, profile.allowGeneralChat, v, profile.allowSensitiveActions)
                            }
                        )
                        PermissionToggleRow(
                            label = "সংবেদনশীল আদেশ (মেসেজ/কল/OTP)",
                            checked = profile.allowSensitiveActions,
                            onCheckedChange = { v ->
                                onChangeRole(profile.id, profile.role, profile.allowGeneralChat, profile.allowAppControl, v)
                            }
                        )
                    } else {
                        Text(
                            text = "✓ বসের কণ্ঠে আনলিমিটেড ফুল এক্সেস সক্রিয় রয়েছে",
                            color = EmeraldSuccess,
                            fontSize = 10.8.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFFE2E8F0),
            fontSize = 11.5.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = EmeraldSuccess)
        )
    }
}

@Composable
private fun SecurityAndLogsTab(
    bioState: VoiceBiometricsState,
    onToggleVoiceLock: (Boolean) -> Unit,
    onToggleAntiSpoofStrict: (Boolean) -> Unit,
    onClearLogs: () -> Unit
) {
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, NeuralViolet.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldSuccess, modifier = Modifier.size(18.dp))
                    Column {
                        Text("বসের ভয়েস সিকিউরিটি গেট", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        Text("অনুমতি ছাড়া মেসেজ, কল, OTP ও ব্যাংক অ্যাপ ব্লক থাকবে", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    }
                }
                Switch(
                    checked = bioState.isVoiceSecurityLockEnabled,
                    onCheckedChange = onToggleVoiceLock
                )
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = AmberWarning, modifier = Modifier.size(18.dp))
                    Column {
                        Text("অ্যান্টি-স্পুফিং Strict শিল্ড", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        Text("রেকর্ডেড বা লাউডস্পিকার কণ্ঠস্বর কঠোরভাবে প্রতিরোধ করবে", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    }
                }
                Switch(
                    checked = bioState.antiSpoofStrictMode,
                    onCheckedChange = onToggleAntiSpoofStrict
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "সিকিউরিটি ও বায়োমেট্রিক্স লগ (${bioState.securityLogs.size})",
            color = Color(0xFFCBD5E1),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        if (bioState.securityLogs.isNotEmpty()) {
            TextButton(onClick = onClearLogs) {
                Text("সব মুছুন", color = PulseRose, fontSize = 11.sp)
            }
        }
    }

    if (bioState.securityLogs.isEmpty()) {
        Surface(
            color = ObsidianSurface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "এখনো কোনো সিকিউরিটি ইভেন্ট বা সতর্কতা রেকর্ড হয়নি।",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    } else {
        bioState.securityLogs.forEach { log ->
            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (log.isWarning) PulseRose.copy(alpha = 0.5f) else Color(0xFF1E293B)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = if (log.isWarning) Icons.Default.WarningAmber else Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = if (log.isWarning) PulseRose else EmeraldSuccess,
                        modifier = Modifier.size(16.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = log.summaryBn,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${log.speakerName} • ${log.timestampFormatted}",
                            color = Color(0xFF94A3B8),
                            fontSize = 9.5.sp
                        )
                    }
                }
            }
        }
    }
}
