package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DramaProvider

@Composable
fun ProviderLabel(
    provider: DramaProvider,
    modifier: Modifier = Modifier
) {
    val providerName = when (provider) {
        DramaProvider.MELOLO -> "MELOLO"
        DramaProvider.DRAMABOX -> "DRAMABOX"
        DramaProvider.DRAMAWAVE -> "DRAMAWAVE"
        DramaProvider.REELSHORT -> "REELSHORT"
        DramaProvider.DRAMABITE -> "DRAMABITE"
        DramaProvider.GOODSHORT -> "GOODSHORT"
    }

    Text(
        text = providerName,
        modifier = modifier
            .background(
                color = Color(0xFF29243D),
                shape = RoundedCornerShape(5.dp)
            )
            .padding(
                horizontal = 9.dp,
                vertical = 5.dp
            ),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.4.sp
    )
}
