package com.example.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.model.CuratedTrack
import com.example.model.MusicMood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

data class MusicPlayerState(
    val isPlaying: Boolean = false,
    val currentTrack: CuratedTrack = BuiltInMusicEngine.CURATED_TRACKS.first(),
    val activeMood: MusicMood = MusicMood.ROMANTIC,
    val currentStepIndex: Int = 0,
    val waveformLevels: List<Float> = List(16) { 0.15f }
)

class BuiltInMusicEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var synthJob: Job? = null
    private var audioTrack: AudioTrack? = null

    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()

    fun playByQueryAndMood(query: String, artist: String, moodStr: String): CuratedTrack {
        val resolvedMood = MusicMood.fromString(moodStr.ifBlank { query })
        val combinedSearch = "$query $artist".trim().lowercase()

        // Find best matching curated track or create a dynamic artist/song track
        val matchedTrack = CURATED_TRACKS.firstOrNull { track ->
            (artist.isNotBlank() && track.artist.lowercase().contains(artist.lowercase())) ||
                (query.isNotBlank() && (
                    track.title.lowercase().contains(query.lowercase()) ||
                        track.artist.lowercase().contains(query.lowercase())
                    ))
        } ?: if (artist.isNotBlank() || (query.isNotBlank() && !isGenericMoodQuery(combinedSearch))) {
            val displayTitle = when {
                query.isNotBlank() && artist.isNotBlank() -> "$query — $artist"
                artist.isNotBlank() -> "$artist স্পেশাল হিটস"
                else -> query.replaceFirstChar { it.uppercase() }
            }
            val displayArtist = artist.ifBlank { "এআই কিউরেটেড (${resolvedMood.titleBn})" }
            val ytQuery = listOf(query, artist, resolvedMood.titleEn, "song").filter { it.isNotBlank() }.joinToString(" ")
            CuratedTrack(
                id = "dynamic_${System.currentTimeMillis()}",
                title = displayTitle,
                artist = displayArtist,
                mood = resolvedMood,
                language = "বাংলা / মাল্টি",
                youtubeQuery = ytQuery
            )
        } else {
            CURATED_TRACKS.firstOrNull { it.mood == resolvedMood } ?: CURATED_TRACKS.first()
        }

        playTrack(matchedTrack)
        return matchedTrack
    }

    private fun isGenericMoodQuery(q: String): Boolean {
        val genericWords = listOf(
            "sad", "song", "romantic", "music", "play", "ভালো", "সুন্দর",
            "গান", "চালাও", "বাজাও", "স্যাড", "মন খারাপ", "রোমান্টিক"
        )
        val cleaned = genericWords.fold(q) { acc, w -> acc.replace(w, "") }.trim()
        return cleaned.length < 3
    }

    fun playTrack(track: CuratedTrack) {
        stop()
        _state.value = _state.value.copy(
            isPlaying = true,
            currentTrack = track,
            activeMood = track.mood
        )
        startPolyphonicSynth(track.mood)
    }

    fun togglePlayPause() {
        if (_state.value.isPlaying) {
            stop()
        } else {
            playTrack(_state.value.currentTrack)
        }
    }

    fun stop() {
        synthJob?.cancel()
        synthJob = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        _state.value = _state.value.copy(
            isPlaying = false,
            waveformLevels = List(16) { 0.12f }
        )
    }

    private fun startPolyphonicSynth(mood: MusicMood) {
        synthJob = scope.launch {
            val sampleRate = 24000
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Exception) {
                null
            }

            audioTrack = track
            try {
                track?.play()
            } catch (_: Exception) {
            }

            val notes = mood.baseFreqs
            val beatDurationSec = 60.0 / mood.tempoBpm.toDouble()
            val samplesPerBeat = (sampleRate * beatDurationSec).toInt().coerceAtLeast(2400)
            val chunkSize = 1200
            val buffer = ShortArray(chunkSize)

            var stepIdx = 0
            while (isActive) {
                val leadFreq = notes[stepIdx % notes.size]
                val bassFreq = notes[0] * 0.5
                val fifthFreq = leadFreq * 1.498 // Warm 5th harmonic

                var sampleInBeat = 0
                while (sampleInBeat < samplesPerBeat && isActive) {
                    val count = minOf(chunkSize, samplesPerBeat - sampleInBeat)
                    var energyAcc = 0f
                    for (i in 0 until count) {
                        val globalSample = sampleInBeat + i
                        val t = globalSample.toDouble() / sampleRate.toDouble()
                        // Smooth piano/pad envelope
                        val attack = (t / 0.04).coerceAtMost(1.0)
                        val decay = exp(-2.8 * t / beatDurationSec)
                        val env = attack * decay

                        // Sub-pad warmth + lead note + gentle harmonic overtone
                        val leadWave = sin(2.0 * PI * leadFreq * t) +
                            0.35 * sin(2.0 * PI * (leadFreq * 2.0) * t) +
                            0.20 * sin(2.0 * PI * fifthFreq * t)
                        val bassWave = 0.45 * sin(2.0 * PI * bassFreq * t) * exp(-1.2 * t / beatDurationSec)

                        val combined = ((leadWave * env * 0.55) + bassWave).coerceIn(-0.95, 0.95)
                        buffer[i] = (combined * Short.MAX_VALUE * 0.45).toInt().toShort()
                        energyAcc += kotlin.math.abs(combined.toFloat())
                    }

                    try {
                        track?.write(buffer, 0, count)
                    } catch (_: Exception) {
                    }

                    val avgEnergy = (energyAcc / count).coerceIn(0.1f, 1.0f)
                    val animatedBars = List(16) { barIdx ->
                        val phase = sin((stepIdx * 0.8 + barIdx * 0.55 + sampleInBeat * 0.002)).toFloat()
                        (0.18f + avgEnergy * 0.65f * kotlin.math.abs(phase)).coerceIn(0.12f, 0.98f)
                    }
                    _state.value = _state.value.copy(
                        currentStepIndex = stepIdx,
                        waveformLevels = animatedBars
                    )

                    sampleInBeat += count
                }
                stepIdx++
            }
        }
    }

    companion object {
        val CURATED_TRACKS = listOf(
            CuratedTrack(
                id = "romantic_1",
                title = "মন ছুঁয়ে যাওয়া সন্ধ্যা (Romantic Harmony)",
                artist = "হাবিব ওয়াহিদ ও ন্যান্সি স্টাইল",
                mood = MusicMood.ROMANTIC,
                language = "বাংলা রোমান্টিক",
                youtubeQuery = "Habib Wahid romantic bangla songs"
            ),
            CuratedTrack(
                id = "sad_1",
                title = "নীরব প্রহর — স্যাড পিয়ানো ও ভায়োলিন",
                artist = "অরিজিৎ সিং ও অর্ণব ভাইবস",
                mood = MusicMood.SAD,
                language = "স্যাড সং স্পেশাল",
                youtubeQuery = "Arijit Singh Arnob best sad songs"
            ),
            CuratedTrack(
                id = "sad_2",
                title = "সে যে বসে আছে একা একা",
                artist = "অর্ণব (Arnob Acoustic Mood)",
                mood = MusicMood.SAD,
                language = "বাংলা ইন্ডি স্যাড",
                youtubeQuery = "Arnob Shey Je Boshe Ache sad bangla song"
            ),
            CuratedTrack(
                id = "romantic_2",
                title = "কেশরিয়া ও তুম হি হো মেলোডি",
                artist = "অরিজিৎ সিং (Arijit Singh Hits)",
                mood = MusicMood.ROMANTIC,
                language = "হিন্দি রোমান্টিক",
                youtubeQuery = "Arijit Singh best romantic songs jukebox"
            ),
            CuratedTrack(
                id = "lofi_1",
                title = "মধ্যরাতের বৃষ্টি — লো-ফাই চিল",
                artist = "কোক স্টুডিও বাংলা লো-ফাই মিক্স",
                mood = MusicMood.LOFI,
                language = "লো-ফাই চিল",
                youtubeQuery = "Bangla Lofi chill rain songs"
            ),
            CuratedTrack(
                id = "classical_1",
                title = "মায়া ও রবীন্দ্র সিম্ফনি",
                artist = "রবীন্দ্রসংগীত ফিউশন",
                mood = MusicMood.CLASSICAL,
                language = "ক্লাসিক্যাল ও রবীন্দ্র",
                youtubeQuery = "Best peaceful Rabindra Sangeet instrumental vocal"
            ),
            CuratedTrack(
                id = "upbeat_1",
                title = "নতুন আলোর উৎসব (Upbeat Pulse)",
                artist = "পার্টি ও ড্রাইভিং বিটস",
                mood = MusicMood.UPBEAT,
                language = "পপ ও ড্যান্স",
                youtubeQuery = "Bangla Hindi upbeat energetic party songs"
            )
        )
    }
}
