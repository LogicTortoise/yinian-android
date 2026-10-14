package com.yinian.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Soft surface card used for idea/recommendation rows.
 * Has a thin bottom edge, a layered shadow and a subtle inner highlight from the top-left,
 * echoing the v4 spec.
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    edgeThickness: Dp = 3.dp,
    edgeColor: Color = MaterialTheme.colorScheme.outline,
    background: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(17.dp, 18.dp, 17.dp, 12.dp),
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = Color(0x1A264839),
                spotColor = Color(0x331A264839)
            )
            .background(background, shape)
            .padding(bottom = edgeThickness)
            .background(edgeColor, shape)
            .padding(0.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(0.dp)
                .background(background, RoundedCornerShape(cornerRadius - 2.dp))
                .padding(contentPadding)
        ) {
            content()
        }
    }
}

@Composable
fun PillBadge(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    description: String? = null
) {
    val cd = description ?: text
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = cd }
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = contentColor)
    }
}

@Composable
fun GrooveTrack(
    selected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        content()
    }
}
