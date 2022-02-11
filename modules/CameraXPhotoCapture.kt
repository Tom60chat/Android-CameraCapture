package fr.katycorp.dashcam.modules

import android.annotation.SuppressLint
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import fr.katycorp.dashcam.R
import fr.katycorp.dashcam.extensions.getAspectRatio
import fr.katycorp.dashcam.fragments.CameraFragment
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXPhotoCapture(private val context: FragmentActivity, private val previewView : PreviewView) {
    private lateinit var imageCapture: ImageCapture

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(context) }
    private val cameraExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() } // Potential memory leak or something else (cameraExecutor.shutdown() not called)

    // bindCaptureUsecase
    fun startCamera(cameraSelector: CameraSelector) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // build a image capture, which can:
            //   - take photo to MediaStore(only shown here), File, ParcelFileDescriptor
            imageCapture = ImageCapture.Builder()
                .setIoExecutor(cameraExecutor)
                .build()

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    context,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                // we are on main thread, let's reset the controls on the UI.
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, mainThreadExecutor)
    }

    fun stopCamera() {
        cameraExecutor.shutdown()
    }

    @SuppressLint("MissingPermission")
    fun takePhoto() {
        // Get a stable reference of the modifiable video capture use case
        val imageCapture = imageCapture ?: return

        // Create MediaStoreOutputOptions for our recorder: resulting our recording!
        val name = context.getString(R.string.app_name) +
                SimpleDateFormat(CameraXPhotoCapture.FILENAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun
                        onImageSaved(output: ImageCapture.OutputFileResults){
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )

        Log.i(TAG, "Recording started")
    }

    companion object {
        val TAG: String = CameraXPhotoCapture::class.java.simpleName
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}
