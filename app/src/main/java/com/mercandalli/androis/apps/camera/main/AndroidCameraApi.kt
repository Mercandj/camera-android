package com.mercandalli.androis.apps.camera.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.mercandalli.androis.apps.camera.R
import com.mercandalli.androis.apps.camera.activity.ActivityExtension.bind

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayList
import java.util.Arrays

open class AndroidCameraApi : AppCompatActivity() {

    private val takePictureButton: Button by bind(R.id.btn_takepicture)
    private val textureView: TextureView by bind(R.id.texture)
    private val cameraManager by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraCaptureSessions: CameraCaptureSession
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private var imageDimension: Size? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    private val textureListener = createSurfaceTextureListener()
    private val stateCallback = createStateCallback()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_android_camera_api)
        textureView.surfaceTextureListener = textureListener
        takePictureButton.setOnClickListener { takePicture() }
    }

    override fun onDestroy() {
        closeCamera()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = textureListener
        }
    }

    override fun onPause() {
        //closeCamera();
        stopBackgroundThread()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults[0] == PackageManager.PERMISSION_DENIED) {
            // close the app
            Toast.makeText(
                this@AndroidCameraApi,
                "Sorry!!!, you can't use this app without granting permission",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera Background")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun takePicture() {
        if (null == cameraDevice) {
            return
        }
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraDevice!!.id)
            val jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(
                ImageFormat.JPEG
            )
            var width = 640
            var height = 480
            if (jpegSizes != null && jpegSizes.isNotEmpty()) {
                width = jpegSizes[0].width
                height = jpegSizes[0].height
            }
            val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
            val outputSurfaces = ArrayList<Surface>(2)
            outputSurfaces.add(reader.surface)
            outputSurfaces.add(Surface(textureView.surfaceTexture))
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            // Orientation
            val rotation = windowManager.defaultDisplay.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation))
            val file = File(Environment.getExternalStorageDirectory().toString() + "/pic.jpg")
            val readerListener = object : ImageReader.OnImageAvailableListener {
                override fun onImageAvailable(reader: ImageReader) {
                    var image: Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        val buffer = image!!.planes[0].buffer
                        val bytes = ByteArray(buffer.capacity())
                        buffer.get(bytes)
                        save(bytes)
                    } catch (e: FileNotFoundException) {
                        e.printStackTrace()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } finally {
                        image?.close()
                    }
                }

                @Throws(IOException::class)
                private fun save(bytes: ByteArray) {
                    var output: OutputStream? = null
                    try {
                        output = FileOutputStream(file)
                        output.write(bytes)
                    } finally {
                        output?.close()
                    }
                }
            }
            reader.setOnImageAvailableListener(readerListener, backgroundHandler)
            val captureListener = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    Toast.makeText(this@AndroidCameraApi, "Saved:$file", Toast.LENGTH_SHORT).show()
                    createCameraPreview()
                }
            }
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        session.capture(captureBuilder.build(), captureListener, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }
            cameraDevice!!.createCaptureSession(
                outputSurfaces,
                sessionCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun createCameraPreview() {
        try {
            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(imageDimension!!.width, imageDimension!!.height)
            val surface = Surface(texture)
            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(surface)
            val sessionCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return
                    }
                    // When the session is ready, we start displaying the preview.
                    cameraCaptureSessions = cameraCaptureSession
                    updatePreview()
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    Toast.makeText(this@AndroidCameraApi, "Configuration change", Toast.LENGTH_SHORT).show()
                }
            }
            cameraDevice!!.createCaptureSession(
                Arrays.asList(surface),
                sessionCallback,
                null
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun openCamera() {
        try {
            cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            imageDimension = map.getOutputSizes(SurfaceTexture::class.java)[0]
            val cameraPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            val storagePermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (cameraPermission != PackageManager.PERMISSION_GRANTED &&
                storagePermission != PackageManager.PERMISSION_GRANTED
            ) {
                val permissions = arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                ActivityCompat.requestPermissions(
                    this@AndroidCameraApi,
                    permissions,
                    REQUEST_CAMERA_PERMISSION
                )
                return
            }
            cameraManager.openCamera(cameraId!!, stateCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        try {
            cameraCaptureSessions.setRepeatingRequest(
                captureRequestBuilder.build(),
                null,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun closeCamera() {
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    private fun createSurfaceTextureListener() = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // Transform you image captured size according to the surface width and height
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun createStateCallback() = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice!!.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    companion object {

        private val ORIENTATIONS = SparseIntArray()

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        private const val REQUEST_CAMERA_PERMISSION = 200

        @JvmStatic
        fun start(context: Context) {
            val intent = Intent(context, AndroidCameraApi::class.java)
            if (context !is Activity) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }
}