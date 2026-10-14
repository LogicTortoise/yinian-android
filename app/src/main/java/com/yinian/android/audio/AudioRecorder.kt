package com.yinian.android.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight MediaRecorder wrapper. Records to app-private storage only.
 * Truthfully reports state: if the recorder fails to start or write, the file is deleted
 * and callers see a failure result rather than a phantom saved file.
 *
 * NOTE: this records audio and stores the file. Speech-to-text is intentionally NOT
 * performed here. Until an ASR provider is configured, callers must display a
 * "transcription not configured" message and present the audio as the only artifact.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var currentStartedAt: Long = 0L

    val isRecording: Boolean get() = recorder != null

    val startedAt: Long get() = currentStartedAt

    /** Starts recording. Returns the destination file on success, null on failure. */
    fun start(): File? {
        if (recorder != null) return currentFile
        val dir = File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "rec-$stamp.m4a")
        val rec = newRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
        }
        return try {
            rec.prepare()
            rec.start()
            recorder = rec
            currentFile = file
            currentStartedAt = System.currentTimeMillis()
            file
        } catch (t: Throwable) {
            runCatching { rec.release() }
            recorder = null
            currentFile = null
            // Don't leave a broken file behind.
            runCatching { if (file.exists()) file.delete() }
            null
        }
    }

    /**
     * Stops the active recording. Returns the saved file on success.
     * If the stop fails, the partial file is deleted and null is returned.
     */
    fun stop(): File? {
        val rec = recorder ?: return null
        val file = currentFile
        return try {
            rec.stop()
            rec.release()
            recorder = null
            currentFile = null
            // Some devices report stop() failing for very short clips; if file is empty, drop it.
            if (file != null && file.exists() && file.length() > 0L) file else {
                runCatching { file?.delete() }
                null
            }
        } catch (t: Throwable) {
            runCatching { rec.release() }
            recorder = null
            currentFile = null
            runCatching { if (file?.exists() == true) file.delete() }
            null
        }
    }

    /** Cancels an in-progress recording and deletes the partial file. */
    fun cancel() {
        val rec = recorder
        val file = currentFile
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        recorder = null
        currentFile = null
        runCatching { if (file?.exists() == true) file.delete() }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

    companion object {
        const val RECORDINGS_DIR = "recordings"
    }
}
