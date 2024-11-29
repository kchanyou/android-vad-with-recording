package com.konovalov.vad.example

import android.Manifest
import android.content.ContentValues
import android.media.AudioRecord
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.konovalov.vad.example.recorder.VoiceRecorder
import com.konovalov.vad.example.recorder.VoiceRecorder.AudioCallback
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.RuntimePermissions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.RandomAccessFile
import android.net.Uri
import android.util.Log
import java.io.OutputStream
import java.io.* // java.io.* 임포트
import java.nio.*


@RuntimePermissions
class VadSileroFragment : Fragment(),
    AudioCallback,
    View.OnClickListener,
    AdapterView.OnItemSelectedListener {

    private val DEFAULT_SAMPLE_RATE = SampleRate.SAMPLE_RATE_8K
    private val DEFAULT_FRAME_SIZE = FrameSize.FRAME_SIZE_256
    private val DEFAULT_MODE = Mode.NORMAL
    private val DEFAULT_SILENCE_DURATION_MS = 300
    private val DEFAULT_SPEECH_DURATION_MS = 50

    private val SPINNER_SAMPLE_RATE_TAG = "sample_rate"
    private val SPINNER_FRAME_SIZE_TAG = "frame_size"
    private val SPINNER_MODE_TAG = "mode"

    private var tempFile: File? = null
    private var filePath: Uri? = null
    private var tempOutputStream: FileOutputStream? = null
    private var outputStream: OutputStream? = null

    private lateinit var titleTextView: TextView

    private lateinit var recordingButton: FloatingActionButton
    private lateinit var speechTextView: TextView

    private lateinit var sampleRateSpinner: Spinner
    private lateinit var frameSpinner: Spinner
    private lateinit var modeSpinner: Spinner

    private lateinit var sampleRateAdapter: ArrayAdapter<String>
    private lateinit var frameAdapter: ArrayAdapter<String>
    private lateinit var modeAdapter: ArrayAdapter<String>

    private lateinit var recorder: VoiceRecorder
    private lateinit var vad: VadSilero
    private var isRecording = false
    private var isSpeechDetected = false

    private fun ShortArray.toShortByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(this.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (s in this) {
            buffer.putShort(s)
        }
        return buffer.array()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_vad_main, parent, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        vad = Vad.builder()
            .setContext(requireContext())
            .setSampleRate(DEFAULT_SAMPLE_RATE)
            .setFrameSize(DEFAULT_FRAME_SIZE)
            .setMode(DEFAULT_MODE)
            .setSilenceDurationMs(DEFAULT_SILENCE_DURATION_MS)
            .setSpeechDurationMs(DEFAULT_SPEECH_DURATION_MS)
            .build()

        recorder = VoiceRecorder(this)

        titleTextView = view.findViewById(R.id.titleTextView)
        titleTextView.setText(R.string.vad_silero)

        speechTextView = view.findViewById(R.id.speechTextView)
        sampleRateSpinner = view.findViewById(R.id.sampleRateSpinner)
        sampleRateAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, getSampleRates())
        sampleRateSpinner.adapter = sampleRateAdapter
        sampleRateSpinner.tag = SPINNER_SAMPLE_RATE_TAG
        sampleRateSpinner.setSelection(getSampleRates().indexOf(DEFAULT_SAMPLE_RATE.name), false)
        sampleRateSpinner.onItemSelectedListener = this

        frameSpinner = view.findViewById(R.id.frameSampleRateSpinner)
        frameAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, getFrameSizes(DEFAULT_SAMPLE_RATE))
        frameSpinner.adapter = frameAdapter
        frameSpinner.tag = SPINNER_FRAME_SIZE_TAG
        frameSpinner.setSelection(getFrameSizes(DEFAULT_SAMPLE_RATE).indexOf(DEFAULT_FRAME_SIZE.name), false)
        frameSpinner.onItemSelectedListener = this

        modeSpinner = view.findViewById(R.id.modeSpinner)
        modeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, modes())
        modeSpinner.adapter = modeAdapter
        modeSpinner.tag = SPINNER_MODE_TAG
        modeSpinner.setSelection(modes().indexOf(DEFAULT_MODE.name), false)
        modeSpinner.onItemSelectedListener = this

        recordingButton = view.findViewById(R.id.recordingActionButton)
        recordingButton.setOnClickListener(this)
        recordingButton.isEnabled = false

        activateRecordingButtonWithPermissionCheck()
    }

    override fun onAudio(audioData: ShortArray) {
        if (vad.isSpeech(audioData)) {
            requireActivity().runOnUiThread {
                speechTextView.setText(R.string.speech_detected)
            }
            if (!isSpeechDetected) {
                isSpeechDetected = true
                startSavingAudio()
            }
            saveAudioData(audioData)
        } else {
            requireActivity().runOnUiThread {
                speechTextView.setText(R.string.noise_detected)
            }

            if (isSpeechDetected) {
                isSpeechDetected = false
                stopSavingAudio()
            }
        }
    }


    private fun startSavingAudio() {
        val fileName = "recording_${System.currentTimeMillis()}.wav"

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val contentResolver = requireContext().contentResolver

        val collection =
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = contentResolver.insert(collection, values)

        Log.d("VadSileroFragment", "itemUri: $itemUri")

        itemUri?.let { uri ->
            filePath = uri

            try {
                tempFile = File.createTempFile("recording_temp", ".pcm", requireContext().cacheDir)
                tempOutputStream = FileOutputStream(tempFile!!)
                Log.d("VadSileroFragment", "tempFile: $tempFile, tempOutputStream: $tempOutputStream")
            } catch (e: IOException) {
                Log.e("VadSileroFragment", "Error creating temp file: ${e.message}")
                tempFile?.delete()
                tempFile = null
                tempOutputStream = null
                return
            }
        } ?: run {
            Log.e("VadSileroFragment", "Failed to create Uri for audio file")
            return

        }
    }

    private fun stopSavingAudio() {
        try {
            tempOutputStream?.close()
        } catch (e: IOException) {
            Log.e("VadSileroFragment", "Error closing tempOutputStream: " + e.message)
        } finally {
            tempOutputStream = null
        }

        if (tempFile != null && filePath != null) {
            try {
                val fileSize = tempFile!!.length()
                val contentResolver = requireContext().contentResolver

                contentResolver.openOutputStream(filePath!!)?.use { fileOutputStream ->
                    try {
                        writeWavHeader(fileOutputStream, vad.sampleRate.value, 1, 16, fileSize)
                        FileInputStream(tempFile!!).use { inputStream ->
                            inputStream.copyTo(fileOutputStream)
                        }
                    } catch (e: IOException) {
                        Log.e("VadSileroFragment", "Error writing WAV file: " + e.message)
                    }
                }

                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                    put(MediaStore.Audio.Media.SIZE, fileSize)
                }
                try {
                    requireContext().contentResolver.update(filePath!!, contentValues, null, null)
                } catch (e: java.lang.Exception) {
                    Log.e("VadSileroFragment", "Failed to update MediaStore: " + e.message)
                }


            } catch (e: IOException) {
                Log.e("VadSileroFragment", "Error accessing tempFile: " + e.message)
            } finally {
                tempFile?.delete()
                tempFile = null
            }
        } else {
            Log.e("VadSileroFragment", "tempFile or filePath is null in stopSavingAudio")
            tempFile?.delete()
            tempFile = null
        }
    }

    private fun saveAudioData(audioData: ShortArray) {
        try {
            tempOutputStream?.let {
                it.write(audioData.toShortByteArray())
            } ?: run {
                Log.e("VadSileroFragment", "tempOutputStream is null in saveAudioData")
            }
        } catch (e: IOException) {
            Log.e("VadSileroFragment", "Error writing audio data: ${e.message}")
        }
    }

    @Throws(IOException::class)
    private fun writeWavHeader(outputStream: OutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val byteRate = longSampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16.toByte() // 4 bytes: size of 'fmt ' chunk
        header[17] = 0.toByte()
        header[18] = 0.toByte()
        header[19] = 0.toByte()
        header[20] = 1.toByte() // format = 1
        header[21] = 0.toByte()
        header[22] = channels.toByte()
        header[23] = 0.toByte()
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = (longSampleRate shr 8 and 0xff).toByte()
        header[26] = (longSampleRate shr 16 and 0xff).toByte()
        header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (2 * 16 / 8).toByte() // block align
        header[33] = 0.toByte()
        header[34] = bitsPerSample.toByte()
        header[35] = 0.toByte()
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()
        outputStream.write(header, 0, 44)
    }

    private fun updateWavHeader(filePath: String, totalAudioLen: Long) {
        try {
            val randomAccessFile = RandomAccessFile(filePath, "rw")
            randomAccessFile.seek(4)
            randomAccessFile.writeInt((totalAudioLen + 36).toInt())
            randomAccessFile.seek(40)
            randomAccessFile.writeInt(totalAudioLen.toInt())
            randomAccessFile.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun getSampleRates(): List<String> {
        return SampleRate.values().map { it.name }.toList()
    }

    private fun getFrameSizes(sampleRate: SampleRate): List<String> {
        return vad.supportedParameters.get(sampleRate)?.map { it.name }?.toList() ?: emptyList()
    }

    private fun modes(): List<String> {
        return Mode.values().map { it.name }.toList()
    }

    private fun startRecording() {
        isRecording = true
        recorder.start(vad.sampleRate.value, vad.frameSize.value)
        recordingButton.setImageResource(R.drawable.stop)
    }

    private fun stopRecording() {
        isRecording = false
        recorder.stop()
        recordingButton.setImageResource(R.drawable.red_dot)
    }

    override fun onClick(v: View) {
        if (!isRecording) {
            startRecording()
        } else {
            stopRecording()
        }
    }

    override fun onItemSelected(adapterView: AdapterView<*>, view: View, position: Int, l: Long) {
        stopRecording()

        when (adapterView.tag.toString()) {
            SPINNER_SAMPLE_RATE_TAG -> {
                vad.sampleRate = SampleRate.valueOf(sampleRateAdapter.getItem(position).toString())

                frameAdapter.clear()
                frameAdapter.addAll(getFrameSizes(vad.sampleRate))
                frameAdapter.notifyDataSetChanged()
                frameSpinner.setSelection(0)

                vad.frameSize = FrameSize.valueOf(frameAdapter.getItem(0).toString())
            }

            SPINNER_FRAME_SIZE_TAG -> {
                vad.frameSize = FrameSize.valueOf(frameAdapter.getItem(position).toString())
            }

            SPINNER_MODE_TAG -> {
                vad.mode = Mode.valueOf(modeAdapter.getItem(position).toString())
            }
        }
    }

    @NeedsPermission(Manifest.permission.RECORD_AUDIO)
    fun activateRecordingButton() {
        recordingButton.isEnabled = true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onNothingSelected(p0: AdapterView<*>?) {}

    override fun onDestroyView() {
        super.onDestroyView()
        recorder.stop()
        vad.close()
        outputStream?.close()
        tempOutputStream?.close()
        tempFile?.delete()
        tempFile = null
        stopSavingAudio()
    }
}