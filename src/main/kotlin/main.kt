import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStreamWriter
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.DataLine
import kotlin.io.path.Path

suspend fun main(args: Array<String>) = coroutineScope {
    val movieFileName = "movie.mp4"
    val audioFilePath = "audio.wav"
    val genFolderName = "gen"
    val width = 480
    val height = 360
    val fps = 30

    Files.list(Path(genFolderName)).forEach {
        Files.delete(it)
    }
    Files.delete(Path(genFolderName))
    Files.createDirectory(Path(genFolderName))
    
    convertImageFile(movieFileName, width, height, genFolderName, fps)
    println("Image Gen Done")
    val fileCount = getGeneratedImageFileCount(genFolderName)
    
    val asciiDeferred = async(Dispatchers.Default) {
        generateAllAsciiFrames(fileCount, width, height)
    }
    val audioDeferred = async(Dispatchers.IO) {
        convertAudioFile(movieFileName)
    }
    
    val result = try {
        asciiDeferred.await()
    } catch (e: Exception) {
        println("ASCII generation failed: ${e.message}")
        return@coroutineScope
    }
    
    try {
        audioDeferred.await()
        println("Audio Gen Done")
    } catch (e: Exception) {
        println("Audio conversion failed: ${e.message}")
        return@coroutineScope
    }
    
    // Clean up audio file when program exits
    File(audioFilePath).deleteOnExit()
    println("Convert Done")
    
    val output = OutputStreamWriter(System.out)
    clearTerminal(output)
    
    playBadApple(audioFilePath)
    
    for (i in 1..fileCount) {
        resetCursorPosition(output)
        result[i]?.let {
            output.append(it)
        }
        output.flush()
        delay((1000/fps - 5).toLong())
    }
}

private suspend fun generateAllAsciiFrames(
    fileCount: Int,
    width: Int,
    height: Int
): HashMap<Int, String> = coroutineScope {
    val result = HashMap<Int, String>()
    val batchSize = 50
    val frames = (1..fileCount).toList()
    
    frames.chunked(batchSize).forEach { batch ->
        val deferredResults = batch.map { i ->
            async(Dispatchers.Default) {
                try {
                    val fileName = "$i.bmp"
                    i to getOutput(width, height, ImageIO.read(File("gen/$fileName")))
                } catch (e: Exception) {
                    println("Failed to process frame $i: ${e.message}")
                    i to ""
                }
            }
        }
        deferredResults.awaitAll().forEach { (frame, text) ->
            result[frame] = text
        }
    }
    result
}

private fun getGeneratedImageFileCount(genFolderName: String) = File(genFolderName).list()?.size ?: 0

private fun convertImageFile(
    movieFileName: String,
    width: Int,
    height: Int,
    genFolderName: String,
    fps:Int,
) {
    val imageGenCommand = "ffmpeg -i $movieFileName -r $fps -vf scale=$width:$height $genFolderName/%0d.bmp".split(' ')
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
