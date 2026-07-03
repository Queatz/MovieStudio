package app.moviestudio

// Android device uploads require an Activity/ActivityResultLauncher, which the shared UI layer does
// not have access to here. Uploading from the device is supported on the web and desktop targets;
// on Android this is a graceful no-op (returns null, so the caller simply does nothing).
@Suppress("UNUSED_PARAMETER")
actual suspend fun pickAndUploadDeviceFile(type: AssetType): UploadedDeviceFile? = null
