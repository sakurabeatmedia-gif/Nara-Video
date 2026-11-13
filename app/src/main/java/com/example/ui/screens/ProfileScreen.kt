package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.api.ApiConstants
import com.example.data.api.NetworkClient
import com.example.ui.theme.NaraBackground
import com.example.ui.theme.NaraBorder
import com.example.ui.theme.NaraGold
import com.example.ui.theme.NaraGreen
import com.example.ui.theme.NaraPrimary
import com.example.ui.theme.NaraRed
import com.example.ui.theme.NaraSurface
import com.example.ui.theme.NaraSurfaceVariant
import com.example.ui.theme.NaraTextPrimary
import com.example.ui.theme.NaraTextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfileScreen(
    totalFavorites: Int,
    totalHistory: Int,
    hasCustomApiKey: Boolean,
    maskedCustomApiKey: String,
    onSaveCustomApiKey: (String) -> Unit,
    onResetCustomApiKey: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var customKeyInput by remember { mutableStateOf("") }
    var keySaveMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NaraBackground)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(16.dp)
            .testTag("profile_screen")
    ) {
        Text(
            text = "Profil Pengguna",
            color = NaraTextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // User Avatar Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NaraSurface)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(NaraPrimary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = NaraPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Penggemar Dracin",
                        color = NaraTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(NaraGold)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "VIP",
                            color = Color.Black,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Akses Penuh Seluruh Drama",
                    color = NaraTextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Statistics Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NaraSurface)
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "$totalFavorites",
                        color = NaraPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Drama Favorit",
                        color = NaraTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NaraSurface)
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "$totalHistory",
                        color = NaraGold,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Drama Ditonton",
                        color = NaraTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // APIShort Melolo Status Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(NaraSurface)
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudQueue,
                    contentDescription = null,
                    tint = NaraPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Layanan APIShort Melolo",
                    color = NaraTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Base URL: ${ApiConstants.API_BASE_URL}",
                color = NaraTextSecondary,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (hasCustomApiKey) {
                    "Status Kunci API: Kustom aktif ($maskedCustomApiKey)"
                } else {
                    "Status Kunci API: Menggunakan konfigurasi bawaan"
                },
                color = if (hasCustomApiKey) NaraGreen else NaraTextSecondary,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = customKeyInput,
                onValueChange = {
                    customKeyInput = it
                    keySaveMessage = null
                },
                label = {
                    Text(
                        text = "Masukkan API Key Sendiri (Opsional)",
                        fontSize = 11.sp
                    )
                },
                placeholder = {
                    Text(
                        text = "Tempel API Key APIShort Anda",
                        fontSize = 12.sp,
                        color = NaraTextSecondary.copy(alpha = 0.6f)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = NaraPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NaraPrimary,
                    unfocusedBorderColor = NaraBorder,
                    focusedTextColor = NaraTextPrimary,
                    unfocusedTextColor = NaraTextPrimary,
                    focusedLabelColor = NaraPrimary,
                    unfocusedLabelColor = NaraTextSecondary,
                    cursorColor = NaraPrimary
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_api_key_input")
            )

            if (keySaveMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = keySaveMessage ?: "",
                    color = NaraGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val trimmed = customKeyInput.trim()
                        if (trimmed.isNotEmpty()) {
                            onSaveCustomApiKey(trimmed)
                            customKeyInput = ""
                            keySaveMessage = "API Key kustom disimpan dan aktif."
                            testStatus = null
                        }
                    },
                    enabled = customKeyInput.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NaraPrimary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("save_custom_api_key_button")
                ) {
                    Text(text = "Simpan API Key", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                if (hasCustomApiKey) {
                    OutlinedButton(
                        onClick = {
                            onResetCustomApiKey()
                            customKeyInput = ""
                            keySaveMessage = "Kembali ke konfigurasi bawaan."
                            testStatus = null
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("reset_custom_api_key_button")
                    ) {
                        Text(
                            text = "Gunakan Default",
                            color = NaraTextPrimary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            if (testStatus != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NaraSurfaceVariant)
                        .padding(10.dp)
                ) {
                    Text(
                        text = testStatus ?: "",
                        color = if (testStatus?.contains("200") == true) NaraGreen else NaraGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    scope.launch {
                        isTesting = true
                        testStatus = "Menghubungi server..."
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val resp = NetworkClient.meloloService.getHome(offset = 0)
                                when (resp.code()) {
                                    in 200..299 -> "HTTP ${resp.code()} OK: Terhubung ke layanan Melolo"
                                    401, 403 -> "API Key salah / tidak valid (HTTP ${resp.code()})"
                                    502 -> "Server API sedang bermasalah (HTTP 502). Silakan coba lagi beberapa saat lagi."
                                    504 -> "Server API timeout (HTTP 504). Silakan coba lagi."
                                    else -> "HTTP ${resp.code()}: Gagal menghubungi server API"
                                }
                            } catch (e: Exception) {
                                "Koneksi gagal: ${e.localizedMessage ?: "Timeout"}"
                            }
                        }
                        testStatus = result
                        isTesting = false
                    }
                },
                enabled = !isTesting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NaraSurfaceVariant,
                    contentColor = NaraTextPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = NaraPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = "Uji Koneksi Server APIShort", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action Options
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(NaraSurface)
        ) {
            // Clear History
            Button(
                onClick = onClearHistory,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = NaraRed
                ),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = NaraRed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Bersihkan Semua Riwayat Tontonan",
                        color = NaraRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // App Information
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(NaraSurface)
                .padding(16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = NaraTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Tentang NARA",
                        color = NaraTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "NARA v1.0 • Aplikasi Streaming Drama Pendek Modern",
                    color = NaraTextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Didukung oleh APIShort Melolo Realtime Integration",
                    color = NaraTextSecondary.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}
