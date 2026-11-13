package id.webdev.studio.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Ikon vektor sendiri (material-icons-extended terlalu besar untuk APK). Semua bentuk sederhana, 24x24. */
object AppIcons {
    private fun build(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = block)
        }.build()

    private fun PathBuilder.rect(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1); lineTo(x2, y1); lineTo(x2, y2); lineTo(x1, y2); close()
    }

    val Stop: ImageVector by lazy { build("Stop") { rect(6f, 6f, 18f, 18f) } }

    val Folder: ImageVector by lazy {
        build("Folder") { moveTo(3f, 5f); lineTo(10f, 5f); lineTo(12f, 7.5f); lineTo(21f, 7.5f); lineTo(21f, 19f); lineTo(3f, 19f); close() }
    }

    val Upload: ImageVector by lazy {
        build("Upload") {
            moveTo(12f, 3f); lineTo(18.5f, 9.5f); lineTo(5.5f, 9.5f); close()
            rect(10.5f, 9f, 13.5f, 16f)
            rect(4f, 18f, 20f, 20.5f)
        }
    }

    val Download: ImageVector by lazy {
        build("Download") {
            rect(10.5f, 3f, 13.5f, 10f)
            moveTo(12f, 17f); lineTo(5.5f, 10.5f); lineTo(18.5f, 10.5f); close()
            rect(4f, 18f, 20f, 20.5f)
        }
    }

    /** Jendela/pratinjau. */
    val Preview: ImageVector by lazy {
        build("Preview") {
            rect(3f, 4f, 21f, 8f); rect(3f, 8f, 5f, 20f); rect(19f, 8f, 21f, 20f); rect(5f, 18f, 19f, 20f)
        }
    }

    val Fullscreen: ImageVector by lazy {
        build("Fullscreen") {
            rect(3f, 3f, 9f, 5f); rect(3f, 5f, 5f, 9f)
            rect(15f, 3f, 21f, 5f); rect(19f, 5f, 21f, 9f)
            rect(3f, 19f, 9f, 21f); rect(3f, 15f, 5f, 19f)
            rect(15f, 19f, 21f, 21f); rect(19f, 15f, 21f, 19f)
        }
    }

    /** Ukuran layar: dua bingkai (ponsel di dalam tablet). */
    val Devices: ImageVector by lazy {
        build("Devices") {
            rect(2f, 4f, 22f, 6f); rect(2f, 6f, 4f, 18f); rect(20f, 6f, 22f, 18f); rect(2f, 16f, 22f, 18f)
            rect(9f, 9f, 15f, 20f)
        }
    }

    /** DevTools: tanda "terminal" > _ */
    val Terminal: ImageVector by lazy {
        build("Terminal") {
            moveTo(4f, 6f); lineTo(6f, 4f); lineTo(14f, 12f); lineTo(6f, 20f); lineTo(4f, 18f); lineTo(10f, 12f); close()
            rect(14f, 17f, 21f, 19.5f)
        }
    }

    val Save: ImageVector by lazy {
        build("Save") { rect(4f, 4f, 20f, 8f); rect(4f, 8f, 7f, 20f); rect(17f, 8f, 20f, 20f); rect(7f, 17f, 17f, 20f); rect(9f, 10f, 15f, 14f) }
    }

    val Undo: ImageVector by lazy {
        build("Undo") {
            moveTo(3f, 10f); lineTo(9f, 4f); lineTo(9f, 16f); close()
            rect(9f, 8f, 17f, 12f); rect(15f, 8f, 19f, 18f); rect(9f, 16f, 19f, 18f)
        }
    }

    val Redo: ImageVector by lazy {
        build("Redo") {
            moveTo(21f, 10f); lineTo(15f, 4f); lineTo(15f, 16f); close()
            rect(7f, 8f, 15f, 12f); rect(5f, 8f, 9f, 18f); rect(5f, 16f, 15f, 18f)
        }
    }

    /** Berkas teks / "file baru". */
    val File: ImageVector by lazy {
        build("File") { moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(6f, 21f); close() }
    }
}
