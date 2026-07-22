package com.hereliesaz.sirmatchalot.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hereliesaz.sirmatchalot.ui.SirMatchALotViewModel
import com.hereliesaz.sirmatchalot.ui.LibraryScreen
import com.hereliesaz.sirmatchalot.ui.DecksScreen
import com.hereliesaz.sirmatchalot.ui.ControlsScreen
import com.hereliesaz.sirmatchalot.ui.PerformanceScreen

enum class DjTab {
    LIBRARY, DECKS, CONTROLS, PERFORMANCE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: SirMatchALotViewModel = viewModel()
) {
    // Waveform Circle Platter is the Unified Main Screen
    var currentTab by remember { mutableStateOf(DjTab.CONTROLS) }
    val crossfader by viewModel.crossfader.collectAsState()
    val isWsConnected by viewModel.isWsConnected.collectAsState()
    val roomCode by viewModel.roomCode.collectAsState()

    var showSyncDialog by remember { mutableStateOf(false) }
    var inputIp by remember { mutableStateOf("192.168.1.100") }
    var inputCode by remember { mutableStateOf("ROOM") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SIR MATCH-A-LOT",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            letterSpacing = 1.sp
                        )

                        // Wireless Sync Link Indicator
                        Button(
                            onClick = { showSyncDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isWsConnected) Color(0xFF10B981) else Color(0xFFEF4444)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Icon(
                                imageVector = if (isWsConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = "Sync Info",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isWsConnected) "SYNC LINKED ($roomCode)" else "SYNC OFFLINE",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF09090B))
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(Color(0xFF09090B))) {
                // Global DJ Crossfader Slider (shared above tabs)
                Divider(color = Color(0xFF18181B))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("DECK A", color = Color(0xFF06B6D4), fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.width(45.dp))
                    Slider(
                        value = crossfader.toFloat(),
                        onValueChange = { viewModel.setCrossfaderValue(it.toInt()) },
                        valueRange = -100f..100f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFF27272A),
                            inactiveTrackColor = Color(0xFF27272A)
                        )
                    )
                    Text("DECK B", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.width(45.dp))
                }

                NavigationBar(
                    containerColor = Color(0xFF18181B),
                    tonalElevation = 8.dp,
                    modifier = Modifier.height(64.dp)
                ) {
                    NavigationBarItem(
                        selected = currentTab == DjTab.CONTROLS,
                        onClick = { currentTab = DjTab.CONTROLS },
                        label = { Text("Platter Main", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.Refresh, contentDescription = "Platter Main") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Cyan,
                            selectedTextColor = Color.Cyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color.Transparent
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == DjTab.LIBRARY,
                        onClick = { currentTab = DjTab.LIBRARY },
                        label = { Text("Library", fontSize = 10.sp) },
                        icon = { Icon(Icons.Default.List, contentDescription = "Library") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Cyan,
                            selectedTextColor = Color.Cyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color.Transparent
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == DjTab.DECKS,
                        onClick = { currentTab = DjTab.DECKS },
                        label = { Text("Decks", fontSize = 10.sp) },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Decks") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Cyan,
                            selectedTextColor = Color.Cyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color.Transparent
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == DjTab.PERFORMANCE,
                        onClick = { currentTab = DjTab.PERFORMANCE },
                        label = { Text("Sampler", fontSize = 10.sp) },
                        icon = { Icon(Icons.Default.Star, contentDescription = "Sampler") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Cyan,
                            selectedTextColor = Color.Cyan,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF09090B))
        ) {
            when (currentTab) {
                DjTab.LIBRARY -> LibraryScreen(viewModel = viewModel)
                DjTab.DECKS -> DecksScreen(viewModel = viewModel)
                DjTab.CONTROLS -> ControlsScreen(viewModel = viewModel)
                DjTab.PERFORMANCE -> PerformanceScreen(viewModel = viewModel)
            }
        }
    }

    // Sync Dialog
    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            containerColor = Color(0xFF18181B),
            title = { Text("Link Multi-Device Session", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Auto Connect will search your local Wi-Fi router for the server room broadcast.", color = Color.LightGray, fontSize = 11.sp)
                    
                    Button(
                        onClick = {
                            viewModel.startAutoDiscovery()
                            showSyncDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ONE-CLICK AUTO CONNECT", color = Color.White, fontWeight = FontWeight.Black)
                    }

                    Divider(color = Color(0xFF27272A))

                    Text("Or enter connection parameters manually:", color = Color.LightGray, fontSize = 11.sp)
                    OutlinedTextField(
                        value = inputIp,
                        onValueChange = { inputIp = it },
                        label = { Text("Server Base IP", color = Color.Gray, fontSize = 10.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Cyan,
                            unfocusedBorderColor = Color(0xFF27272A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = inputCode,
                        onValueChange = { inputCode = it },
                        label = { Text("Room Code", color = Color.Gray, fontSize = 10.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Cyan,
                            unfocusedBorderColor = Color(0xFF27272A),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanIp = inputIp.trim()
                        val wsUrl = "ws://$cleanIp:3000/ws"
                        viewModel.connectToRoom(wsUrl, inputCode.trim())
                        showSyncDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)
                ) {
                    Text("Connect Manual", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSyncDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}
