package com.spikest3r.cameracontrol

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryItem(val handle: Int, val thumbnail: android.graphics.Bitmap)

@Composable
fun GalleryScreen(cam: PtpCamera, modifier: Modifier = Modifier, onClose: () -> Unit) {
    var galleryItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var isLoadingThumbnails by remember { mutableStateOf(true) }

    var selectedHandle by remember { mutableStateOf<Int?>(null) }
    var fullImageBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isDownloadingFull by remember { mutableStateOf(false) }

    // Back closes the full-image preview first, then falls through to the screen-level handler
    BackHandler(enabled = selectedHandle != null && !isDownloadingFull) { selectedHandle = null }

    LaunchedEffect(cam) {
        withContext(Dispatchers.IO) {
            try {
                val handles = cam.objectHandles()
                val items = mutableListOf<GalleryItem>()

                val recentHandles = handles.takeLast(30).reversed()

                for (rawHandle in recentHandles) {
                    val handleInt = rawHandle.toInt()
                    val thumbResponse = cam.cmd(0x100A, handleInt)
                    val thumbBytes = thumbResponse.data

                    if (thumbBytes != null && thumbBytes.size > 100) {
                        if (thumbBytes[0] == 0xFF.toByte() && thumbBytes[1] == 0xD8.toByte()) {
                            val bitmap = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                            if (bitmap != null) {
                                items.add(GalleryItem(handleInt, bitmap))
                            }
                        }
                    }
                }
                galleryItems = items
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoadingThumbnails = false
            }
        }
    }

    LaunchedEffect(selectedHandle) {
        val handle = selectedHandle ?: return@LaunchedEffect
        isDownloadingFull = true
        fullImageBmp = null

        withContext(Dispatchers.IO) {
            try {
                val fullResponse = cam.cmd(0x1009, handle)
                val fullBytes = fullResponse.data

                if (fullBytes != null && fullBytes.isNotEmpty()) {
                    fullImageBmp = BitmapFactory.decodeByteArray(fullBytes, 0, fullBytes.size)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isDownloadingFull = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF101010))) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
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
                Text("SD CARD", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }

            if (isLoadingThumbnails) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFE53935))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(galleryItems) { item ->
                        Image(
                            bitmap = item.thumbnail.asImageBitmap(),
                            contentDescription = "Thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF222222))
                                .clickable {
                                    selectedHandle = item.handle
                                }
                        )
                    }
                }
            }
        }

        if (selectedHandle != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .clickable {
                        if (!isDownloadingFull) selectedHandle = null
                    }
            ) {
                if (isDownloadingFull) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Pulling full file from camera...", color = Color.LightGray)
                    }
                } else if (fullImageBmp != null) {
                    Image(
                        bitmap = fullImageBmp!!.asImageBitmap(),
                        contentDescription = "Full Image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    Button(
                        onClick = { selectedHandle = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x88222222)),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                    ) {
                        Text("CLOSE", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}