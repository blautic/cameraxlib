package com.blautic.camerax

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.LayoutInflater
import com.blautic.camerax.databinding.ActivityMainBinding
import com.blautic.cameraxlib.CaptureFragment

class MainActivity : AppCompatActivity() {
    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private var start = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        binding.button.setOnClickListener {
            if(!start){
                val videoFile = CaptureFragment.createFile(this)
                binding.cameraContainer.getFragment<CaptureFragment>().startRecording(videoFile)
            }else{
                binding.cameraContainer.getFragment<CaptureFragment>().stopRecording()
            }
          start = !start
        }

    }
}