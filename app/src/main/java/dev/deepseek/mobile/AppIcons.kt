package dev.deepseek.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Hand-drawn paths; source and SVG twins: scripts/draw-ui-icons.py. */
object AppIcons {
    private fun icon(name: String, data: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.65f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()

    val RightWorkspace by lazy {
        icon(
            "RightWorkspace",
            "M6.5 4H17.5Q21 4 21 7.5V16.5Q21 20 17.5 20H6.5Q3 20 3 16.5V7.5Q3 4 6.5 4ZM15 4V20",
        )
    }
    val Agents by lazy {
        icon(
            "Agents",
            "M7 11V7Q7 4 10 4H14Q17 4 17 7V11M5 11H19V20H5ZM9 15H9.1M15 15H15.1M9 18H15M12 1V4M2 14H5M19 14H22",
        )
    }
    val Globe by lazy {
        icon(
            "Globe",
            "M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12ZM2.5 12H21.5M12 2C7 6 7 18 12 22M12 2C17 6 17 18 12 22",
        )
    }
    val Browser by lazy {
        icon(
            "Browser",
            "M4 3H20Q22 3 22 5V19Q22 21 20 21H4Q2 21 2 19V5Q2 3 4 3ZM2 8H22M6 5.5H6.1M9 5.5H9.1M10 12L7 15L10 18M14 12L17 15L14 18",
        )
    }
    val Diff by lazy { icon("Diff", "M5 3H14L20 9V21H5ZM14 3V9H20M8 13H15M11.5 10V16M8 18H15") }
    val Paperclip by lazy {
        icon(
            "Paperclip",
            "M8 13.5L15.6 5.9Q18.4 3.1 20.1 5.4Q21.8 7.7 19.3 10.2L9.6 19.9Q6.6 22.9 3.8 20.1Q1 17.3 4.1 14.2L13.5 4.8M6.6 15.7L14.9 7.4",
        )
    }
    val Stop by lazy { icon("Stop", "M6 6H18V18H6Z") }
    val ShieldOff by lazy {
        icon("ShieldOff", "M12 3L20 6V12Q20 17.5 12 21Q4 17.5 4 12V6ZM8.5 9L15.5 16M15.5 9L8.5 16")
    }
    val ShieldCheck by lazy {
        icon("ShieldCheck", "M12 3L20 6V12Q20 17.5 12 21Q4 17.5 4 12V6ZM8 12L11 15L16 9")
    }
    val ShieldAuto by lazy {
        icon(
            "ShieldAuto",
            "M12 3L20 6V12Q20 17.5 12 21Q4 17.5 4 12V6ZM12 8L13.2 11L16 12L13.2 13L12 16L10.8 13L8 12L10.8 11Z",
        )
    }
    val ShieldOpen by lazy {
        icon(
            "ShieldOpen",
            "M12 3L20 6V12Q20 17.5 12 21Q4 17.5 4 12V6ZM9 12V10Q9 7 12 7Q14 7 15 9M8.5 12H15.5V17H8.5Z",
        )
    }
    val TerminalBox by lazy {
        icon(
            "TerminalBox",
            "M5 4H19Q21 4 21 6V12M12 20H5Q3 20 3 18V6Q3 4 5 4ZM7 8L10 11L7 14M12 14H14M18 14L22 16V20L18 22L14 20V16ZM14 16L18 18L22 16M18 18V22",
        )
    }
    val TerminalUser by lazy {
        icon(
            "TerminalUser",
            "M5 4H19Q21 4 21 6V12M12 20H5Q3 20 3 18V6Q3 4 5 4ZM7 8L10 11L7 14M12 14H14M20.5 15.5A2.5 2.5 0 1 1 15.5 15.5A2.5 2.5 0 1 1 20.5 15.5M14 22Q14 18.5 18 18.5Q22 18.5 22 22",
        )
    }
    val TerminalShell by lazy {
        icon(
            "TerminalShell",
            "M5 4H19Q21 4 21 6V12M12 20H5Q3 20 3 18V6Q3 4 5 4ZM7 8L10 11L7 14M12 14H14M19 13L14 18H18L17 22L22 17H18Z",
        )
    }
    val TerminalGuard by lazy {
        icon(
            "TerminalGuard",
            "M5 4H19Q21 4 21 6V12M12 20H5Q3 20 3 18V6Q3 4 5 4ZM7 8L10 11L7 14M12 14H14M18 13L22 15V18Q22 20 18 22Q14 20 14 18V15ZM16 17L18 19L20 16",
        )
    }
    val TerminalRoot by lazy {
        icon(
            "TerminalRoot",
            "M5 4H19Q21 4 21 6V12M12 20H5Q3 20 3 18V6Q3 4 5 4ZM7 8L10 11L7 14M12 14H14M16.5 14L15.5 22M20.5 14L19.5 22M14 17H22M13.5 20H21.5",
        )
    }
    val Terminal by lazy {
        icon(
            "Terminal",
            "M5 4H19Q21 4 21 6V18Q21 20 19 20H5Q3 20 3 18V6Q3 4 5 4ZM7 8L11 12L7 16M13 16H17",
        )
    }
    val Bolt by lazy { icon("Bolt", "M13.5 2.5L4.5 13H10.7L9.5 21.5L19.5 10H13Z") }
    val Reset by lazy { icon("Reset", "M4 8V3M4 8H9M4 8A8 8 0 1 1 4 17") }
    val ChevronRight by lazy { icon("ChevronRight", "M9 5L16 12L9 19") }
    val Add by lazy { icon("Add", "M12 4V20M4 12H20") }
    val ArrowBack by lazy { icon("ArrowBack", "M11 5L4 12L11 19M4 12H20") }
    val ArrowUpward by lazy { icon("ArrowUpward", "M5 11L12 4L19 11M12 4V20") }
    val Menu by lazy { icon("Menu", "M4 6H20M4 12H16M4 18H20") }
    val Close by lazy { icon("Close", "M5 5L19 19M19 5L5 19") }
    val ExpandMore by lazy { icon("ExpandMore", "M5 9L12 16L19 9") }
    val MoreHoriz by lazy {
        icon(
            "MoreHoriz",
            "M5.7 12a.7.7 0 1 1-1.4 0a.7.7 0 1 1 1.4 0M12.7 12a.7.7 0 1 1-1.4 0a.7.7 0 1 1 1.4 0M19.7 12a.7.7 0 1 1-1.4 0a.7.7 0 1 1 1.4 0",
        )
    }
    val Search by lazy {
        icon("Search", "M16.5 16.5L20.5 20.5M18 10.5A7.5 7.5 0 1 1 3 10.5A7.5 7.5 0 1 1 18 10.5")
    }
    val Edit by lazy {
        icon("Edit", "M14 5L19 10M4 16L15 5Q17 3 19 5Q21 7 19 9L8 20L3 21ZM4 16L8 20")
    }
    val Shield by lazy {
        icon("Shield", "M12 3L20 6V12Q20 17.5 12 21Q4 17.5 4 12V6ZM12 8V12.5M12 16H12.05")
    }
    val Hub by lazy {
        icon(
            "Hub",
            "M10.5 8.5L7 14M13.5 8.5L17 14M9 18H15M15 6A3 3 0 1 1 9 6A3 3 0 1 1 15 6M9 17A3 3 0 1 1 3 17A3 3 0 1 1 9 17M21 17A3 3 0 1 1 15 17A3 3 0 1 1 21 17",
        )
    }
    val MicNone by lazy {
        icon(
            "MicNone",
            "M8 6Q8 3 12 3Q16 3 16 6V11Q16 15 12 15Q8 15 8 11ZM4 10V11Q4 18 12 18Q20 18 20 11V10M12 18V21M8 21H16",
        )
    }
    val ContentCopy by lazy { icon("ContentCopy", "M8 8H20V21H8ZM4 16V3H16") }
    val Code by lazy { icon("Code", "M8 5L2 12L8 19M16 5L22 12L16 19") }
    val Download by lazy { icon("Download", "M12 3V15M7 10L12 15L17 10M4 16V21H20V16") }
    val Description by lazy { icon("Description", "M5 3H14L20 9V21H5ZM14 3V9H20M9 13H16M9 17H16") }
    val Image by lazy {
        icon(
            "Image",
            "M5 3H19Q21 3 21 5V19Q21 21 19 21H5Q3 21 3 19V5Q3 3 5 3ZM3 17L8 12L14 18L17 15L21 19M17 7H17.05",
        )
    }
    val FolderOpen by lazy {
        icon(
            "FolderOpen",
            "M3 9V5Q3 3 5 3H10L13 6H19Q21 6 21 8V10M3 9H8L10 11H22L19 21H5ZM5 21L8 12",
        )
    }
    val Checklist by lazy {
        icon("Checklist", "M3 6L5 8L8 4M12 6H21M3 13L5 15L8 11M12 13H21M3 20L5 22L8 18M12 20H21")
    }
    val Key by lazy {
        icon(
            "Key",
            "M13 11L21 19L18 22L15 19M17 15L14 18M14 8A6 6 0 1 1 2 8A6 6 0 1 1 14 8M6 7H6.05",
        )
    }
    val NotificationsNone by lazy {
        icon("NotificationsNone", "M5 17H19L17.5 14V9Q17.5 4 12 4Q6.5 4 6.5 9V14ZM10 21H14M12 2V4")
    }
    val BubbleChart by lazy {
        icon(
            "BubbleChart",
            "M12 15A4.5 4.5 0 1 1 3 15A4.5 4.5 0 1 1 12 15M21 6.5A3.5 3.5 0 1 1 14 6.5A3.5 3.5 0 1 1 21 6.5M21 19A2.5 2.5 0 1 1 16 19A2.5 2.5 0 1 1 21 19",
        )
    }
    val TouchApp by lazy {
        icon(
            "TouchApp",
            "M8 13V6Q8 4 10 4Q12 4 12 6V12L14 11L20 14L18 21H9L4 16Q3 14 5 14L8 16M4 9Q2 2 10 2Q17 2 16 9",
        )
    }
    val Science by lazy {
        icon("Science", "M8 3H16M9 3V10L4 18Q2 21 5 21H19Q22 21 20 18L15 10V3M6 16H18")
    }
    val Settings by lazy {
        icon(
            "Settings",
            "M9 3H15L16 6L19 7L21 10V14L19 17L16 18L15 21H9L8 18L5 17L3 14V10L5 7L8 6ZM15.5 12A3.5 3.5 0 1 1 8.5 12A3.5 3.5 0 1 1 15.5 12",
        )
    }
    val Check by lazy { icon("Check", "M4 12L9 17L20 6") }
    val Chat by lazy {
        icon(
            "Chat",
            "M5 4H19Q21 4 21 6V15Q21 17 19 17H10L5 21V17Q3 17 3 15V6Q3 4 5 4ZM7 9H17M7 13H13",
        )
    }
    val Spark by lazy {
        icon("Spark", "M12 3Q13 10 20 11Q13 12 12 20Q11 12 4 11Q11 10 12 3ZM20 3V7M18 5H22")
    }
    val Pause by lazy { icon("Pause", "M7 4H9V20H7ZM15 4H17V20H15") }
    val Play by lazy { icon("Play", "M7 4L20 12L7 20Z") }
    val Bowl by lazy {
        icon(
            "Bowl",
            "M3 11H21Q20 19 12 19Q4 19 3 11ZM9 21H15M7 7Q5 5 7 3M12 7Q10 5 12 3M17 7Q15 5 17 3",
        )
    }
    val Moon by lazy { icon("Moon", "M19 15Q15 16 11 12Q7 8 9 3Q3 5 3 12Q3 21 12 21Q18 21 21 15Z") }
    val Bow by lazy {
        icon(
            "Bow",
            "M11 10Q6 5 3 6V18Q7 19 11 14M13 10Q18 5 21 6V18Q17 19 13 14M12 9L15 12L12 15L9 12ZM10 15L7 21M14 15L17 21",
        )
    }
    val ArrowOut by lazy { icon("ArrowOut", "M7 17L19 5M8 5H19V16") }
}
