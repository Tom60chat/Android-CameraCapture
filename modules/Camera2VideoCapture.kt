package fr.katycorp.dashcam.modules

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.database.Cursor
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.SurfaceHolder
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.example.android.camera.utils.AutoFitSurfaceView
import com.example.android.camera.utils.OrientationLiveData
import com.example.android.camera.utils.getPreviewOutputSize
import fr.katycorp.dashcam.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class Camera2VideoCapture(
    private val context: FragmentActivity,
    private val viewFinder: AutoFitSurfaceView,
    private val cameraId: String,
    private val fps: Int,
    private val width: Int,
    private val height: Int) {

    private var recording: Boolean = false
    val IsRecording get() = recording

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = context.applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraId)
    }

    /** File where the recording will be saved */
    private val outputURI: Uri? by lazy { createMedia(context, "video/mpeg") }

    /**
     * Setup a persistent [Surface] for the recorder so we can use it as an output target for the
     * camera session without preparing the recorder
     */
    private val recorderSurface: Surface by lazy {

        // Get a persistent Surface from MediaCodec, don't forget to release when done
        val surface = MediaCodec.createPersistentInputSurface()

        // Prepare and release a dummy MediaRecorder with our new surface
        // Required to allocate an appropriately sized buffer before passing the Surface as the
        //  output target to the capture session
        createRecorder(surface).apply {
            prepare()
            release()
        }

        surface
    }

    /** Saves the video recording */
    private val recorder: MediaRecorder by lazy { createRecorder(recorderSurface) }

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var session: CameraCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Requests used for preview only in the [CameraCaptureSession] */
    private val previewRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            // Add the preview surface target
            addTarget(viewFinder.holder.surface)
        }.build()
    }

    /** Requests used for preview and recording in the [CameraCaptureSession] */
    private val recordRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            // Add the preview and recording surface targets
            addTarget(viewFinder.holder.surface)
            addTarget(recorderSurface)
            // Sets user requested FPS for all targets
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        }.build()
    }

    private var recordingStartMillis: Long = 0L

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    init {
        viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {

                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(
                    viewFinder.display, characteristics, SurfaceHolder::class.java)
                Log.d(TAG, "View finder size: ${viewFinder.width} x ${viewFinder.height}")
                Log.d(TAG, "Selected preview size: $previewSize")
                viewFinder.setAspectRatio(previewSize.width, previewSize.height)

                // To ensure that size is set, initialize camera in the view's thread
                viewFinder.post { initializeCamera() }
            }
        })

        // Used to rotate the output media to match device orientation
        relativeOrientation = OrientationLiveData(context, characteristics).apply {
            observe(context, Observer {
                    orientation -> Log.d(TAG, "Orientation changed: $orientation")
            })
        }
    }

    /** Creates a [MediaRecorder] instance using the provided [Surface] as input */
    private fun createRecorder(surface: Surface): MediaRecorder {
        val resolver = context.applicationContext.contentResolver
        val mediaRecorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(context)
            else
                MediaRecorder()
        val outputURI = outputURI ?: return mediaRecorder
        val outputFile = resolver.openFileDescriptor(outputURI, "w") ?: return mediaRecorder

        return mediaRecorder.apply {
           setAudioSource(MediaRecorder.AudioSource.MIC)
           setVideoSource(MediaRecorder.VideoSource.SURFACE)
           setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
           setOutputFile(outputFile.fileDescriptor)
           setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
           if (fps > 0) setVideoFrameRate(fps)
           setVideoSize(width, height)
           setVideoEncoder(MediaRecorder.VideoEncoder.H264)
           setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
           setInputSurface(surface)
        }
    }

    fun getRealPathFromURI(context: Context, contentUri: Uri?): String? {
        var cursor: Cursor? = null

        return try {
            val proj = arrayOf(MediaStore.MediaColumns.DATA)

            cursor = context.contentResolver.query(contentUri!!, proj, null, null, null) ?: return null

            val column_index: Int = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

            cursor.moveToFirst()
            cursor.getString(column_index)
        } finally {
            cursor?.close()
        }
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating request
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = context.lifecycleScope.launch(Dispatchers.Main) {

        // Open the selected camera
        camera = openCamera(cameraManager, cameraId, cameraHandler)

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(viewFinder.holder.surface, recorderSurface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        // Sends the capture request as frequently as possible until the session is torn down or
        //  session.stopRepeating() is called
        session.setRepeatingRequest(previewRequest, null, cameraHandler)
    }
    
    fun startRecording() {
        if (recording) return

        recording = true
        
        context.lifecycleScope.launch(Dispatchers.IO) {

            // Prevents screen rotation during the video recording
            context.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_LOCKED

            // Start recording repeating requests, which will stop the ongoing preview
            //  repeating requests without having to explicitly call `session.stopRepeating`
            session.setRepeatingRequest(recordRequest, null, cameraHandler)

            // Finalizes recorder setup and starts recording
            recorder.apply {
                // Sets output orientation based on current sensor value at start time
                relativeOrientation.value?.let { setOrientationHint(it) }
                prepare()
                start()
            }
            recordingStartMillis = System.currentTimeMillis()
            Log.d(TAG, "Recording started")
        }
    }

    fun stopRecordingAndSave() {
        if(!recording) return
        
        context.lifecycleScope.launch(Dispatchers.IO) {
            val resolver = context.applicationContext.contentResolver

            // Unlocks screen rotation after recording finished
            context.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
            val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
            if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
            }

            Log.d(TAG, "Recording stopped. Output file: $outputURI")
            recorder.stop()

            // Broadcasts the media file to the rest of the system
            /*MediaScannerConnection.scanFile(
                context, arrayOf(outputURI!!.path!!), null, null)*/
            if (outputURI != null) {
                resolver.update(
                    outputURI!!,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null)
            }

            // Launch external activity via intent to play video recorded using our provider
            /*startActivity(Intent().apply {
                action = Intent.ACTION_VIEW
                type = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(outputFile.extension)
                val authority = "${BuildConfig.APPLICATION_ID}.provider"
                data = FileProvider.getUriForFile(view.context, authority, outputFile)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            })*/

            // Finishes our current camera screen
            recording = false
        }
    }
    
    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                context.finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when(error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Creates a capture session using the predefined targets, and defines a session state
        // callback which resumes the coroutine once the session is configured
        device.createCaptureSession(targets, object: CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    fun onStop() {
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    fun onDestroy() {
        cameraThread.quitSafely()
        recorder.release()
        recorderSurface.release()
    }

    companion object {
        private val TAG = Camera2VideoCapture::class.java.simpleName

        private const val RECORDER_VIDEO_BITRATE: Int = 10_000_000
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L

        /**
         * Create a media named a using formatted timestamp with the current date and time.
         *
         * @return [Uri] created.
         */
        private fun createMedia(context: Context, mimeType: String) : Uri? {
            // Add a specific media item.
            val resolver = context.applicationContext.contentResolver

            // Find all image files on the primary external storage device.
            val imageCollection =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Video.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY )
                else
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI

            // Publish a new image.
            val name = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
                .format(System.currentTimeMillis())
            val relativeLocation = Environment.DIRECTORY_DCIM + '/' + context.getString(R.string.app_name)
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { //this one
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeLocation)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            return resolver.insert(imageCollection, contentValues)
        }
    }
}