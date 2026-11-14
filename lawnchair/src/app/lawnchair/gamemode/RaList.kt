package app.lawnchair.gamemode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R

/** Lista de conquistas do jogo em andamento; o controle rola pelo foco e B fecha (tratado pela tela). */
@Composable
internal fun AchievementListPanel(title: String, entries: List<RaEntry>, hardcore: Boolean) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val earned = entries.count { it.unlocked }
    val points = entries.filter { it.unlocked }.sumOf { it.achievement.points }
    val totalPoints = entries.sumOf { it.achievement.points }
    Box(modifier = Modifier.fillMaxSize().background(GameColors.scrim), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.92f)
                .width(560.dp)
                .background(GameColors.panel, RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = GameColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = stringResource(R.string.game_ra_summary, earned, entries.size, points, totalPoints) +
                    if (hardcore) " · " + stringResource(R.string.game_ra_hardcore_label) else "",
                color = GameColors.textDim,
                fontSize = 12.sp,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                itemsIndexed(entries, key = { _, entry -> entry.achievement.id }) { index, entry ->
                    FocusSurface(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (entry.unlocked) "✓" else "○",
                                color = if (entry.unlocked) GameColors.success else GameColors.textDim,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.achievement.title,
                                    color = if (entry.unlocked) GameColors.text else GameColors.text.copy(alpha = 0.8f),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = entry.achievement.description,
                                    color = GameColors.textDim,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(text = "${entry.achievement.points}", color = GameColors.textDim, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
