# Android-CameraCapture
Library to easily take photos or videos

Ok it's not really a library, but have fun.

This is using the cameraX or camera2 api while using MediaStore for both

Majority of the code came from [android/camera-samples](https://github.com/android/camera-samples)

## Init
```kotlin
/// CameraX
val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

// Photo capture
cameraPhotoCapture = CameraXPhotoCapture(this, binding.viewFinder)
cameraPhotoCapture.startCamera(cameraSelector)

// Video capture
cameraVideoCapture = CameraXVideoCapture(this, binding.viewFinder)
cameraVideoCapture.startCamera(cameraSelector, Quality.HIGHEST)

/// Camera2
val cameraId = "1"

// Photo capture
cameraPhotoCapture = Camera2PhotoCapture(this, binding.viewFinder, cameraId, ImageFormat.JPEG)

// Video cpature
cameraVideoCapture = Camera2VideoCapture(this, binding.viewFinder, cameraId, 29, 1920, 1080)
```

## Take
```kotlin
/// CameraX
// Photo capture
binding.cameraCaptureButton.setOnClickListener { cameraPhotoCapture.takePhoto() }

// Video capture
binding.cameraCaptureButton.setOnClickListener {
    if (cameraVideoCapture.isRecording())
        cameraVideoCapture.stopRecording()
    else
        cameraVideoCapture.startRecording(true);
}

/// Camera2
// Photo capture
binding.cameraCaptureButton.setOnClickListener { cameraPhotoCapture.takeAndSavePhoto() }

// Video cpature
binding.cameraCaptureButton.setOnClickListener {
    if (cameraVideoCapture.IsRecording)
        cameraVideoCapture.stopRecordingAndSave()
    else
        cameraVideoCapture.startRecording();
}
 ```
