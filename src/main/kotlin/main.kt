import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import kotlin.io.path.Path

private const val ESC = "\u001B"
private const val ASCII_CHARS = " .-+*wGHM#&%"

private val HIDE_CURSOR_AND_CLEAR = "$ESC[?25l$ESC[2J".toByteArray(Charsets.US_ASCII)
private val SHOW_CURSOR = "$ESC[?25h\n".toByteArray(Charsets.US_ASCII)
private val SYNC_BEGIN = "$ESC[?2026h".toByteArray(Charsets.US_ASCII)
private val SYNC_END = "$ESC[?2026l".toByteArray(Charsets.US_ASCII)

private class Frame(val full: ByteArray, val diff: ByteArray)

fun main() {
    val movieFileName = "movie.mp4"
    val audioFilePath = "audio.wav"
    val genFolderName = "gen"
    val fps = 30

    val (columns, rows) = detectTerminalSize() ?: (120 to 41)
    val width = columns
    val height = (rows - 1).coerceAtLeast(1)

    recreateFolder(genFolderName)
    convertImageFile(movieFileName, width, height, genFolderName, fps)
    println("Image Gen Done")
    val fileCount = getGeneratedImageFileCount(genFolderName)
    val frames = generateAA(fileCount, genFolderName)
    File(audioFilePath).deleteOnExit()
    convertAudioFile(movieFileName, audioFilePath)
    println("Audio Gen Done")
    println("Convert Done")

    Runtime.getRuntime().addShutdownHook(Thread {
        System.out.print("$ESC[?25h")
        System.out.flush()
    })

    val output = BufferedOutputStream(FileOutputStream(FileDescriptor.out), 256 * 1024)
    output.write(HIDE_CURSOR_AND_CLEAR)
    output.flush()

    playBadApple(audioFilePath)

    val startNanos = System.nanoTime()
    var lastDrawnIndex = 0
    for (index in 1..fileCount) {
        val targetNanos = startNanos + index * 1_000_000_000L / fps
        val waitNanos = targetNanos - System.nanoTime()
        // 描画が追いつかないときはフレームを捨てて音声との同期を保つ
        if (waitNanos < 0) continue
        Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
        val frame = frames[index] ?: continue
        output.write(SYNC_BEGIN)
        output.write(if (lastDrawnIndex == index - 1) frame.diff else frame.full)
        output.write(SYNC_END)
        output.flush()
        lastDrawnIndex = index
    }
    output.write(SHOW_CURSOR)
    output.flush()
}

private fun detectTerminalSize(): Pair<Int, Int>? = try {
    val process = ProcessBuilder("sh", "-c", "stty size < /dev/tty").start()
    val text = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && text.isNotEmpty()) {
        val (rows, columns) = text.split(' ').map(String::toInt)
        columns to rows
    } else {
        null
    }
} catch (e: Exception) {
    null
}

private fun recreateFolder(folderName: String) {
    val folder = Path(folderName)
    if (Files.exists(folder)) {
        Files.list(folder).use { entries -> entries.forEach(Files::delete) }
        Files.delete(folder)
    }
    Files.createDirectory(folder)
}

private fun generateAA(fileCount: Int, genFolderName: String): Array<Frame?> {
    val frameLines = arrayOfNulls<Array<String>>(fileCount + 1)
    (1..fileCount).toList().parallelStream().forEach { index ->
        frameLines[index] = renderFrame(ImageIO.read(File("$genFolderName/$index.bmp")))
    }
    val frames = arrayOfNulls<Frame>(fileCount + 1)
    (1..fileCount).toList().parallelStream().forEach { index ->
        val current = frameLines[index] ?: return@forEach
        val full = ("$ESC[H" + current.joinToString("\n")).toByteArray(Charsets.US_ASCII)
        val previous = frameLines[index - 1]
        frames[index] = Frame(full, if (previous == null) full else buildDiff(previous, current))
    }
    return frames
}

private fun renderFrame(image: BufferedImage): Array<String> {
    val width = image.width
    val height = image.height
    val pixels = image.getRGB(0, 0, width, height, null, 0, width)
    return Array(height) { y ->
        buildString(width) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val rgb = pixels[rowOffset + x]
                val red = (rgb ushr 16) and 0xFF
                val green = (rgb ushr 8) and 0xFF
                val blue = rgb and 0xFF
                val luminance = (red * 0.2126f + green * 0.7152f + blue * 0.0722f) / 255
                append(ASCII_CHARS[(ASCII_CHARS.lastIndex * luminance).toInt()])
            }
        }
    }
}

// 変化した行だけをカーソル移動付きで書き換えるバイト列を作る
private fun buildDiff(previous: Array<String>, current: Array<String>): ByteArray {
    val builder = StringBuilder()
    for (row in current.indices) {
        if (previous.getOrNull(row) != current[row]) {
            builder.append(ESC).append('[').append(row + 1).append(";1H").append(current[row])
        }
    }
    return builder.toString().toByteArray(Charsets.US_ASCII)
}

private fun getGeneratedImageFileCount(genFolderName: String) = File(genFolderName).list()?.size ?: 0

private fun convertImageFile(
    movieFileName: String,
    width: Int,
    height: Int,
    genFolderName: String,
    fps: Int,
) {
    runCommand("ffmpeg -y -i $movieFileName -r $fps -vf scale=$width:$height $genFolderName/%0d.bmp")
}

private fun convertAudioFile(movieFileName: String, audioFilePath: String) {
    runCommand("ffmpeg -y -i $movieFileName $audioFilePath")
}

private fun runCommand(command: String) {
    ProcessBuilder(command.split(' '))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
        .waitFor()
}

private fun playBadApple(audioFilePath: String) {
    AudioSystem.getAudioInputStream(File(audioFilePath)).use { audioInputStream ->
        (AudioSystem.getLine(DataLine.Info(Clip::class.java, audioInputStream.format)) as Clip).also {
            it.open(audioInputStream)
            it.start()
        }
    }
}
