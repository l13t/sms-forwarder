package ua.smsforwarder.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.smsforwarder.R
import ua.smsforwarder.core.Message
import ua.smsforwarder.core.tr

private val Light = lightColorScheme(
    primary=Color(0xFF006B5D), onPrimary=Color.White,
    primaryContainer=Color(0xFFA8F2DD), onPrimaryContainer=Color(0xFF002D25),
    secondary=Color(0xFF48665D), secondaryContainer=Color(0xFFCBEADF),
    background=Color(0xFFF5F8F6), surface=Color(0xFFF5F8F6),
    surfaceContainerLowest=Color.White, surfaceContainerLow=Color(0xFFEEF3EF),
    surfaceContainer=Color(0xFFE7EFE9), surfaceContainerHigh=Color(0xFFE1EAE4),
    onSurface=Color(0xFF18241F), onSurfaceVariant=Color(0xFF52615A),
    outline=Color(0xFF74877D), outlineVariant=Color(0xFFD4DFD7)
)
private val Dark = darkColorScheme(
    primary=Color(0xFF88D8C1), onPrimary=Color(0xFF00382E),
    primaryContainer=Color(0xFF005043), onPrimaryContainer=Color(0xFFA8F2DD),
    secondary=Color(0xFFAFCFC2), secondaryContainer=Color(0xFF304D41),
    background=Color(0xFF0F1713), surface=Color(0xFF0F1713),
    surfaceContainerLowest=Color(0xFF0A120E), surfaceContainerLow=Color(0xFF17211B),
    surfaceContainer=Color(0xFF1B271F), surfaceContainerHigh=Color(0xFF263229),
    onSurface=Color(0xFFE0EBE2), onSurfaceVariant=Color(0xFFB7C9BD),
    outline=Color(0xFF8B9F90), outlineVariant=Color(0xFF3B4B40)
)
private val AppTypography = Typography(
    headlineLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.SemiBold,fontSize=32.sp,lineHeight=38.sp,letterSpacing=(-0.5).sp),
    headlineSmall=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.SemiBold,fontSize=24.sp,lineHeight=30.sp),
    titleLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=22.sp,lineHeight=28.sp),
    titleMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=16.sp,lineHeight=24.sp),
    bodyLarge=TextStyle(fontSize=16.sp,lineHeight=24.sp),
    bodyMedium=TextStyle(fontSize=14.sp,lineHeight=21.sp),
    labelLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=14.sp,lineHeight=20.sp)
)

@Composable fun ForwarderTheme(content: @Composable ()->Unit) {
    MaterialTheme(colorScheme=if(isSystemInDarkTheme()) Dark else Light, typography=AppTypography,
        shapes=Shapes(extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(28.dp)),content=content)
}
@Composable fun AppIcon(@DrawableRes icon: Int, modifier: Modifier=Modifier, description: String?=null, tint: Color=LocalContentColor.current) {
    Icon(painterResource(icon),contentDescription=description,modifier=modifier.size(24.dp),tint=tint)
}
@Composable fun IconTile(@DrawableRes icon: Int, modifier: Modifier=Modifier) {
    Surface(modifier=modifier.size(44.dp),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment=Alignment.Center) { AppIcon(icon,tint=MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}
@Composable fun SectionTitle(title: String) {
    Text(title,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp,bottom=4.dp))
}
@Composable fun StatusBadge(status: String) {
    val (label,icon)=when(status) {
        "sent" -> tr(Message.DELIVERED) to R.drawable.ic_check
        "failed" -> tr(Message.ERROR) to R.drawable.ic_error
        "sending" -> tr(Message.SENDING) to R.drawable.ic_send
        "enabled" -> tr(Message.ENABLED) to R.drawable.ic_check
        "paused" -> tr(Message.PAUSED) to R.drawable.ic_pause
        else -> tr(Message.QUEUED) to R.drawable.ic_clock
    }
    val background=when(status) { "failed" -> MaterialTheme.colorScheme.errorContainer; "sent","enabled" -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surfaceContainerHigh }
    val foreground=when(status) { "failed" -> MaterialTheme.colorScheme.onErrorContainer; "sent","enabled" -> MaterialTheme.colorScheme.onPrimaryContainer; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Surface(color=background,contentColor=foreground,shape=RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
            AppIcon(icon,modifier=Modifier.size(16.dp)); Text(label,style=MaterialTheme.typography.labelMedium)
        }
    }
}
@Composable fun AppCard(modifier: Modifier=Modifier, content: @Composable ColumnScope.()->Unit) {
    Card(modifier=modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
