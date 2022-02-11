package fr.katycorp.dashcam.modules

import android.annotation.SuppressLint
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import fr.katycorp.dashcam.R
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraCapture non fragment because it's overcomplicated,
 * with my stupid idea of making things easy to understand
 * for my little brain.
 * Most code is from CameraXVideo sample.
 * @param cameraSelector The camera to use
 * @param quality The quality to use
 */
class CameraXVideoCapture(private val context: FragmentActivity, private val previewView : PreviewView) {
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private var recordingState: VideoRecordEvent? = null

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(context) }
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() } // Potential memory leak or something else (cameraExecutor.shutdown() not called)

    // bindCaptureUsecase
    fun startCamera(cameraSelector: CameraSelector, quality: Quality) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // create the user required QualitySelector (video resolution): we know this is
            // supported, a valid qualitySelector will be created.
            val qualitySelector = QualitySelector.from(quality)

            previewView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                val ratio = Pair(4, 3)
                dimensionRatio = "V,${ratio.second}:${ratio.first}"
            }

            // Preview
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }

            // build a recorder, which can:
            //   - record video/audio to MediaStore(only shown here), File, ParcelFileDescriptor
            //   - be used create recording(s) (the recording performs recording)
            val recorder = Recorder.Builder()
                .setExecutor(cameraExecutor)
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                val test = cameraProvider.hasCamera(cameraSelector)

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    context,
                    cameraSelector,
                    videoCapture,
                    preview
                )
            } catch (exc: Exception) {
                // we are on main thread, let's reset the controls on the UI.
                Log.e(TAG, "Use case binding failed", exc)
            }
        },  mainThreadExecutor)
    }

    fun stopCamera() {
        cameraExecutor.shutdown()
    }

    @SuppressLint("MissingPermission")
    fun startRecording(audioEnabled: Boolean) {
        // Get a stable reference of the modifiable video capture use case
        val videoCapture = videoCapture ?: return

        // Stop the current recording session.
        if (isRecording())
            stopRecording()

        // Create MediaStoreOutputOptions for our recorder: resulting our recording!
        val name = context.getString(R.string.app_name) +
                SimpleDateFormat(CameraXVideoCapture.FILENAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        // Configure Recorder and Start recording to the mediaStoreOutput.
        currentRecording = videoCapture.output
            .prepareRecording(context, mediaStoreOutput)
            .apply { if (audioEnabled) withAudioEnabled() }
            .start(mainThreadExecutor, captureListener)

        Log.i(TAG, "Recording started")
    }

    fun pauseRecording() {
        currentRecording?.pause()
    }

    fun resumeRecording() {
        currentRecording?.resume()
    }

    fun stopRecording() {
        currentRecording?.stop()
    }

    fun isRecording(): Boolean {
        return recordingState is VideoRecordEvent.Start ||
                recordingState is  VideoRecordEvent.Pause ||
                recordingState is  VideoRecordEvent.Resume
    }

    fun isPaused(): Boolean {
        return recordingState is VideoRecordEvent.Pause
    }

    private val captureListener = Consumer<VideoRecordEvent> { event ->
        // cache the recording state
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        //updateUI(event)

        when (event) {
            is VideoRecordEvent.Start -> Toast.makeText(context, "Video capture start", Toast.LENGTH_SHORT).show()
            is VideoRecordEvent.Pause -> Toast.makeText(context, "Video capture pause", Toast.LENGTH_SHORT).show()
            is VideoRecordEvent.Resume -> Toast.makeText(context, "Video capture resume", Toast.LENGTH_SHORT).show()
            is VideoRecordEvent.Finalize -> if (event.hasError())
                Toast.makeText(context, "Video capture ends with error: " + event.error, Toast.LENGTH_SHORT).show()
            else {
                stopRecording()
                Toast.makeText(context, "Video capture succeeded: " + event.outputResults.outputUri, Toast.LENGTH_SHORT)
                    .show()
            }
        }

        /*if (event is VideoRecordEvent.Finalize) {
            // display the captured video
            lifecycleScope.launch {
                navController.navigate(
                    CaptureFragmentDirections.actionCaptureToVideoViewer(
                        event.outputResults.outputUri
                    )
                )
            }
        }*/

    }

    companion object {
        val TAG:String = CameraXVideoCapture::class.java.simpleName
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}
