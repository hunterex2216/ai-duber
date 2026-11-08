package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.service.MusicPlayerState
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.PulseRose

@Composable
fun AgentHeroCoreHeader(
    isListening: Boolean,
    isThinking: Boolean,
    isPlayingMusic: Boolean,
    statusSubtitle: String,
    onOrbClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = if (isListening || isThinking || isPlayingMusic) 1.18f else 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isListening || isThinking) 750 else 1800,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rotation"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("agent_hero_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(164.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_agent_core_art),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF0A1124),
                                Color(0xFF171138),
                                Color(0xFF09223B)
                            )
                        )
                    ),
                alpha = 0.35f
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        color = when {
                            isListening -> PulseRose.copy(alpha = 0.2f)
                            isThinking -> NeuralViolet.copy(alpha = 0.25f)
                            isPlayingMusic -> EmeraldSuccess.copy(alpha = 0.2f)
                            else -> ElectricCyan.copy(alpha = 0.16f)
                        },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isListening -> PulseRose
                                            isThinking -> NeuralViolet
                                            isPlayingMusic -> EmeraldSuccess
                                            else -> ElectricCyan
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    isListening -> "ভয়েস শুনছি..."
                                    isThinking -> "এআই প্ল্যান তৈরি করছে..."
                                    isPlayingMusic -> "মিউজিক ও এজেন্ট সক্রিয়"
                                    else -> "অমনি-এজেন্ট অনলাইন"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }

                    Text(
                        text = "আপনার মোবাইল এখন আপনার কথায় চলবে",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = statusSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB8C7E8),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Interactive Animated AI Neural Orb
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape)
                        .clickable { onOrbClick() }
                        .testTag("agent_voice_orb")
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val baseRadius = size.minDimension * 0.34f * pulseScale

                        val primaryGlow = when {
                            isListening -> PulseRose
                            isThinking -> NeuralViolet
                            else -> ElectricCyan
                        }

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    primaryGlow.copy(alpha = 0.45f),
                                    NeuralViolet.copy(alpha = 0.18f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = baseRadius * 1.45f
                            ),
                            radius = baseRadius * 1.45f,
                            center = center
                        )

                        drawCircle(
                            color = primaryGlow.copy(alpha = 0.6f),
                            radius = baseRadius * 1.12f,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )

                        val angleRad = Math.toRadians(ringRotation.toDouble())
                        val dotOffset = Offset(
                            x = center.x + (baseRadius * 1.12f * kotlin.math.cos(angleRad)).toFloat(),
                            y = center.y + (baseRadius * 1.12f * kotlin.math.sin(angleRad)).toFloat()
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 4.dp.toPx(),
                            center = dotOffset
                        )

                        drawCircle(
                            brush = Brush.linearGradient(
                                colors = listOf(primaryGlow, NeuralViolet)
                            ),
                            radius = baseRadius * 0.85f,
                            center = center
                        )
                    }

                    Icon(
                        imageVector = if (isPlayingMusic && !isListening) Icons.Default.GraphicEq else Icons.Default.Mic,
                        contentDescription = "ভয়েস কমান্ড অরব",
                        tint = Color(0xFF06101E),
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun NowPlayingMiniBanner(
    musicState: MusicPlayerState,
    onTogglePlay: () -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(listOf(ElectricCyan, NeuralViolet, PulseRose)),
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("mini_music_banner"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF131D36)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(listOf(ElectricCyan, NeuralViolet))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = Color(0xFF071020)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = musicState.currentTrack.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${musicState.currentTrack.artist} • ${musicState.activeMood.titleBn}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.testTag("mini_player_play_pause")
                    ) {
                        Icon(
                            imageVector = if (musicState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (musicState.isPlaying) "Pause" else "Play",
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = onOpenExternal,
                        modifier = Modifier.testTag("mini_player_external")
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "ইউটিউব বা মিউজিক অ্যাপে খুলুন",
                            tint = ElectricCyan
                        )
                    }
                }
            }

            if (musicState.isPlaying) {
                Spacer(modifier = Modifier.height(8.dp))
                AudioWaveformVisualizer(
                    levels = musicState.waveformLevels,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                )
            }
        }
    }
}

@Composable
fun AudioWaveformVisualizer(
    levels: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val barCount = levels.size.coerceAtLeast(1)
        val spacing = 4.dp.toPx()
        val totalSpacing = spacing * (barCount - 1)
        val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(2f)

        levels.forEachIndexed { idx, level ->
            val clamped = level.coerceIn(0.12f, 1f)
            val barHeight = size.height * clamped
            val top = (size.height - barHeight) / 2f
            val left = idx * (barWidth + spacing)

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(ElectricCyan, NeuralViolet, PulseRose)
                ),
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
        }
    }
}
