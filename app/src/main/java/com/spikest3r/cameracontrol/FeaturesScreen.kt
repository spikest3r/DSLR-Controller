package com.spikest3r.cameracontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

@Composable
fun FeaturesScreen(cam: PtpCamera, modifier: Modifier = Modifier, onClose: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("< BACK", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text("PRO FEATURES", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FeatureCard(
                title = "HDR Bracketing (3 Shots)",
                description = "Captures 3 sequential exposures (-2EV, 0EV, +2EV)."
            ) {
                onClose()

                thread {
                    cam.apply {
                        // TODO: ISO VIA USER
                        setProp(0x500F, 400)

                        setProp(0x5010, -2000)
                        shoot()

                        setProp(0x5010, 0)
                        shoot()

                        setProp(0x5010, 2000)
                        shoot()

                        setProp(0x5010, 0)
                    }
                }
            }

            FeatureCard(
                title = "Astrophotography",
                description = "Prepares camera settings for photography (use MANUAL mode on camera)"
            ) {
                onClose()

                thread {
                    cam.apply {
                        setProp(0x500F, 1600) // ISO 1600 (i use this personally, although 3200 is also nice)

                        setProp(0x5010, 0) // EV 0

                        // get widest aperture
                        val ap = getDesc(0x5007)
                        if (ap.rw && ap.allowed.isNotEmpty()) {
                            setProp(0x5007, ap.allowed.min()) // set
                        }

                        // set closest value that doesnt exceed 30"
                        // bulb is skipped!
                        val sh = getDesc(0x500D)
                        if (sh.rw && sh.allowed.isNotEmpty()) {
                            val target = sh.allowed.filter { it <= 300_000L }.maxOrNull()
                            if (target != null) setProp(0x500D, target)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FeatureCard(title: String, description: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, color = Color.Gray, fontSize = 13.sp)
        }
    }
}