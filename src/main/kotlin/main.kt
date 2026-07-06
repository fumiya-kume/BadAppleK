import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import kotlin.concurrent.thread
import kotlin.io.path.Path

private const val ESC = "\u001B"
private const val ASCII_CHARS = " .-+*wGHM#&%"
private const val UNKNOWN = -1

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
    val ffmpeg = startImageConversion(movieFileName, width, height, genFolderName, fps)
    File(audioFilePath).deleteOnExit()
    convertAudioFile(movieFileName, audioFilePath)

    val frames = ConcurrentHashMap<Int, Frame>()
    val totalFrames = AtomicInteger(UNKNOWN)
    startFrameConverter(ffmpeg, genFolderName, frames, totalFrames)

    // 1秒分のフレームが溜まるか変換が終わり次第、再生を開始する
    while (frames[fps] == null && totalFrames.get() == UNKNOWN) {
        Thread.sleep(10)
    }

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
    var index = 1
    while (totalFrames.get() == UNKNOWN || index <= totalFrames.get()) {
        val targetNanos = startNanos + index * 1_000_000_000L / fps
        val waitNanos = targetNanos - System.nanoTime()
        // 描画が追いつかないときはフレームを捨てて音声との同期を保つ
        if (waitNanos < 0) {
            frames.remove(index)
            index++
            continue
        }
        Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
        // 変換が追いついていないフレームも捨てて音声との同期を保つ
        val frame = frames.remove(index)
        if (frame == null) {
            index++
            continue
        }
        output.write(SYNC_BEGIN)
        output.write(if (lastDrawnIndex == index - 1) frame.diff else frame.full)
        output.write(SYNC_END)
        output.flush()
        lastDrawnIndex = index
        index++
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

// ffmpeg が書き出す BMP を連番順に追いかけて ASCII フレームへ変換する。
// ffmpeg は連番で書き出すため、次のファイルが存在すれば現在のファイルは書き込み完了とみなせる
private fun startFrameConverter(
    ffmpeg: Process,
    genFolderName: String,
    frames: ConcurrentHashMap<Int, Frame>,
    totalFrames: AtomicInteger,
) = thread(isDaemon = true) {
    var index = 1
    var previous: Array<String>? = null
    while (true) {
        val ffmpegAlive = ffmpeg.isAlive
        val current = File(genFolderName, "$index.bmp")
        val ready = File(genFolderName, "${index + 1}.bmp").exists() || (!ffmpegAlive && current.exists())
        if (!ready) {
            if (ffmpegAlive) {
                Thread.sleep(5)
                continue
            }
            totalFrames.set(index - 1)
            break
        }
        val image = readImageOrNull(current)
        if (image == null) {
            // ffmpeg 稼働中なら書き込み途中の可能性があるので待って再試行、終了済みなら末尾の壊れたファイルとして打ち切る
            if (ffmpegAlive) {
                Thread.sleep(5)
                continue
            }
            totalFrames.set(index - 1)
            break
        }
        val lines = renderFrame(image)
        val full = ("$ESC[H" + lines.joinToString("\n")).toByteArray(Charsets.US_ASCII)
        frames[index] = Frame(full, previous?.let { buildDiff(it, lines) } ?: full)
        previous = lines
        index++
    }
}

private fun readImageOrNull(file: File): BufferedImage? = try {
    ImageIO.read(file)
} catch (e: Exception) {
    null
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

private fun startImageConversion(
    movieFileName: String,
    width: Int,
    height: Int,
    genFolderName: String,
    fps: Int,
): Process = startCommand("ffmpeg -y -i $movieFileName -r $fps -vf scale=$width:$height $genFolderName/%0d.bmp")

private fun convertAudioFile(movieFileName: String, audioFilePath: String) {
    startCommand("ffmpeg -y -i $movieFileName $audioFilePath").waitFor()
}

private fun startCommand(command: String): Process =
    ProcessBuilder(command.split(' '))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()

private fun playBadApple(audioFilePath: String) {
    AudioSystem.getAudioInputStream(File(audioFilePath)).use { audioInputStream ->
        (AudioSystem.getLine(DataLine.Info(Clip::class.java, audioInputStream.format)) as Clip).also {
            it.open(audioInputStream)
            it.start()
        }
    }
}
