package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NaraBackground
import com.example.ui.theme.NaraBorder
import com.example.ui.theme.NaraPrimary
import com.example.ui.theme.NaraSurface
import com.example.ui.theme.NaraSurfaceVariant
import com.example.ui.theme.NaraTextPrimary
import com.example.ui.theme.NaraTextSecondary
import com.example.ui.viewmodel.NaraTab

@Composable
fun NaraBottomNavigation(
    selectedTab: NaraTab,
    onTabSelected: (NaraTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NaraBackground.copy(alpha = 0.98f))
            .navigationBarsPadding()
    ) {
        HorizontalDivider(
            thickness = 0.8.dp,
            color = NaraBorder.copy(alpha = 0.4f)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NaraTab.values().forEach { tab ->
                val isSelected = selectedTab == tab
                val icon = when (tab) {
                    NaraTab.HOME -> if (isSelected) Icons.Filled.Home else Icons.Outlined.Home
                    NaraTab.EXPLORE -> if (isSelected) Icons.Filled.Explore else Icons.Outlined.Explore
                    NaraTab.HISTORY -> if (isSelected) Icons.Filled.History else Icons.Outlined.History
                    NaraTab.FAVORITES -> if (isSelected) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder
                    NaraTab.PROFILE -> if (isSelected) Icons.Filled.Person else Icons.Outlined.Person
                }

                val tint by animateColorAsState(
                    targetValue = if (isSelected) NaraPrimary else NaraTextSecondary,
                    label = "nav_color_${tab.name}"
                )

                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTabSelected(tab) }
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("nav_tab_${tab.name.lowercase()}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) NaraPrimary.copy(alpha = 0.16f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = tab.label,
                        color = tint,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
