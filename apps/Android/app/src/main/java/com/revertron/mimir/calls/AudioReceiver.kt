package com.revertron.mimir.calls

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

class AudioReceiver(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 1
) : Thread("AudioReceiver") {

    @Volatile private var running = false

    private lateinit var codec: MediaCodec
    private lateinit var audioTrack: AudioTrack

    private val inputQueue = ArrayDeque<ByteArray>()

    fun pushPacket(data: ByteArray) {
        synchronized(inputQueue) {
            inputQueue.add(data)
            if (inputQueue.size > 5)
                inputQueue.removeFirst()
            //Log.i(name, "Queue size ${inputQueue.size}")
            (inputQueue as Object).notify()
        }
    }

    override fun run() {
        running = true

        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
            val csd0 = byteArrayOf(0x12, 0x10)   // 44.1 kHz, LC, 1 channel
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            configure(format, null, null, 0)
            start()
        }

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(minBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize,
                AudioTrack.MODE_STREAM
            )
        }

        audioTrack.play()

        val bufferInfo = MediaCodec.BufferInfo()
        var pts: Long = 0
        val frameDurationUs = 1024L * 1_000_000L / sampleRate

        while (running) {
            /*val packet = synchronized(inputQueue) {
                if (inputQueue.isNotEmpty()) inputQueue.removeFirst() else null
            }*/
            val packet = synchronized(inputQueue) {
                while (inputQueue.isEmpty() && running) {
                    (inputQueue as Object).wait(5)
                }
                if (inputQueue.isNotEmpty())
                    inputQueue.removeFirst()
                else
                    null
            }
            if (!running) break
            if (packet != null) {
                val inIndex = codec.dequeueInputBuffer(5000)
                if (inIndex >= 0) {
                    val inputBuffer: ByteBuffer? = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(packet)
                        //Log.i(name, "Put packet of ${packet.size} bytes into codec")
                        codec.queueInputBuffer(inIndex, 0, packet.size, pts, 0)
                        pts += frameDurationUs
                    }
                }
            }

            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 5000)
            //Log.i(name, "outIndex: $outIndex")
            when (outIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    Log.w(name, "Output format changed: $format")
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // No data
                }
                else -> if (outIndex >= 0) {
                    //while (outIndex >= 0) {
                        val outBuffer: ByteBuffer? = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null) {
                            val pcm = ByteArray(bufferInfo.size)
                            outBuffer.get(pcm)
                            outBuffer.clear()

                            //Log.i(name, "Got from codec, sending to audioTrack")
                            audioTrack.write(pcm, 0, pcm.size)

                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        //outIndex = codec.dequeueOutputBuffer(bufferInfo, 5000)
                    //}
                }
            }
            val size = synchronized(inputQueue) {
                inputQueue.size
            }
            if (size > 0)
                sleep(1)
            else
                sleep(5)
        }

        Log.i(name, "Stopping")
        codec.stop()
        codec.release()
        audioTrack.stop()
        audioTrack.release()
    }

    fun stopReceiver() {
        Log.i(name, "Stopping thread")
        running = false
    }
}
