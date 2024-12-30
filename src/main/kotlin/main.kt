import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import kotlin.coroutines.suspendCoroutine


suspend fun main(args: Array<String>) = coroutineScope {
    val movieFileName = "movie.mp4"
    val audioFilePath = "audio.wav"
    val genFolderName = "gen"
    val width = 480
    val height = 360

    val result = hashMapOf<Int, String>()

    runBlocking {
        convertImageFile(movieFileName, width, height, genFolderName)
        println("Image Gen Done")
        val fileCount = getGeneratedImageFileCount(genFolderName)
        generateAA(fileCount, width, height, result)
        File(audioFilePath).deleteOnExit()
        convertAudioFile(movieFileName)
        println("Audio Gen Done")
    }
    println("Convert Done")

    val output = OutputStreamWriter(System.out)
    clearTerminal(output)

    playBadApple(audioFilePath)

    val fileCount = getGeneratedImageFileCount(genFolderName)
    var job:Job? = null
    var currentImageIndex = 0
    while (true) {
        if (currentImageIndex > fileCount) {
            break
        }
        job = launch(Dispatchers.Default) {
            currentImageIndex += 1
            resetCursorPosition(output)
            result[currentImageIndex]?.let {
                output.append(it)
                output.flush()
            }
        }
        delay(95)
    }
}

private fun generateAA(
    fileCount: Int,
    width: Int,
    height: Int,
    result: HashMap<Int, String>
) {
    (1 until fileCount).toList().parallelStream().forEach {
        val fileName = "$it.bmp"
        val text = getOutput(width, height, ImageIO.read(File("gen/$fileName")))
        result[it] = text
    }
}

private fun getGeneratedImageFileCount(genFolderName: String) = File(genFolderName).list()?.size ?: 0

private fun convertImageFile(
    movieFileName: String,
    width: Int,
    height: Int,
    genFolderName: String
) {
    val imageGenCommand = "ffmpeg -i $movieFileName -r 10 -vf scale=$width:$height $genFolderName/%0d.bmp".split(' ')
    ProcessBuilder(imageGenCommand).start().waitFor()
}

private fun convertAudioFile(movieFileName: String) {
    val audioCommand = "ffmpeg -i $movieFileName audio.wav".split(' ')
    ProcessBuilder(audioCommand).start().waitFor()
}

private fun resetCursorPosition(outputStreamWriter: OutputStreamWriter) {
    val ESC = "\u001B"
    outputStreamWriter.write("$ESC[0;0H")
    outputStreamWriter.flush()
}

private fun clearTerminal(outputStreamWriter: OutputStreamWriter) {
    outputStreamWriter.write("\u033c")
}

private fun playBadApple(audioFilePath: String) {
    AudioSystem.getAudioInputStream(File(audioFilePath)).use { audioInputStream ->
        (AudioSystem.getLine(DataLine.Info(Clip::class.java, audioInputStream.format)) as Clip).also {
            it.open(audioInputStream)
            it.start()
        }
    }
}

private fun getOutput(width: Int, height: Int, image: BufferedImage) =
    (0 until (height - 1)).map { height ->
        (0 until (width - 1)).map { width ->
            val rgb = image.getRGB(width, height)
            val red = (rgb ushr 16) and 0xFF
            val green = (rgb ushr 8) and 0xFF
            val blue = rgb and 0xFF
            val luminance = (red * 0.2126f + green * 0.7152f + blue * 0.0722f) / 255
            val chars = " .-+*wGHM#&%"
            chars[(chars.lastIndex * luminance).toInt()]
        }.fold("\n") { i, i2 -> i + i2 }
    }.fold("") { i, i2 -> i + i2 }