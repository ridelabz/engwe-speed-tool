package io.github.ridelabz.engwespeedtool.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Rounded corners: cards use `large` (20 dp), sheets `extraLarge` (24 dp). */
val AppShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

/** The spacing scale. Layouts use only these steps. */
object Spacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Layout limits. */
object Layout {
    /** The widest a screen's content gets (tablets, landscape), centred: lines stay readable. */
    val maxContentWidth: Dp = 600.dp
}

/** Touch targets: at least 48 dp everywhere, and 56 dp for the speed choices. */
object TouchTargets {
    val minimum: Dp = 48.dp
    val speedChoice: Dp = 56.dp
}
