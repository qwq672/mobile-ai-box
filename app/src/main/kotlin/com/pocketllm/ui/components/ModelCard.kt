package com.pocketllm.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketllm.data.model.ModelInfo

@Composable
fun ModelCard(
    model: ModelInfo,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false
) {
    val container = if (isActive) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow
    val onContainer = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                      else MaterialTheme.colorScheme.onSurface

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 48dp leading block: primaryContainer-tinted icon box
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (isActive) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = null,
                    tint = if (isActive) MaterialTheme.colorScheme.onPrimary
                          else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.size(12.dp))

            // Title + chips
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    model.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                    maxLines = 1
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.params?.let { TagChip(it, isActive) }
                    model.quant?.let { TagChip(it, isActive) }
                    TagChip(formatSize(model.sizeMb), isActive)
                }
            }

            Spacer(Modifier.size(8.dp))

            // Trailing slot: active chip / loading spinner / empty
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                isActive -> AssistChip(
                    onClick = onClick,
                    label = {
                        Text(
                            "已激活",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        labelColor = MaterialTheme.colorScheme.onPrimary,
                        leadingIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                else -> Spacer(Modifier.size(56.dp))
            }
        }
    }
}

@Composable
private fun TagChip(text: String, isActive: Boolean = false) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        shape = MaterialTheme.shapes.small,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.surface
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
            leadingIconContentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                                      else MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

private fun formatSize(mb: Long): String =
    if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "$mb MB"
