package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.CuratedTrack
import com.example.model.MusicMood
import com.example.service.BuiltInMusicEngine
import com.example.service.MusicPlayerState
import com.example.ui.components.AudioWaveformVisualizer
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.PulseRose

@Composable
fun MusicStudioScreen(
    musicState: MusicPlayerState,
    onPlayTrack: (CuratedTrack) -> Unit,
    onSelectMood: (MusicMood) -> Unit,
    onPlayCustomSearch: (String, String, String) -> Unit,
    onTogglePlayPause: () -> Unit,
    onStopMusic: () -> Unit,
    onOpenExternalMusic: (String, String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onNavigateBack() }

    val keyboardController = LocalSoftwareKeyboardController.current
    var searchSongOrArtist by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("music_studio_list"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        item {
            Text(
                text = "এআই মিউজিক ও মুড ইঞ্জিন",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Text(
                text = "যেকোনো মুড, গানের ধরন বা শিল্পীর নাম বললেই ইন-অ্যাপ সিন্থ ও ইউটিউব/মিউজিক অ্যাপে গান বাজবে",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Active Synthesizer & Visualizer Stage Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        brush = Brush.horizontalGradient(listOf(ElectricCyan, NeuralViolet, PulseRose)),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .testTag("now_playing_stage_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF101930)
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            color = if (musicState.isPlaying) EmeraldSuccess.copy(alpha = 0.2f) else NeuralViolet.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = if (musicState.isPlaying) EmeraldSuccess else ElectricCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (musicState.isPlaying) "এখন বাজছে • ${musicState.activeMood.titleBn}" else "স্ট্যান্ডবাই • ${musicState.activeMood.titleBn}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }

                        Text(
                            text = "${musicState.activeMood.tempoBpm} BPM",
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    Brush.linearGradient(listOf(ElectricCyan, NeuralViolet, PulseRose))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                tint = Color(0xFF06101E),
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = musicState.currentTrack.title,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = musicState.currentTrack.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ElectricCyan
                            )
                            Text(
                                text = musicState.activeMood.subtitleBn,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    AudioWaveformVisualizer(
                        levels = musicState.waveformLevels,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = onTogglePlayPause,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = Color(0xFF04141C)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("music_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (musicState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (musicState.isPlaying) "পজ করুন" else "এখনই বাজান",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        if (musicState.isPlaying) {
                            IconButton(
                                onClick = onStopMusic,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(PulseRose.copy(alpha = 0.22f))
                                    .testTag("music_stop_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "গান বন্ধ করুন",
                                    tint = PulseRose
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                onOpenExternalMusic(
                                    musicState.currentTrack.youtubeQuery,
                                    musicState.currentTrack.artist
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("open_youtube_music_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ইউটিউবে শুনুন",
                                style = MaterialTheme.typography.labelLarge,
                                color = ElectricCyan
                            )
                        }
                    }
                }
            }
        }

        // Artist / Song Name Direct Finder
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "যেকোনো শিল্পী বা গানের নাম লিখুন:",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchSongOrArtist,
                            onValueChange = { searchSongOrArtist = it },
                            placeholder = {
                                Text(
                                    text = "যেমন: Arijit Singh, অর্ণব, হাবিব, স্যাড সং...",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (searchSongOrArtist.isNotBlank()) {
                                        onPlayCustomSearch(searchSongOrArtist, searchSongOrArtist, "")
                                        keyboardController?.hide()
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("artist_search_input")
                        )

                        Button(
                            onClick = {
                                if (searchSongOrArtist.isNotBlank()) {
                                    onPlayCustomSearch(searchSongOrArtist, searchSongOrArtist, "")
                                    keyboardController?.hide()
                                }
                            },
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("artist_search_play_button")
                        ) {
                            Icon(imageVector = Icons.Default.Search, contentDescription = "খুঁজুন ও বাজান")
                        }
                    }
                }
            }
        }

        // Mood Selector Pills
        item {
            Column {
                Text(
                    text = "মুড অনুযায়ী তাৎক্ষণিক সুর:",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("music_mood_row")
                ) {
                    items(MusicMood.entries) { mood ->
                        val selected = musicState.activeMood == mood
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectMood(mood) },
                            label = {
                                Text(
                                    text = mood.titleBn,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                                selectedLabelColor = ElectricCyan
                            )
                        )
                    }
                }
            }
        }

        // Curated Bangla & Popular Tracks List
        item {
            Text(
                text = "জনপ্রিয় শিল্পী ও মুড প্লেলিস্ট:",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }

        items(BuiltInMusicEngine.CURATED_TRACKS, key = { it.id }) { track ->
            val isCurrent = musicState.currentTrack.id == track.id && musicState.isPlaying
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayTrack(track) }
                    .testTag("track_card_${track.id}"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isCurrent) {
                        ElectricCyan.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isCurrent) ElectricCyan else NeuralViolet.copy(alpha = 0.25f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isCurrent) Icons.Default.GraphicEq else Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (isCurrent) Color(0xFF04141C) else Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${track.artist} • ${track.language}",
                                style = MaterialTheme.typography.bodySmall,
                                color = ElectricCyan
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onPlayTrack(track) }) {
                            Icon(
                                imageVector = if (isCurrent) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = { onOpenExternalMusic(track.youtubeQuery, track.artist) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "ইউটিউবে শুনুন",
                                tint = ElectricCyan
                            )
                        }
                    }
                }
            }
        }
    }
}
