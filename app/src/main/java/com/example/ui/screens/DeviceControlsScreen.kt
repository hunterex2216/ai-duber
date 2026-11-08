package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AgentMemoryNoteEntity
import com.example.data.CommandHistoryEntity
import com.example.model.SmartRoutine
import com.example.service.DeviceHardwareSnapshot
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeuralViolet
import com.example.ui.theme.PulseRose

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceControlsScreen(
    hardwareState: DeviceHardwareSnapshot,
    routines: List<SmartRoutine>,
    memoryNotes: List<AgentMemoryNoteEntity>,
    history: List<CommandHistoryEntity>,
    onVolumeChange: (String, Int) -> Unit,
    onRingerModeChange: (String) -> Unit,
    onToggleFlashlight: (Boolean) -> Unit,
    onOpenSettingsTarget: (String) -> Unit,
    onQuickSendEmail: (String, String, String) -> Unit,
    onQuickOpenCamera: () -> Unit,
    onQuickSetAlarm: (Int, Int, String) -> Unit,
    onAddMemoryNote: (String, String) -> Unit,
    onDeleteMemoryNote: (Int) -> Unit,
    onRunRoutine: (SmartRoutine) -> Unit,
    onReRunHistoryPrompt: (String) -> Unit,
    onDeleteHistoryItem: (Int) -> Unit,
    onClearAllHistory: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onNavigateBack() }

    var emailRecipient by rememberSaveable { mutableStateOf("") }
    var emailSubject by rememberSaveable { mutableStateOf("জরুরি আপডেট") }
    var emailBody by rememberSaveable { mutableStateOf("হ্যালো, আমি Cube AI থেকে এই বার্তাটি পাঠাচ্ছি।") }

    var noteTitle by rememberSaveable { mutableStateOf("") }
    var noteContent by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("device_controls_list"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        item {
            Text(
                text = "ডিভাইস কন্ট্রোল ও কিউব মেমোরি ভল্ট",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Text(
                text = "ভলিউম, ফ্ল্যাশলাইট, সেটিংস, ক্যামেরা, মেমোরি নোট এবং মাল্টি-অ্যাপ রুটিন নিয়ন্ত্রণ করুন",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Cube AI Memory & Voice Notes Vault
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF121D36)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdded,
                            contentDescription = null,
                            tint = EmeraldSuccess
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "কিউব এআই মেমোরি ও নোট ভল্ট (${memoryNotes.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Text(
                                text = "\"Hey Cube মনে রাখো...\" বললে এখানে স্বয়ংক্রিয়ভাবে সেভ হয়",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = noteContent,
                            onValueChange = { noteContent = it },
                            placeholder = { Text("নতুন মেমোরি বা টাস্ক নোট লিখুন...") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("memory_note_input")
                        )
                        Button(
                            onClick = {
                                if (noteContent.isNotBlank()) {
                                    onAddMemoryNote(noteTitle.ifBlank { "বসের নোট" }, noteContent)
                                    noteContent = ""
                                }
                            },
                            modifier = Modifier.height(52.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "নোট যোগ করুন")
                        }
                    }

                    if (memoryNotes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            memoryNotes.take(5).forEach { note ->
                                Surface(
                                    color = Color(0xFF0B1325),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = note.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = EmeraldSuccess
                                            )
                                            Text(
                                                text = note.content,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White
                                            )
                                        }
                                        IconButton(onClick = { onDeleteMemoryNote(note.id) }) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "মুছুন",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Hardware Flashlight & Sound Profile Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (hardwareState.isFlashlightOn) AmberWarning else Color(0xFF1F2C4C)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (hardwareState.isFlashlightOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                                    contentDescription = null,
                                    tint = if (hardwareState.isFlashlightOn) Color(0xFF1A1200) else Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "হার্ডওয়্যার ফ্ল্যাশলাইট (টর্চ)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color.White
                                )
                                Text(
                                    text = if (hardwareState.isFlashlightOn) "টর্চলাইট অন আছে" else "টর্চলাইট অফ আছে",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (hardwareState.isFlashlightOn) AmberWarning else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = hardwareState.isFlashlightOn,
                            onCheckedChange = { onToggleFlashlight(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF1A1200),
                                checkedTrackColor = AmberWarning
                            ),
                            modifier = Modifier.testTag("flashlight_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "ফোনের সাউন্ড প্রোফাইল:",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(
                            Triple("NORMAL", "নরমাল", Icons.AutoMirrored.Filled.VolumeUp),
                            Triple("VIBRATE", "ভাইব্রেট", Icons.Default.Vibration),
                            Triple("SILENT", "সাইলেন্ট", Icons.Default.VolumeOff)
                        ).forEach { (modeKey, labelBn, icon) ->
                            val selected = hardwareState.ringerMode == modeKey
                            FilterChip(
                                selected = selected,
                                onClick = { onRingerModeChange(modeKey) },
                                label = { Text(text = labelBn) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan.copy(alpha = 0.22f),
                                    selectedLabelColor = ElectricCyan,
                                    selectedLeadingIconColor = ElectricCyan
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Live Audio Volume Sliders Card
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
                        text = "লাইভ সিস্টেম ভলিউম কন্ট্রোলার",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    VolumeControlSliderRow(
                        label = "মিডিয়া ও মিউজিক ভলিউম",
                        percent = hardwareState.musicVolumePercent,
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        accentColor = ElectricCyan,
                        onPercentChange = { onVolumeChange("MUSIC", it) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    VolumeControlSliderRow(
                        label = "রিংটোন ও কল ভলিউম",
                        percent = hardwareState.ringVolumePercent,
                        icon = Icons.Default.NotificationsActive,
                        accentColor = NeuralViolet,
                        onPercentChange = { onVolumeChange("RING", it) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    VolumeControlSliderRow(
                        label = "অ্যালার্ম ভলিউম",
                        percent = hardwareState.alarmVolumePercent,
                        icon = Icons.Default.AccessAlarm,
                        accentColor = PulseRose,
                        onPercentChange = { onVolumeChange("ALARM", it) }
                    )
                }
            }
        }

        // System Settings Quick Panels Grid
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
                        text = "সরাসরি সিস্টেম সেটিংস ও টুলস খুলুন",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val settingsItems = listOf(
                        Triple("WIFI", "ওয়াইফাই প্যানেল", Icons.Default.Wifi),
                        Triple("BLUETOOTH", "ব্লুটুথ", Icons.Default.Bluetooth),
                        Triple("DISPLAY", "ডিসপ্লে ও ব্রাইটনেস", Icons.Default.BrightnessHigh),
                        Triple("SOUND", "সাউন্ড সেটিংস", Icons.AutoMirrored.Filled.VolumeUp),
                        Triple("BATTERY", "ব্যাটারি (${hardwareState.batteryPercent}%)", Icons.Default.BatteryChargingFull),
                        Triple("LOCATION", "লোকেশন / জিপিএস", Icons.Default.LocationOn),
                        Triple("APPS", "সব অ্যাপস", Icons.Default.Apps)
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        settingsItems.forEach { (target, titleBn, icon) ->
                            FilledTonalButton(
                                onClick = { onOpenSettingsTarget(target) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("settings_btn_$target")
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = titleBn,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = onQuickOpenCamera,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = PulseRose,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ক্যামেরা খুলুন", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        // Quick Email & Action Dispatcher Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = null,
                            tint = ElectricCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "কুইক ইমেইল ও অ্যাপ ব্রিজ",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = emailRecipient,
                        onValueChange = { emailRecipient = it },
                        label = { Text("প্রাপকের ইমেইল (Recipient)") },
                        placeholder = { Text("friend@gmail.com") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quick_email_recipient")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = emailSubject,
                        onValueChange = { emailSubject = it },
                        label = { Text("বিষয় (Subject)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quick_email_subject")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = emailBody,
                        onValueChange = { emailBody = it },
                        label = { Text("মেসেজ (Body)") },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quick_email_body")
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = { onQuickSendEmail(emailRecipient, emailSubject, emailBody) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = Color(0xFF04141C)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_send_email_button")
                        ) {
                            Icon(imageVector = Icons.Default.Email, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ইমেইল পাঠান")
                        }

                        OutlinedButton(
                            onClick = { onQuickSetAlarm(7, 0, "Cube Morning Alarm") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.AccessAlarm, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("সকাল ৭টায় অ্যালার্ম")
                        }
                    }
                }
            }
        }

        // Multi-App Smart Routines Section
        item {
            Text(
                text = "ওয়ান-ট্যাপ মাল্টি-অ্যাপ স্মার্ট রুটিন:",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }

        items(routines, key = { it.id }) { routine ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRunRoutine(routine) }
                    .testTag("routine_card_${routine.id}"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF121C34)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = routine.titleBn,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = routine.subtitleBn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${routine.presetSteps.size}টি স্বয়ংক্রিয় ধাপ",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(routine.accentHex)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { onRunRoutine(routine) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(routine.accentHex),
                            contentColor = Color(0xFF06101E)
                        ),
                        modifier = Modifier.testTag("run_routine_${routine.id}")
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("চালান")
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = ElectricCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "সাম্প্রতিক কমান্ড হিস্ট্রি (${history.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                    TextButton(
                        onClick = onClearAllHistory,
                        modifier = Modifier.testTag("clear_history_button")
                    ) {
                        Text("সব মুছুন", color = PulseRose)
                    }
                }
            }

            items(history, key = { it.id }) { item ->
                Surface(
                    color = Color(0xFF0E1629),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onReRunHistoryPrompt(item.userPrompt) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "\"${item.userPrompt}\"",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.actionsSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = ElectricCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { onDeleteHistoryItem(item.id) }) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "মুছুন",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeControlSliderRow(
    label: String,
    percent: Int,
    icon: ImageVector,
    accentColor: Color,
    onPercentChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelLarge,
                color = accentColor
            )
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor
            )
        )
    }
}
