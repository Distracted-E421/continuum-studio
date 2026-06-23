package com.example.continuumstudio.ui.cognix

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.viewmodel.CognixViewModel
import com.example.continuumstudio.viewmodel.PatternCategoryData
import com.example.continuumstudio.viewmodel.PatternData

data class PatternCategory(
    val name: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val patterns: List<PatternInsight>
)

data class PatternInsight(
    val id: String,
    val summary: String,
    val detail: String,
    val confidence: Float,
    val actionable: Boolean = false,
    val suggestedAction: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CognixPatternsScreen(
    viewModel: CognixViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Convert backend data to UI models with icons
    val categories = remember(uiState.patternCategories) {
        uiState.patternCategories.map { cat ->
            PatternCategory(
                name = cat.name,
                icon = getCategoryIcon(cat.name),
                patterns = cat.patterns.map { p ->
                    PatternInsight(
                        id = p.id,
                        summary = p.description.take(60) + if (p.description.length > 60) "..." else "",
                        detail = p.description,
                        confidence = p.confidence,
                        actionable = p.patternType.startsWith("adhd_"),
                        suggestedAction = getSuggestedAction(p)
                    )
                }
            )
        }.ifEmpty {
            // Fallback placeholder when no data
            listOf(
                PatternCategory(
                    name = "Getting Started",
                    icon = Icons.Default.Info,
                    patterns = listOf(
                        PatternInsight(
                            id = "placeholder",
                            summary = "No patterns detected yet",
                            detail = "Import your Google Takeout data or use Cognix for a few days to see behavioral patterns",
                            confidence = 0f
                        )
                    )
                )
            )
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Patterns & Insights") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.patternsLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { viewModel.refreshPatterns() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SummaryCard(
                    totalPatterns = uiState.patternSummary?.totalPatterns ?: categories.sumOf { it.patterns.size },
                    daysAnalyzed = uiState.patternSummary?.daysAnalyzed ?: 30,
                    categoriesCount = categories.size
                )
            }
            
            categories.forEach { category ->
                item {
                    CategoryHeader(
                        name = category.name,
                        icon = category.icon
                    )
                }
                
                items(category.patterns) { pattern ->
                    PatternCard(pattern = pattern)
                }
            }
        }
    }
}

private fun getCategoryIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when {
        name.contains("ADHD", ignoreCase = true) -> Icons.Default.Psychology
        name.contains("AI", ignoreCase = true) -> Icons.Default.SmartToy
        name.contains("Search", ignoreCase = true) -> Icons.Default.Search
        name.contains("Location", ignoreCase = true) -> Icons.Default.LocationOn
        name.contains("Productivity", ignoreCase = true) -> Icons.Default.TrendingUp
        name.contains("Focus", ignoreCase = true) -> Icons.Default.CenterFocusStrong
        name.contains("Distraction", ignoreCase = true) -> Icons.Default.NotificationsActive
        name.contains("Context", ignoreCase = true) -> Icons.Default.SwapHoriz
        else -> Icons.Default.Insights
    }
}

private fun getSuggestedAction(pattern: PatternData): String? {
    return when {
        pattern.patternType == "adhd_distraction" -> "Consider using app blockers during focus time"
        pattern.patternType == "adhd_hyperfocus" -> "Set timers to take breaks during deep work"
        pattern.patternType == "adhd_impulsivity" -> "Try a 30-second pause before new searches"
        pattern.patternType == "adhd_sleep_irregularity" -> "Consider a consistent bedtime routine"
        pattern.patternType == "productivity_window" -> "Schedule important tasks during this window"
        pattern.patternType.contains("gemini") -> "Review your AI usage patterns"
        else -> null
    }
}

@Composable
private fun SummaryCard(
    totalPatterns: Int,
    daysAnalyzed: Int,
    categoriesCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Your Insights Summary",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(value = daysAnalyzed.toString(), label = "Days analyzed")
                StatItem(value = totalPatterns.toString(), label = "Patterns found")
                StatItem(value = categoriesCount.toString(), label = "Categories")
            }
        }
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun CategoryHeader(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PatternCard(pattern: PatternInsight) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pattern.summary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                
                // Confidence indicator
                LinearProgressIndicator(
                    progress = { pattern.confidence },
                    modifier = Modifier
                        .width(50.dp)
                        .height(4.dp),
                    color = when {
                        pattern.confidence > 0.8f -> MaterialTheme.colorScheme.primary
                        pattern.confidence > 0.6f -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.tertiary
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = pattern.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (pattern.actionable && pattern.suggestedAction != null) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pattern.suggestedAction,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}
