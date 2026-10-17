package com.example.myapplication.ui.fragment

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.BaseApplication
import com.example.myapplication.ui.map.MapViewModel
import com.example.myapplication.ui.map.MapViewModelFactory
import com.example.myapplication.ui.map.TacticalMapScreen

class MapFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Lock orientation to portrait
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        return ComposeView(requireContext()).apply {
            setContent {
                val app = requireActivity().application as BaseApplication
                val mapViewModel: MapViewModel = viewModel(
                    factory = MapViewModelFactory(app, app.repository)
                )
                TacticalMapScreen(viewModel = mapViewModel)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Reset orientation when leaving
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
