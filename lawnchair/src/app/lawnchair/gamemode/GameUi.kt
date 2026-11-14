package app.lawnchair.gamemode

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Paleta do modo jogos (estilo SteamOS): fundo azul-escuro, destaque azul. */
object GameColors {
    val background = Color(0xFF0B1017)
    val backgroundTop = Color(0xFF16212E)
    val surface = Color(0xFF16212E)
    val surfaceHigh = Color(0xFF223244)
    val accent = Color(0xFF1A9FFF)
    val onAccent = Color(0xFF04121F)
    val text = Color(0xFFE8EEF5)
    val textDim = Color(0xFF9AA8B8)
    val danger = Color(0xFFFF9B9B)
    val success = Color(0xFF7FD1A0)
    val panel = Color(0xEB0B1017)
    val scrim = Color(0x99000000)
    val bar = Color(0x59000000)
}

/** Elemento que recebe foco do controle: destaca com borda azul e responde ao botão A. */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    background: Color = GameColors.surface,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(background)
            .then(if (focused) Modifier.border(3.dp, GameColors.accent, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        content = content,
    )
}

@Composable
fun GameButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val textColor = when {
        !enabled -> GameColors.textDim.copy(alpha = 0.5f)
        primary -> GameColors.onAccent
        danger -> GameColors.danger
        else -> GameColors.text
    }
    FocusSurface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        background = if (primary && enabled) GameColors.accent else GameColors.surface,
        modifier = if (compact) modifier else modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = if (compact) 14.sp else 16.sp,
            maxLines = 1,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 8.dp else 10.dp),
        )
    }
}

/** Capa de jogo: por enquanto um bloco colorido com o nome; capas reais chegam com o RomM. */
@Composable
fun CoverTile(game: LocalGame, modifier: Modifier = Modifier) {
    val palette = listOf(0xFF223244, 0xFF2A2640, 0xFF1F3A35, 0xFF3A2A2A, 0xFF2B3340)
    val color = Color(palette[game.platform.sortOrder % palette.size])
    Box(modifier = modifier.background(color), contentAlignment = Alignment.BottomStart) {
        Text(
            text = game.title,
            color = GameColors.text,
            fontSize = 13.sp,
            maxLines = 3,
            modifier = Modifier.padding(10.dp),
        )
    }
}
