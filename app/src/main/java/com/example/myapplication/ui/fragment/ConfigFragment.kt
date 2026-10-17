package com.example.myapplication.ui.fragment

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.logic.policy.ConfigManager

class ConfigFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Lock orientation to portrait
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        return ComposeView(requireContext()).apply {
            setContent {
                MaterialTheme(
                    colorScheme = lightColorScheme()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        ConfigScreen(onNavigateToMap = {
                            findNavController().navigate(R.id.action_ConfigFragment_to_MapFragment)
                        })
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Reset orientation when leaving
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

@Composable
fun ConfigScreen(onNavigateToMap: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var missionId by remember { mutableStateOf(ConfigManager.missionId) }
    var userId by remember { mutableStateOf(ConfigManager.userId) }
    var showError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White) // Forced White background
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "MSSS Configuration",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.Black, // Forced Black text
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = missionId,
            onValueChange = { 
                missionId = it
                if (it.isNotBlank()) showError = false
            },
            label = { Text("MISSION_ID") },
            isError = showError && missionId.isBlank(),
            supportingText = {
                if (showError && missionId.isBlank()) {
                    Text("Mission ID cannot be empty", color = MaterialTheme.colorScheme.error)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color.Black,
                focusedLabelColor = Color.Blue,
                unfocusedLabelColor = Color.Gray,
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            singleLine = true
        )

        OutlinedTextField(
            value = userId,
            onValueChange = { 
                userId = it
                if (it.isNotBlank()) showError = false
            },
            label = { Text("USER_ID") },
            isError = showError && userId.isBlank(),
            supportingText = {
                if (showError && userId.isBlank()) {
                    Text("User ID cannot be empty", color = MaterialTheme.colorScheme.error)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color.Black,
                focusedLabelColor = Color.Blue,
                unfocusedLabelColor = Color.Gray,
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            singleLine = true
        )

        Button(
            onClick = {
                if (missionId.isNotBlank() && userId.isNotBlank()) {
                    // Clear map cache and archives on entry
                    com.example.myapplication.utils.MapCacheUtils.clearAllMapData(context)

                    ConfigManager.missionId = missionId
                    ConfigManager.userId = userId
                    ConfigManager.isConfigured = true
                    onNavigateToMap()
                } else {
                    showError = true
                    Toast.makeText(context, "Fill in all fields to continue", Toast.LENGTH_SHORT).show()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Text("ENTER MISSION", color = Color.White)
        }
    }
}
