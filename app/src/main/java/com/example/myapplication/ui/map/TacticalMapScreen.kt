package com.example.myapplication.ui.map

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import android.view.View
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.osmdroid.api.IMapView
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MapTileFilesystemProvider
import org.osmdroid.tileprovider.modules.MapTileSqlCacheProvider
import org.osmdroid.tileprovider.modules.SqliteArchiveTileWriter
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.data.local.entities.TrackingRecord
import com.example.myapplication.data.local.entities.TrackingStatus
import com.example.myapplication.logic.policy.ConfigManager
import com.example.myapplication.logic.map.MapDownloadWorker
import androidx.work.WorkInfo
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TacticalMapScreen(
    modifier: Modifier = Modifier,
    viewModel: MapViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val resources = context.resources

    // Handle Selection Mode Broadcast
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.example.myapplication.START_SELECTING_CENTER") {
                    viewModel.startSelectingCenter()
                }
            }
        }
        val filter = IntentFilter("com.example.myapplication.START_SELECTING_CENTER")
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Observe WorkManager progress
    val downloadWorkInfo by viewModel.downloadWorkInfo.collectAsState(initial = null)
    val isDownloading = downloadWorkInfo?.state == WorkInfo.State.RUNNING ||
                        downloadWorkInfo?.state == WorkInfo.State.ENQUEUED

    val isSelectingCenter by viewModel.isSelectingCenter
    val pendingCenter by viewModel.pendingCenter

    // Helper to create a marker icon with an always-visible label
    fun createLabeledIcon(iconRes: Int, label: String): BitmapDrawable {
        val baseDrawable = ContextCompat.getDrawable(context, iconRes)!!
        val paint = Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 35f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            // Add a slight shadow/outline for readability
            setShadowLayer(3f, 0f, 0f, android.graphics.Color.WHITE)
        }

        val textBounds = Rect()
        paint.getTextBounds(label, 0, label.length, textBounds)

        val width = maxOf(baseDrawable.intrinsicWidth, textBounds.width()) + 20
        val height = baseDrawable.intrinsicHeight + textBounds.height() + 20

        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw Label at the top
        canvas.drawText(label, width / 2f, textBounds.height().toFloat() + 5f, paint)

        // Draw Icon below the label
        val left = (width - baseDrawable.intrinsicWidth) / 2
        val top = textBounds.height() + 10
        baseDrawable.setBounds(left, top, left + baseDrawable.intrinsicWidth, top + baseDrawable.intrinsicHeight)
        baseDrawable.draw(canvas)

        return bitmap.toDrawable(resources)
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Initialize osmdroid configuration and caching
    SideEffect {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))
        Configuration.getInstance().userAgentValue = "MyTacticalApp/1.0 (Android; ${context.packageName})"
    }

    // Use remember to keep the MapView instance across recompositions
    val mapView = remember {
        // --- HYBRID TILE PROVIDER: AUTOMATIC OFFLINE + ONLINE ---
        val tileSource = TileSourceFactory.MAPNIK
        
        // This is the default provider but we ensure it includes the Archive provider
        // for our tactical_area.sqlite
        val tileProvider = org.osmdroid.tileprovider.MapTileProviderBasic(context, tileSource)
        
        MapView(context, tileProvider).apply {
            id = View.generateViewId()
            setMultiTouchControls(true)
            
            // --- FIX RENDERING AND ZOOM ---
            setUseDataConnection(true)
            setTilesScaledToDpi(true)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            
            // Fix vertical repetition bug
            setVerticalMapRepetitionEnabled(false)
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
            
            controller.setZoom(5.0) // Start with a broader view
        }
    }
    
    // Track if we have already centered the map once
    var hasCenteredOnce by remember { mutableStateOf(false) }

    val myLocationOverlay = remember(mapView) {
        object : MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) {
            override fun onSingleTapConfirmed(e: MotionEvent?, map: MapView?): Boolean {
                // Do nothing to prevent the default empty bubble from MyLocationNewOverlay
                return false 
            }
        }.apply {
            enableMyLocation()
            // Disable internal icons to avoid duplicates, we use our custom marker
            setPersonIcon(createBitmap(1, 1, Bitmap.Config.ARGB_8888))
            setDirectionIcon(createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }
    }

    // Determine current user's emergency status for UI feedback
    val markersState by viewModel.markers
    val userRecord = markersState.find { it.userId == ConfigManager.userId }
    val isUserInDanger = viewModel.isMonitoring.value && (userRecord?.status == TrackingStatus.DANGER)

    // Auto-follow logic: center map on the current user when mission is active and position changes
    LaunchedEffect(userRecord, viewModel.isMonitoring.value) {
        if (userRecord != null && (userRecord.latitude != 0.0 || userRecord.longitude != 0.0)) {
            val userPoint = GeoPoint(userRecord.latitude, userRecord.longitude)
            if (viewModel.isMonitoring.value) {
                mapView.controller.animateTo(userPoint)
                if (!hasCenteredOnce) {
                    mapView.controller.setZoom(17.0)
                    hasCenteredOnce = true
                }
            } else if (!hasCenteredOnce) {
                // Just center once even if not monitoring to show current location
                mapView.controller.setCenter(userPoint)
                mapView.controller.setZoom(17.0)
                hasCenteredOnce = true
            }
        }
    }

    // Observe paths to force UI update
    val pathsState by remember { derivedStateOf { viewModel.historicalPaths } }

    // Pulse animation for SOS markers
    val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 10f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "PulseRadius"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "PulseAlpha"
    )

    // Handle MapView lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    mapView.onPause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach() // CRITICAL: Release resources and stop threads
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val downloadStatus by viewModel.downloadStatus

    LaunchedEffect(downloadStatus) {
        downloadStatus?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    val selectionMarker = remember(mapView) {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            // Removed title to prevent info window
        }
    }

    val eventsOverlay = remember(mapView) {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                InfoWindow.closeAllInfoWindowsOn(mapView)
                // Also ensure any lingering windows are closed
                mapView.overlays.forEach { overlay ->
                    if (overlay is Marker) overlay.closeInfoWindow()
                }
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (viewModel.isSelectingCenter.value) {
                    p?.let { viewModel.setPendingCenter(it) }
                    return true
                }
                return false
            }
        })
    }

    // Map of persistent overlays to avoid recreation
    val persistentMarkers = remember { mutableMapOf<String, Marker>() }
    val persistentPaths = remember { mutableMapOf<String, Polyline>() }
    val pulsePolygons = remember { mutableMapOf<String, Polygon>() }
    
    // Icon cache to avoid expensive bitmap recreation during animations
    val iconCache = remember { mutableMapOf<String, BitmapDrawable>() }

    fun getCachedIcon(iconRes: Int, label: String): BitmapDrawable {
        val key = "$label-$iconRes"
        return iconCache.getOrPut(key) {
            createLabeledIcon(iconRes, label)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.LightGray)) {
        AndroidView(
            factory = { 
                mapView.apply {
                    if (!overlays.contains(myLocationOverlay)) {
                        overlays.add(myLocationOverlay)
                    }
                    if (!overlays.contains(eventsOverlay)) {
                        overlays.add(eventsOverlay)
                    }
                }
                mapView
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // Update selection marker
                if (isSelectingCenter && pendingCenter != null) {
                    selectionMarker.position = pendingCenter
                    if (!view.overlays.contains(selectionMarker)) {
                        view.overlays.add(selectionMarker)
                    }
                } else {
                    view.overlays.remove(selectionMarker)
                }

                // --- Optimized Rendering ---
                
                // 1. Manage Historical Paths
                val currentPathIds = pathsState.keys.toSet()
                val pathsToRemove = persistentPaths.keys.filter { it !in currentPathIds }
                pathsToRemove.forEach { id ->
                    view.overlays.remove(persistentPaths[id])
                    persistentPaths.remove(id)
                }

                pathsState.forEach { (userId, records) ->
                    if (records.size >= 2) {
                        val polyline = persistentPaths.getOrPut(userId) {
                            Polyline(view).apply {
                                outlinePaint.strokeWidth = 6f
                                infoWindow = null // Disable empty bubble
                                view.overlays.add(0, this) // Add at the back
                            }
                        }
                        
                        val points = records.map { GeoPoint(it.latitude, it.longitude) }
                        polyline.setPoints(points)

                        val latestStatus = records.last().status
                        polyline.outlinePaint.color = when {
                            latestStatus == TrackingStatus.DANGER -> android.graphics.Color.RED
                            userId == ConfigManager.userId -> android.graphics.Color.GREEN
                            else -> android.graphics.Color.BLUE
                        }
                    }
                }

                // 2. Manage Teammates and User Markers
                val allActiveUsers = (markersState.map { it.userId } + (userRecord?.userId ?: "")).filter { it.isNotEmpty() }.toSet()
                val markersToRemove = persistentMarkers.keys.filter { it !in allActiveUsers }
                markersToRemove.forEach { id ->
                    view.overlays.remove(persistentMarkers[id])
                    persistentMarkers.remove(id)
                    view.overlays.remove(pulsePolygons[id])
                    pulsePolygons.remove(id)
                }

                fun updateMarker(record: TrackingRecord, isMe: Boolean) {
                    val id = record.userId
                    val marker = persistentMarkers.getOrPut(id) {
                        Marker(view).apply {
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            view.overlays.add(this) // Initially add
                            setOnMarkerClickListener { m, _ ->
                                if (m.isInfoWindowShown) {
                                    m.closeInfoWindow()
                                } else {
                                    InfoWindow.closeAllInfoWindowsOn(view)
                                    m.showInfoWindow()
                                }
                                true
                            }
                        }
                    }

                    // Force marker to the top of the overlay stack
                    if (view.overlays.contains(marker)) {
                        view.overlays.remove(marker)
                        view.overlays.add(marker)
                    }
                    
                    marker.position = GeoPoint(record.latitude, record.longitude)
                    marker.title = if (isMe) "YOU: $id" else "SOLDIER: $id"
                    
                    val timeStr = timeFormatter.format(Date(record.timestamp))
                    marker.snippet = "Status: ${record.status}\nActivity: ${record.activityType}\nLast Update: $timeStr"

                    val iconRes = if (record.status == TrackingStatus.DANGER) {
                        R.drawable.ic_soldier_danger
                    } else {
                        R.drawable.ic_soldier_ok
                    }
                    marker.icon = getCachedIcon(iconRes, id)

                    if (record.status == TrackingStatus.DANGER) {
                        val pulse = pulsePolygons.getOrPut(id) {
                            Polygon(view).apply {
                                outlinePaint.color = android.graphics.Color.RED
                                outlinePaint.strokeWidth = 2f
                                infoWindow = null // Disable empty bubble
                                view.overlays.add(0, this) 
                            }
                        }
                        pulse.points = Polygon.pointsAsCircle(marker.position, pulseRadius.toDouble())
                        pulse.fillPaint.color = android.graphics.Color.argb((pulseAlpha * 255).toInt(), 255, 0, 0)
                    } else {
                        view.overlays.remove(pulsePolygons[id])
                        pulsePolygons.remove(id)
                    }
                }

                markersState.forEach { updateMarker(it, it.userId == ConfigManager.userId) }
                userRecord?.let { updateMarker(it, true) }

                view.invalidate()
            }
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
        )

        // --- Selection Mode Overlay ---
        if (isSelectingCenter) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.BottomCenter),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (pendingCenter == null) 
                            "Long-press on the map to select the center" 
                            else "Point selected. Confirm?",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.stopSelectingCenter() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Cancel")
                        }
                        
                        Button(
                            onClick = { viewModel.confirmMapCenter() },
                            enabled = pendingCenter != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Confirm")
                        }
                    }
                }
            }
        }

        // --- Mission Control Banner ---
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .align(Alignment.TopCenter),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 4.dp,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Background Download Progress
                if (isDownloading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Downloading AO map...",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { viewModel.cancelMapDownload() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val statusText = when {
                            !viewModel.isMonitoring.value -> "STATUS: INACTIVE"
                            isUserInDanger -> "STATUS: EMERGENCY"
                            else -> "STATUS: MONITORING (OK)"
                        }
                        val statusColor = when {
                            !viewModel.isMonitoring.value -> Color.Gray
                            isUserInDanger -> Color.Red
                            else -> Color(0xFF388E3C)
                        }
                        
                        Text(
                            text = statusText,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            fontSize = 14.sp
                        )
                        
                        if (viewModel.isMonitoring.value) {
                            Text(
                                text = "Duration: ${viewModel.missionDuration.value}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { 
                                if (viewModel.isMonitoring.value) viewModel.stopMission() 
                                else viewModel.startMission() 
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (viewModel.isMonitoring.value) Color.Red else Color(0xFF4CAF50)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(if (viewModel.isMonitoring.value) "Stop" else "Start", color = Color.White)
                        }
                        
                        if (viewModel.isMonitoring.value) {
                            Button(
                                onClick = { viewModel.toggleEmergency() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isUserInDanger) Color.Gray else Color.Red
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(if (isUserInDanger) "Reset" else "SOS", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}
