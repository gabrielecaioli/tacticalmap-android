package com.example.myapplication.ui.map

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MapTileFilesystemProvider
import org.osmdroid.tileprovider.modules.MapTileSqlCacheProvider
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerBottomSheet(
    onDismiss: () -> Unit,
    onConfirm: (GeoPoint) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedPoint by remember { mutableStateOf<GeoPoint?>(null) }
    val sheetState = rememberModalBottomSheetState()

    val mapView = remember {
        val tileSource = TileSourceFactory.MAPNIK
        val tileProvider = org.osmdroid.tileprovider.MapTileProviderBasic(context, tileSource)
        
        MapView(context, tileProvider).apply {
            setMultiTouchControls(true)
            
            // --- FIX RENDERING AND ZOOM ---
            setUseDataConnection(true)
            setTilesScaledToDpi(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0

            // Fix vertical repetition bug
            setVerticalMapRepetitionEnabled(false)
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)

            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(45.0, 9.0))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(0.8f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Long-press on the map to select the center",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    factory = { ctx ->
                        mapView.apply {
                            val marker = Marker(this)
                            
                            val eventsReceiver = object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                                
                                override fun longPressHelper(p: GeoPoint?): Boolean {
                                    p?.let {
                                        selectedPoint = it
                                        marker.position = it
                                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                        if (!overlays.contains(marker)) {
                                            overlays.add(marker)
                                        }
                                        invalidate()
                                    }
                                    return true
                                }
                            }
                            overlays.add(MapEventsOverlay(eventsReceiver))
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = onDismiss) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        selectedPoint?.let { onConfirm(it) }
                    },
                    enabled = selectedPoint != null
                ) {
                    Text("Confirm Center")
                }
            }
        }
    }
}
