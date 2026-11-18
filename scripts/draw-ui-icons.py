"""Hand-authored geometry; emit identical SVG and Compose paths. No icon libraries."""

from pathlib import Path
import json
import math

root = Path(__file__).resolve().parents[1]


def settings_gear():
    # Eight evenly spaced teeth with round stroke joins and a circular axle.
    points = []
    for tooth in range(8):
        center = tooth * 45 - 90
        for offset, radius in [
            (-22.5, 7.2),
            (-12, 7.2),
            (-8, 9.6),
            (8, 9.6),
            (12, 7.2),
            (22.5, 7.2),
        ]:
            angle = math.radians(center + offset)
            points.append((12 + radius * math.cos(angle), 12 + radius * math.sin(angle)))
    outline = "M" + "L".join(f"{x:.3f} {y:.3f}" for x, y in points) + "Z"
    return outline + "M15.1 12A3.1 3.1 0 1 1 8.9 12A3.1 3.1 0 1 1 15.1 12Z"


# 24-unit canvas, 3-unit optical margin, round 1.65-unit stroke.
paths = {
    "Agents": "M7 11V7Q7 4 10 4H14Q17 4 17 7V11M5 11H19V20H5ZM9 15H9.1M15 15H15.1M9 18H15M12 1V4M2 14H5M19 14H22",
    "RightWorkspace": "M6.5 4H17.5Q21 4 21 7.5V16.5Q21 20 17.5 20H6.5Q3 20 3 16.5V7.5Q3 4 6.5 4ZM15 4V4Q15 4 15 4H15Q15 4 15 4V20Q15 20 15 20",
    "WorkspaceTabs": "M6 7H14Q17 7 17 10V18Q17 21 14 21H6Q3 21 3 18V10Q3 7 6 7ZM7 4.8V4.8Q7 3 9.5 3H18Q21 3 21 6V14Q21 16.5 19 17",
    "RemoteRelay": "M3 10V8.5Q3 6 5.5 6H19M15.5 2.5L19 6L15.5 9.5M21 14V15.5Q21 18 18.5 18H5M8.5 14.5L5 18L8.5 21.5",
    "RemoteComputer": "M4.5 5H13M19.5 10V15Q19.5 17.5 17 17.5H5Q2.5 17.5 2.5 15V7.5Q2.5 5 4.5 5M11 17.5V21M7 21H15M14 10L21 3M16.5 3H21V7.5",
    "Globe": "M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12ZM2.5 12H21.5M12 2C7 6 7 18 12 22M12 2C17 6 17 18 12 22",
    "Browser": "M4 3H20Q22 3 22 5V19Q22 21 20 21H4Q2 21 2 19V5Q2 3 4 3ZM2 8H22M6 5.5H6.1M9 5.5H9.1M10 12L7 15L10 18M14 12L17 15L14 18",
    "Diff": "M5 3H14L20 9V21H5ZM14 3V9H20M8 13H15M11.5 10V16M8 18H15",
    "Paperclip": "M8 13.5L15.6 5.9Q18.4 3.1 20.1 5.4Q21.8 7.7 19.3 10.2L9.6 19.9Q6.6 22.9 3.8 20.1Q1 17.3 4.1 14.2L13.5 4.8M6.6 15.7L14.9 7.4",
    "Stop": "M6 6H18V18H6Z",
    "Markdown": "M3.5 18V6L8 12L12.5 6V18M18 6V18M14.5 14.5L18 18L21.5 14.5",
    "ShieldOff": "M12 3L20 6V12Q20 17.5 12 21Q4 17.5 4 12V6ZM8.5 9L15.5 16M15.5 9L8.5 16",
    "ShieldCheck": "M12 3L20 6V12Q20 17.5 12 21Q4 17.5 4 12V6ZM8 12L11 15L16 9",
    "ShieldAuto": "M12 3L20 6V12Q20 17.5 12 21Q4 17.5 4 12V6ZM12 8L13.2 11L16 12L13.2 13L12 16L10.8 13L8 12L10.8 11Z",
    "ShieldOpen": "M12 3L20 6V12Q20 17.5 12 21Q4 17.5 4 12V6ZM9 12V10Q9 7 12 7Q14 7 15 9M8.5 12H15.5V17H8.5Z",
    "TerminalBox": "M5 4H19Q21 4 21 6V12M12 20H5Q3 20 3 18V6Q3 4 5 4M7 8L10 11L7 14M12 14H14M18 14L22 16V20L18 22L14 20V16ZM14 16L18 18L22 16M18 18V22",
    "TerminalUser": "M5 4H19Q21 4 21 6V12M12 20H5Q3 20 3 18V6Q3 4 5 4M7 8L10 11L7 14M12 14H14M20.5 15.5A2.5 2.5 0 1 1 15.5 15.5A2.5 2.5 0 1 1 20.5 15.5M14 22Q14 18.5 18 18.5Q22 18.5 22 22",
    "TerminalShell": "M5 4H19Q21 4 21 6V12M12 20H5Q3 20 3 18V6Q3 4 5 4M7 8L10 11L7 14M12 14H14M19 13L14 18H18L17 22L22 17H18Z",
    "TerminalGuard": "M5 4H19Q21 4 21 6V12M12 20H5Q3 20 3 18V6Q3 4 5 4M7 8L10 11L7 14M12 14H14M18 13L22 15V18Q22 20 18 22Q14 20 14 18V15ZM16 17L18 19L20 16",
    "TerminalRoot": "M5 4H19Q21 4 21 6V12M12 20H5Q3 20 3 18V6Q3 4 5 4M7 8L10 11L7 14M12 14H14M16.5 14L15.5 22M20.5 14L19.5 22M14 17H22M13.5 20H21.5",
    "Terminal": "M5 4H19Q21 4 21 6V18Q21 20 19 20H5Q3 20 3 18V6Q3 4 5 4ZM7 8L11 12L7 16M13 16H17",
    "Bolt": "M13.5 2.5L4.5 13H10.7L9.5 21.5L19.5 10H13Z",
    "Reset": "M4 8V3M4 8H9M4 8A8 8 0 1 1 4 17",
    "ChevronRight": "M9 5L16 12L9 19",
    "Add": "M12 4V20M4 12H20",
    "ArrowBack": "M11 5L4 12L11 19M4 12H20",
    "ArrowUpward": "M5 11L12 4L19 11M12 4V20",
    "Menu": "M4 6H20M4 12H16M4 18H20",
    "Close": "M5 5L19 19M19 5L5 19",
    "ExpandMore": "M5 9L12 16L19 9",
    "MoreHoriz": "M5.7 12a.7.7 0 1 1-1.4 0a.7.7 0 1 1 1.4 0M12.7 12a.7.7 0 1 1-1.4 0a.7.7 0 1 1 1.4 0M19.7 12a.7.7 0 1 1-1.4 0a.7.7 0 1 1 1.4 0",
    "Search": "M16.5 16.5L20.5 20.5M18 10.5A7.5 7.5 0 1 1 3 10.5A7.5 7.5 0 1 1 18 10.5",
    "Edit": "M14 5L19 10M4 16L15 5Q17 3 19 5Q21 7 19 9L8 20L3 21ZM4 16L8 20",
    "Shield": "M12 3L20 6V12Q20 17.5 12 21Q4 17.5 4 12V6ZM12 8V12.5M12 16H12.05",
    "Hub": "M10.5 8.5L7 14M13.5 8.5L17 14M9 18H15M15 6A3 3 0 1 1 9 6A3 3 0 1 1 15 6M9 17A3 3 0 1 1 3 17A3 3 0 1 1 9 17M21 17A3 3 0 1 1 15 17A3 3 0 1 1 21 17",
    "MicNone": "M8 6Q8 3 12 3Q16 3 16 6V11Q16 15 12 15Q8 15 8 11ZM4 10V11Q4 18 12 18Q20 18 20 11V10M12 18V21M8 21H16",
    "ContentCopy": "M8 8H20V21H8ZM4 16V3H16",
    "Code": "M8 5L2 12L8 19M16 5L22 12L16 19",
    "Download": "M12 3V15M7 10L12 15L17 10M4 16V21H20V16",
    "Description": "M5 3H14L20 9V21H5ZM14 3V9H20M9 13H16M9 17H16",
    "Image": "M5 3H19Q21 3 21 5V19Q21 21 19 21H5Q3 21 3 19V5Q3 3 5 3ZM3 17L8 12L14 18L17 15L21 19M17 7H17.05",
    "FolderOpen": "M3 9V5Q3 3 5 3H10L13 6H19Q21 6 21 8V10M3 9H8L10 11H22L19 21H5ZM5 21L8 12",
    "Checklist": "M3 6L5 8L8 4M12 6H21M3 13L5 15L8 11M12 13H21M3 20L5 22L8 18M12 20H21",
    "Key": "M13 11L21 19L18 22L15 19M17 15L14 18M14 8A6 6 0 1 1 2 8A6 6 0 1 1 14 8M6 7H6.05",
    "NotificationsNone": "M5 17H19L17.5 14V9Q17.5 4 12 4Q6.5 4 6.5 9V14ZM10 21H14M12 2V4",
    "BubbleChart": "M12 15A4.5 4.5 0 1 1 3 15A4.5 4.5 0 1 1 12 15M21 6.5A3.5 3.5 0 1 1 14 6.5A3.5 3.5 0 1 1 21 6.5M21 19A2.5 2.5 0 1 1 16 19A2.5 2.5 0 1 1 21 19",
    "TouchApp": "M8 13V6Q8 4 10 4Q12 4 12 6V12L14 11L20 14L18 21H9L4 16Q3 14 5 14L8 16M4 9Q2 2 10 2Q17 2 16 9",
    "Science": "M8 3H16M9 3V10L4 18Q2 21 5 21H19Q22 21 20 18L15 10V3M6 16H18",
    "Settings": settings_gear(),
    "Check": "M4 12L9 17L20 6",
    "Chat": "M5 4H19Q21 4 21 6V15Q21 17 19 17H10L5 21V17Q3 17 3 15V6Q3 4 5 4ZM7 9H17M7 13H13",
    "Spark": "M12 3Q13 10 20 11Q13 12 12 20Q11 12 4 11Q11 10 12 3ZM20 3V7M18 5H22",
    "Pause": "M7 4H9V20H7ZM15 4H17V20H15",
    "Play": "M7 4L20 12L7 20Z",
    "Bowl": "M3 11H21Q20 19 12 19Q4 19 3 11ZM9 21H15M7 7Q5 5 7 3M12 7Q10 5 12 3M17 7Q15 5 17 3",
    "Moon": "M19 15Q15 16 11 12Q7 8 9 3Q3 5 3 12Q3 21 12 21Q18 21 21 15Z",
    "Bow": "M11 10Q6 5 3 6V18Q7 19 11 14M13 10Q18 5 21 6V18Q17 19 13 14M12 9L15 12L12 15L9 12ZM10 15L7 21M14 15L17 21",
    "ArrowOut": "M7 17L19 5M8 5H19V16",
}
asset = root / "app/src/main/assets/icons"
asset.mkdir(parents=True, exist_ok=True)
for name, data in paths.items():
    (asset / f"{name}.svg").write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round"><path d="{data}"/></svg>\n',
        encoding="utf-8",
    )
header = """package dev.deepseek.mobile

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

"""


def kotlin_property(name: str, data: str) -> str:
    if name in ("RightWorkspace", "WorkspaceTabs"):
        key = name[0].lower() + name[1:] + "Path"
        return (
            f"    internal const val {key} =\n        {json.dumps(data)}\n"
            f'    val {name} by lazy {{ icon("{name}", {key}) }}\n'
        )
    call = f'icon("{name}", {json.dumps(data)})'
    single_line = f"    val {name} by lazy {{ {call} }}"
    if len(single_line) <= 100:
        return single_line + "\n"
    if len("        " + call) <= 100:
        body = "        " + call + "\n"
    else:
        body = f'        icon(\n            "{name}",\n            {json.dumps(data)},\n        )\n'
    return f"    val {name} by lazy {{\n" + body + "    }\n"


(root / "app/src/main/java/dev/deepseek/mobile/AppIcons.kt").write_text(
    header + "".join(kotlin_property(name, data) for name, data in paths.items()) + "}\n",
    encoding="utf-8",
)
print(
    f"{len(paths)} hand-drawn icons emitted, all using the same 24-unit canvas and 1.65-unit stroke."
)
