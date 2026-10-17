package com.example.myapplication.ui.db

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.BaseApplication
import com.example.myapplication.data.local.entities.TrackingRecord
import com.example.myapplication.data.local.entities.TrackingStatus
import com.example.myapplication.data.local.entities.ActivityType
import com.example.myapplication.data.local.TrackingViewModel
import com.example.myapplication.data.local.TrackingViewModelFactory
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*

class DbTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as BaseApplication).repository

        setContent {
            val viewModel: TrackingViewModel = viewModel(
                factory = TrackingViewModelFactory(repository)
            )

            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TrackingScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun TrackingScreen(viewModel: TrackingViewModel, modifier: Modifier = Modifier) {
    val records by viewModel.allRecords.collectAsStateWithLifecycle()
    
    // Get Firebase instance for specific region
    val dbUrl = "https://msss-b34d1-default-rtdb.europe-west1.firebasedatabase.app"
    val firebaseDb = FirebaseDatabase.getInstance(dbUrl)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "MSSS Tracking System",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val newRecord = TrackingRecord(
                        userId = "SOLDIER_${(1..100).random()}",
                        timestamp = System.currentTimeMillis(),
                        latitude = 45.4642 + (Random().nextDouble() - 0.5) * 0.1,
                        longitude = 9.1900 + (Random().nextDouble() - 0.5) * 0.1,
                        status = if (Random().nextBoolean()) TrackingStatus.OK else TrackingStatus.DANGER,
                        activityType = listOf(ActivityType.STILL, ActivityType.WALKING, ActivityType.RUNNING).random(),
                        isCloudSynced = false
                    )
                    viewModel.insert(newRecord)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Add Record")
            }

            Button(
                onClick = { 
                    viewModel.syncWithFirebase(firebaseDb)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Sync Firebase")
            }

            Button(
                onClick = { 
                    viewModel.fetchFromFirebase(firebaseDb)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text("Fetch Cloud")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.clearDatabase() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Clear Local DB")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Local Records: ${records.size}",
            style = MaterialTheme.typography.titleMedium
        )

        // Auto-save Status Indicator
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = Color.Green,
                    modifier = Modifier.size(10.dp)
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Auto-Save Active: Every 20 seconds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(records) { record ->
                TrackingRecordItem(record)
            }
        }
    }
}

@Composable
fun TrackingRecordItem(record: TrackingRecord) {
    val sdf = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault())
    val dateString = sdf.format(Date(record.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(text = record.userId, style = MaterialTheme.typography.titleSmall)
                
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (record.isCloudSynced) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (record.isCloudSynced) "Synced (Cloud)" else "Pending (Local)",
                        color = if (record.isCloudSynced) Color(0xFF388E3C) else Color(0xFFE65100),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(text = "Status: ${record.status} | Activity: ${record.activityType}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Pos: ${"%.4f".format(record.latitude)}, ${"%.4f".format(record.longitude)}", style = MaterialTheme.typography.bodySmall)
            Text(text = "Time: $dateString", style = MaterialTheme.typography.bodySmall)
        }
    }
}
