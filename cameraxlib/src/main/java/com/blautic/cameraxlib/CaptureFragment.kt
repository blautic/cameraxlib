package com.blautic.cameraxlib

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.whenCreated
import com.blautic.cameraxlib.databinding.FragmentCaptureBinding
import kotlinx.coroutines.*
import java.io.File

class CaptureFragment : Fragment() {

    private var _captureViewBinding: FragmentCaptureBinding? = null
    private val captureViewBinding get() = _captureViewBinding!!
    private val cameraCapabilities = mutableListOf<CameraCapability>()
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private var cameraIndex = 1
    private var qualityIndex = DEFAULT_QUALITY_IDX
    private var audioEnabled = false

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }
    private var enumerationDeferred:Deferred<Unit>? = null
    var cameraCallback: CameraCallback? = null

    interface CameraCallback {
        fun onVideoSaved(path: Uri)
        fun onError(message: String, cause: Throwable?)
    }

    // main cameraX capture functions
    /**
     *   Always bind preview + video capture use case combinations in this sample
     *   (VideoCapture can work on its own). The function should always execute on
     *   the main thread.
     */
    private suspend fun bindCaptureUsecase() {
        val cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

        val cameraSelector = getCameraSelector(cameraIndex)

        // create the user required QualitySelector (video resolution): we know this is
        // supported, a valid qualitySelector will be created.
        val quality = cameraCapabilities[cameraIndex].qualities[qualityIndex]
        val qualitySelector = QualitySelector.from(quality)

        captureViewBinding.previewView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            val orientation = this@CaptureFragment.resources.configuration.orientation
            dimensionRatio = quality.getAspectRatioString(quality,
                (orientation == Configuration.ORIENTATION_PORTRAIT))
        }

        val preview = Preview.Builder()
            .setTargetAspectRatio(quality.getAspectRatio(quality))
            .build().apply {
                setSurfaceProvider(captureViewBinding.previewView.surfaceProvider)
            }

        // build a recorder, which can:
        //   - record video/audio to MediaStore(only shown here), File, ParcelFileDescriptor
        //   - be used create recording(s) (the recording performs recording)
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                videoCapture,
                preview
            )
        } catch (exc: Exception) {
            // we are on main thread, let's reset the controls on the UI.
            Log.e(TAG, "Use case binding failed", exc)
            cameraCallback?.onError("Use case binding failed", exc)
        }
    }


    @SuppressLint("MissingPermission")
    fun startRecording(outFile: File) {

        val mediaStoreOutput = FileOutputOptions.Builder(outFile)
            .build()

        // configure Recorder and Start recording to the mediaStoreOutput.
        currentRecording = videoCapture.output
               .prepareRecording(requireActivity(), mediaStoreOutput)
               .apply { if (audioEnabled) withAudioEnabled() }
               .start(mainThreadExecutor){ event ->
                   if (event is VideoRecordEvent.Finalize) {
                       // display the captured video
                       lifecycleScope.launch {
                           Log.i(TAG,"Video File : ${event.outputResults.outputUri.path}")
                           cameraCallback?.onVideoSaved(event.outputResults.outputUri)
                       }
                   }
               }

        Log.i(TAG, "Recording started")
    }
    fun stopRecording(){
        if (currentRecording == null ) {
            return
        }
        val recording = currentRecording
        if (recording != null) {
            recording.stop()
            currentRecording = null
        }
    }

    fun changeCamera(){
        cameraIndex = (cameraIndex + 1) % cameraCapabilities.size
        viewLifecycleOwner.lifecycleScope.launch {
            bindCaptureUsecase()
        }
    }
    fun enableAudio(enable:Boolean){
        this.audioEnabled = enable
    }

    fun getQualities():List<String>{
        val selectorStrings = cameraCapabilities[cameraIndex].qualities.map {
            it.getNameString()
        }
        return selectorStrings
    }

    fun changeQuality(qualityIndex:Int){
        this.qualityIndex =  qualityIndex
    }
    /**
     * Retrieve the asked camera's type(lens facing type). In this sample, only 2 types:
     *   idx is even number:  CameraSelector.LENS_FACING_BACK
     *          odd number:   CameraSelector.LENS_FACING_FRONT
     */
    private fun getCameraSelector(idx: Int) : CameraSelector {
        if (cameraCapabilities.size == 0) {
            Log.i(TAG, "Error: This device does not have any camera, bailing out")
            requireActivity().finish()
        }
        return (cameraCapabilities[idx % cameraCapabilities.size].camSelector)
    }

    data class CameraCapability(val camSelector: CameraSelector, var qualities:List<Quality>)
    /**
     * Query and cache this platform's camera capabilities, run only once.
     */
    init {
        enumerationDeferred = lifecycleScope.async {
            whenCreated {
                val provider = ProcessCameraProvider.getInstance(requireContext()).await()

                provider.unbindAll()
                for (camSelector in arrayOf(
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    CameraSelector.DEFAULT_FRONT_CAMERA
                )) {
                    try {
                        // just get the camera.cameraInfo to query capabilities
                        // we are not binding anything here.
                        if (provider.hasCamera(camSelector)) {
                            val camera = provider.bindToLifecycle(requireActivity(), camSelector)
                            QualitySelector
                                .getSupportedQualities(camera.cameraInfo)
                                .filter { quality ->
                                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
                                        .contains(quality)
                                }.also {
                                    cameraCapabilities.add(CameraCapability(camSelector, it))
                                }
                            cameraCapabilities.forEach { it.qualities = it.qualities.reversed() }
                        }
                    } catch (exc: java.lang.Exception) {
                        Log.e(TAG, "Camera Face $camSelector is not supported")
                    }
                }
            }
        }
    }

    /**
     * One time initialize for CameraFragment (as a part of fragment layout's creation process).
     * This function performs the following:
     *   - initialize but disable all UI controls except the Quality selection.
     *   - set up the Quality selection recycler view.
     *   - bind use cases to a lifecycle camera, enable UI controls.
     */
    private fun initCameraFragment() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (enumerationDeferred != null) {
                enumerationDeferred!!.await()
                enumerationDeferred = null
            }
            bindCaptureUsecase()
        }
    }

    // System function implementations
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _captureViewBinding = FragmentCaptureBinding.inflate(inflater, container, false)
        return captureViewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCameraFragment()
    }
    override fun onDestroyView() {
        _captureViewBinding = null
        super.onDestroyView()
    }

    companion object {
        var DEFAULT_QUALITY_IDX = 0
        val TAG:String = CaptureFragment::class.java.simpleName
        const val VIDEO_EXTENSION = ".mp4"

        fun createFile(context: Context) =
            File(
                context.filesDir.path, "${System.currentTimeMillis()}" + VIDEO_EXTENSION
            )
    }
}