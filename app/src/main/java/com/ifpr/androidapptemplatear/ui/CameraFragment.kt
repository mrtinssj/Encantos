package com.ifpr.androidapptemplatear.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.ifpr.androidapptemplatear.R
import io.github.sceneview.SceneView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject


class CameraFragment : Fragment() {

    private enum class Mode {
        CHECKING,
        AR,
        CAMERA,
        STATIC
    }

    companion object {
        private const val AVAILABILITY_TIMEOUT_MS = 4000L
        private const val AR_TIMEOUT_MS = 15000L
        private const val CAMERA_TIMEOUT_MS = 8000L
    }

    private var mode = Mode.CHECKING

    private var container: FrameLayout? = null
    private var placeButton: ExtendedFloatingActionButton? = null
    private var statusText: TextView? = null
    private var overlay: View? = null
    private var collectButton: Button? = null
    private var scoreText: TextView? = null
    private var objectId = UUID.randomUUID().toString()

    private var sceneView: SceneView? = null
    private var arView: ARSceneView? = null

    private var modelNode: ModelNode? = null
    private var anchorNode: AnchorNode? = null
    private var placementHit: HitResult? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraPreview: Preview? = null

    private var loadJob: Job? = null
    private var availabilityJob: Job? = null
    private var arWatchdogJob: Job? = null
    private var cameraWatchdogJob: Job? = null

    private var generation = 0
    private var modelGeneration = 0
    private var pendingAr = false

    private var arTracking = false
    private var cameraStreaming = false

    private var currentModelFile = "coracao.glb"

    private lateinit var sharedViewModel: SharedModelViewModel

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (container != null) {
                when {
                    !granted -> startPreview(useCamera = false)
                    pendingAr -> startAr()
                    else -> startPreview(useCamera = true)
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_camera,
            parent,
            false
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        objectId = savedInstanceState?.getString("objectId") ?: objectId

        container = view.findViewById(R.id.sceneContainer)
        placeButton = view.findViewById(R.id.place)

        createControls()

        sharedViewModel =
            ViewModelProvider(requireActivity())[
                SharedModelViewModel::class.java
            ]

        val selectedModel = sharedViewModel.selectedModel.value

        if (!selectedModel.isNullOrBlank()) {
            currentModelFile = selectedModel
        } else {
            sharedViewModel.selectModel(currentModelFile)
        }

        sharedViewModel.selectedModel.observe(viewLifecycleOwner) { path ->
            if (!path.isNullOrBlank() && path != currentModelFile) {
                currentModelFile = path
                objectId = UUID.randomUUID().toString()
                loadModel()
            }
        }

        sharedViewModel.collected.observe(viewLifecycleOwner) { score ->
            scoreText?.text = getString(R.string.collection_score, score)
            updateCollectButton()
        }
        sharedViewModel.cleaning.observe(viewLifecycleOwner) { updateCollectButton() }

        placeButton?.setOnClickListener {
            when (mode) {
                Mode.AR -> placeModel()

                Mode.CAMERA,
                Mode.STATIC -> {
                    startPreview(useCamera = mode == Mode.CAMERA)
                }

                Mode.CHECKING -> Unit
            }
        }

        checkAvailability()
    }

    private fun createControls() {
        val parent = container?.parent as? ViewGroup ?: return

        val layer = FrameLayout(requireContext())

        val panel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.argb(190, 25, 30, 35))
        }

        statusText = TextView(requireContext()).apply {
            text = "Preparando visualização…"
            textSize = 13f
            setTextColor(Color.WHITE)
        }

        panel.addView(statusText)

        scoreText = TextView(requireContext()).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        panel.addView(scoreText)
        collectButton = Button(requireContext()).apply {
            setText(R.string.clean_and_collect)
            isEnabled = false
            setOnClickListener {
                if (sharedViewModel.isCollected(objectId)) {
                    objectId = UUID.randomUUID().toString()
                    loadModel()
                } else if (sharedViewModel.beginCleaning(currentModelFile, objectId)) {
                    findNavController().navigate(R.id.navigation_cleaning)
                }
            }
        }
        panel.addView(collectButton)

        layer.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(85)
            }
        )

        parent.addView(
            layer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        overlay = layer

        parent.findViewById<View>(R.id.titleImage)?.bringToFront()
        placeButton?.bringToFront()
    }

    private fun updateCollectButton() {
        if (!::sharedViewModel.isInitialized) return
        collectButton?.apply {
            setText(if (sharedViewModel.isCollected(objectId)) R.string.new_gem else R.string.clean_and_collect)
            isEnabled = !sharedViewModel.isGameOver && modelNode != null &&
                (mode != Mode.AR || anchorNode != null)
        }
    }

    /**
     * Identificação por características comuns de emuladores.
     * Por escolha deste aplicativo, eles iniciam sem ARCore.
     */

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk", ignoreCase = true) ||
                Build.MODEL.contains("Emulator", ignoreCase = true) ||
                Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
                Build.PRODUCT.contains("sdk", ignoreCase = true) ||
                Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
                Build.HARDWARE.contains("ranchu", ignoreCase = true)
    }


    private fun checkAvailability() {
        availabilityJob?.cancel()

        // Não tenta iniciar ARCore no emulador.
        if (isEmulator()) {
            requestMode(useAr = false)
            return
        }

        val expected = generation
        var resolved = false

        availabilityJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(AVAILABILITY_TIMEOUT_MS)

            if (
                !resolved &&
                container != null &&
                expected == generation
            ) {
                resolved = true
                requestMode(useAr = false)
            }
        }

        try {
            ArCoreApk.getInstance()
                .checkAvailabilityAsync(requireContext()) { availability ->
                    activity?.runOnUiThread {
                        if (
                            !resolved &&
                            container != null &&
                            expected == generation
                        ) {
                            resolved = true
                            availabilityJob?.cancel()
                            availabilityJob = null

                            val installed =
                                availability ==
                                        ArCoreApk.Availability.SUPPORTED_INSTALLED

                            requestMode(useAr = installed)
                        }
                    }
                }
        } catch (_: Exception) {
            if (
                !resolved &&
                container != null &&
                expected == generation
            ) {
                resolved = true
                availabilityJob?.cancel()
                availabilityJob = null

                requestMode(useAr = false)
            }
        }
    }

    private fun requestMode(useAr: Boolean) {
        pendingAr = useAr

        val permitted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        when {
            !permitted -> {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }

            useAr -> startAr()

            else -> startPreview(useCamera = true)
        }
    }

    private fun startAr() {
        val host = container ?: return

        clearScene()
        mode = Mode.AR

        val expected = generation

        statusText?.text = "Aponte para uma mesa ou para o chão."

        placeButton?.apply {
            text = "Posicionar"
            isEnabled = false
        }

        try {
            val ar = ARSceneView(requireContext())

            arView = ar
            sceneView = ar

            ar.configureSession { _, config ->
                config.planeFindingMode =
                    Config.PlaneFindingMode.HORIZONTAL

                config.lightEstimationMode =
                    Config.LightEstimationMode.DISABLED
            }

            ar.onSessionFailed = {
                host.post {
                    if (
                        container != null &&
                        expected == generation &&
                        mode == Mode.AR
                    ) {
                        startPreview(useCamera = true)
                    }
                }
            }

            ar.onSessionUpdated = { _, frame ->
                if (
                    expected == generation &&
                    mode == Mode.AR
                ) {
                    arTracking =
                        frame.camera.trackingState ==
                                TrackingState.TRACKING

                    if (anchorNode == null) {
                        placementHit =
                            if (
                                arTracking &&
                                ar.width > 0 &&
                                ar.height > 0
                            ) {
                                frame.hitTest(
                                    ar.width / 2f,
                                    ar.height / 2f
                                ).firstOrNull { hit ->
                                    val plane = hit.trackable as? Plane

                                    plane != null &&
                                            plane.type ==
                                            Plane.Type.HORIZONTAL_UPWARD_FACING &&
                                            plane.isPoseInPolygon(hit.hitPose)
                                }
                            } else {
                                null
                            }

                        val ready =
                            placementHit != null &&
                                    modelNode != null

                        placeButton?.isEnabled = ready

                        statusText?.text =
                            if (ready) {
                                "Superfície encontrada. Toque em Posicionar."
                            } else {
                                "Mova o aparelho devagar sobre uma superfície."
                            }
                    }
                }
            }

            ar.lifecycle = viewLifecycleOwner.lifecycle

            host.addView(ar, fullSize())

            startArWatchdog(expected)
            loadModel()

        } catch (_: Exception) {
            startPreview(useCamera = true)
        }
    }

    /**
     * Troca automaticamente quando o ARCore não consegue rastrear.
     * Conta somente o tempo em que a tela está em primeiro plano.
     * A ausência de uma superfície, sozinha, não provoca a troca.
     */
    private fun startArWatchdog(expected: Int) {
        arWatchdogJob?.cancel()

        arWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            var withoutTrackingMs = 0L
            var lastTime = SystemClock.elapsedRealtime()

            while (isActive) {
                delay(500L)

                if (
                    container == null ||
                    expected != generation ||
                    mode != Mode.AR
                ) {
                    return@launch
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastTime
                lastTime = now

                val resumed = viewLifecycleOwner.lifecycle
                    .currentState.isAtLeast(Lifecycle.State.RESUMED)

                if (!resumed || arTracking) {
                    withoutTrackingMs = 0L
                } else {
                    withoutTrackingMs += elapsed
                }

                if (withoutTrackingMs >= AR_TIMEOUT_MS) {
                    startPreview(useCamera = true)
                    return@launch
                }
            }
        }
    }

    private fun startPreview(useCamera: Boolean) {
        val host = container ?: return

        clearScene()

        mode = if (useCamera) Mode.CAMERA else Mode.STATIC

        placeButton?.apply {
            text = "Centralizar"
            isEnabled = false
        }

        statusText?.text = if (useCamera) {
            "Modelo sobre a câmera, sem fixação no ambiente."
        } else {
            "Visualização 3D sem câmera."
        }

        host.setBackgroundColor(Color.rgb(45, 55, 65))

        val cameraView = if (useCamera) {
            PreviewView(requireContext()).apply {
                implementationMode =
                    PreviewView.ImplementationMode.COMPATIBLE

                scaleType = PreviewView.ScaleType.FILL_CENTER

                host.addView(this, fullSize())
            }
        } else {
            null
        }

        try {
            val scene = SceneView(
                context = requireContext(),
                isOpaque = !useCamera
            )

            scene.lifecycle = viewLifecycleOwner.lifecycle
            scene.cameraNode.position = Position(0f, 0f, 3f)
            scene.cameraNode.lookAt(Position(0f, 0f, 0f))

            sceneView = scene

            host.addView(scene, fullSize())

            loadModel()

            if (cameraView != null) {
                openCamera(cameraView)
            }
        } catch (_: Exception) {
            statusText?.text = "Não foi possível iniciar a cena 3D."
        }
    }

    private fun openCamera(previewView: PreviewView) {
        val context = context ?: return

        val expected = generation
        val owner = viewLifecycleOwner

        // O prazo também cobre a obtenção do CameraProvider.
        startCameraWatchdog(expected)

        try {
            val future = ProcessCameraProvider.getInstance(context)

            future.addListener({
                if (
                    container == null ||
                    expected != generation ||
                    mode != Mode.CAMERA
                ) {
                    return@addListener
                }

                try {
                    val provider = future.get()

                    val selector = when {
                        provider.hasCamera(
                            CameraSelector.DEFAULT_BACK_CAMERA
                        ) -> CameraSelector.DEFAULT_BACK_CAMERA

                        provider.hasCamera(
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        ) -> CameraSelector.DEFAULT_FRONT_CAMERA

                        else -> {
                            startPreview(useCamera = false)
                            return@addListener
                        }
                    }

                    val preview = Preview.Builder().build()

                    preview.setSurfaceProvider(
                        previewView.surfaceProvider
                    )

                    cameraProvider = provider
                    cameraPreview = preview

                    previewView.previewStreamState.observe(owner) { state ->
                        if (
                            expected == generation &&
                            mode == Mode.CAMERA
                        ) {
                            cameraStreaming =
                                state == PreviewView.StreamState.STREAMING
                        }
                    }

                    provider.bindToLifecycle(
                        owner,
                        selector,
                        preview
                    )

                } catch (_: Exception) {
                    if (
                        expected == generation &&
                        container != null
                    ) {
                        startPreview(useCamera = false)
                    }
                }
            }, ContextCompat.getMainExecutor(context))

        } catch (_: Exception) {
            if (
                expected == generation &&
                container != null
            ) {
                startPreview(useCamera = false)
            }
        }
    }

    private fun startCameraWatchdog(expected: Int) {
        cameraWatchdogJob?.cancel()

        cameraWatchdogJob = viewLifecycleOwner.lifecycleScope.launch {
            var withoutCameraMs = 0L
            var lastTime = SystemClock.elapsedRealtime()

            while (isActive) {
                delay(500L)

                if (
                    container == null ||
                    expected != generation ||
                    mode != Mode.CAMERA
                ) {
                    return@launch
                }

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastTime
                lastTime = now

                val resumed = viewLifecycleOwner.lifecycle
                    .currentState.isAtLeast(Lifecycle.State.RESUMED)

                if (!resumed || cameraStreaming) {
                    withoutCameraMs = 0L
                } else {
                    withoutCameraMs += elapsed
                }

                if (withoutCameraMs >= CAMERA_TIMEOUT_MS) {
                    startPreview(useCamera = false)
                    return@launch
                }
            }
        }
    }

    private fun loadModel() {
        val scene = sceneView ?: return
        val location = modelLocation(currentModelFile)

        if (location == null) {
            statusText?.text =
                "Modelo não encontrado: $currentModelFile"

            placeButton?.isEnabled = false
            return
        }

        loadJob?.cancel()
        removeModel()

        val expected = generation
        val expectedModel = ++modelGeneration
        val isAr = mode == Mode.AR
        val fileName = currentModelFile

        placeButton?.isEnabled = false

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val instance =
                    scene.modelLoader.loadModelInstance(location)

                if (
                    expected != generation ||
                    expectedModel != modelGeneration ||
                    sceneView !== scene
                ) {
                    return@launch
                }

                if (instance == null) {
                    statusText?.text = "Não foi possível abrir o modelo."
                    return@launch
                }

                val node = ModelNode(
                    modelInstance = instance,
                    scaleToUnits = if (isAr) 0.5f else 1.5f,
                    centerOrigin = Position(0f, 0f, 0f)
                )

                node.isEditable = true
                modelNode = node

                if (!isAr) {
                    scene.addChildNode(node)
                    placeButton?.isEnabled = true
                }
                updateCollectButton()

            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (
                    expected == generation &&
                    expectedModel == modelGeneration
                ) {
                    statusText?.text =
                        "Erro ao carregar: $fileName"
                }
            }
        }
    }

    private fun placeModel() {
        val ar = arView ?: return

        if (anchorNode != null) {
            loadModel()
            return
        }

        val hit = placementHit ?: return
        val model = modelNode ?: return

        try {
            val anchor = AnchorNode(
                engine = ar.engine,
                anchor = hit.createAnchor()
            )

            anchor.addChildNode(model)
            ar.addChildNode(anchor)

            anchorNode = anchor
            placementHit = null

            placeButton?.apply {
                text = "Reposicionar"
                isEnabled = true
            }

            statusText?.text = "Objeto posicionado no ambiente."
            updateCollectButton()

        } catch (_: Exception) {
            statusText?.text =
                "Não foi possível posicionar. Tente outra superfície."
        }
    }

    private fun modelLocation(fileName: String): String? {
        val context = context ?: return null

        try {
            context.assets.open(fileName).use { }
            return fileName
        } catch (_: Exception) {
            // Procura no armazenamento interno.
        }

        val file = File(context.filesDir, fileName)

        return if (file.isFile) {
            Uri.fromFile(file).toString()
        } else {
            null
        }
    }

    private fun removeModel() {
        val scene = sceneView

        modelNode?.let { model ->
            runCatching {
                anchorNode?.removeChildNode(model)
                scene?.removeChildNode(model)
                model.destroy()
            }
        }

        modelNode = null
        collectButton?.isEnabled = false

        anchorNode?.let { anchor ->
            runCatching {
                scene?.removeChildNode(anchor)
                anchor.anchor.detach()
                anchor.destroy()
            }
        }

        anchorNode = null
        placementHit = null

        if (mode == Mode.AR) {
            placeButton?.text = "Posicionar"
        }
    }

    private fun clearScene() {
        generation++
        modelGeneration++

        arWatchdogJob?.cancel()
        arWatchdogJob = null

        cameraWatchdogJob?.cancel()
        cameraWatchdogJob = null

        loadJob?.cancel()
        loadJob = null

        cameraPreview?.let { preview ->
            runCatching {
                cameraProvider?.unbind(preview)
            }
        }

        cameraPreview = null
        cameraProvider = null

        arTracking = false
        cameraStreaming = false

        removeModel()

        sceneView?.let { scene ->
            runCatching {
                scene.lifecycle = null
                scene.destroy()
            }
        }

        sceneView = null
        arView = null

        container?.removeAllViews()
    }

    private fun fullSize() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() {
        availabilityJob?.cancel()
        availabilityJob = null

        clearScene()

        overlay?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        overlay = null
        statusText = null
        collectButton = null
        scoreText = null
        placeButton = null
        container = null

        pendingAr = false
        mode = Mode.CHECKING

        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("objectId", objectId)
        super.onSaveInstanceState(outState)
    }
}

class MapFragment : Fragment(), LocationListener {
    private var webView: WebView? = null
    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null
    private var permissionRequested = false
    private lateinit var game: SharedModelViewModel
    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            if (isResumed) startLocationUpdates()
        } else {
            showStatus("Permita a localização para encontrar objetos perto de você.")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        locationManager = requireContext().getSystemService(LocationManager::class.java)
        game = ViewModelProvider(requireActivity())[SharedModelViewModel::class.java]
        val view = WebView(requireContext())
        val assets = requireContext().assets
        val models = JSONArray(assets.list("").orEmpty().filter { it.endsWith(".glb", true) })
        webView = view.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.userAgentString += " Encantos/1.0"
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val uri = request.url
                    if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return null
                    val file = uri.path?.removePrefix("/assets/")
                    if (file == null || file.contains('/') || file.contains("..") || !file.endsWith(".glb", true)) {
                        return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
                    }
                    return try {
                        WebResourceResponse("model/gltf-binary", null, assets.open(file))
                    } catch (_: java.io.IOException) {
                        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    lastLocation?.let(::onLocationChanged)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme == "encantos" && request.url.host == "clean") {
                        val model = request.url.getQueryParameter("model") ?: return true
                        val item = request.url.getQueryParameter("item") ?: return true
                        val allowed = assets.list("").orEmpty().contains(model) && model.endsWith(".glb", true)
                        if (allowed && item.length <= 200 &&
                            findNavController().currentDestination?.id == R.id.navigation_map &&
                            game.beginCleaning(model, "map:" + item)) {
                            game.selectModel(model)
                            findNavController().navigate(R.id.navigation_cleaning)
                        }
                    } else if (request.url.toString() == "encantos://location") {
                        if (!hasLocationPermission()) {
                            if (permissionRequested && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + requireContext().packageName)))
                            } else {
                                requestLocationPermission()
                            }
                        } else if (locationManager.getProviders(true).none {
                                it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
                            }) {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } else {
                            startLocationUpdates()
                        }
                    } else if (request.url.scheme == "https") {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    }
                    return true
                }
            }
            loadDataWithBaseURL(
                "https://appassets.androidplatform.net/assets/",
                mapHtml.replace("__MODELS__", models.toString()).replace("__SCORE__", (game.collected.value ?: 0).toString()),
                "text/html", "UTF-8", null
            )
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!hasLocationPermission()) requestLocationPermission()
    }

    private fun requestLocationPermission() {
        permissionRequested = true
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        locationManager.removeUpdates(this)
        val providers = locationManager.getProviders(true).filter {
            it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
        }
        if (providers.isEmpty()) {
            showStatus("Ative o GPS e toque em Minha localização.")
            return
        }
        providers.forEach {
            locationManager.requestLocationUpdates(it, 2000L, 2f, this, Looper.getMainLooper())
        }
        providers.mapNotNull { locationManager.getLastKnownLocation(it) }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.takeIf { android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 60_000_000_000L }
            ?.let(::onLocationChanged)
    }

    override fun onLocationChanged(location: Location) {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        lastLocation = location
        webView?.evaluateJavascript(
            "window.latestLocation = [" + location.latitude + "," + location.longitude + "," + location.accuracy + "]; window.updateLocation?.(...window.latestLocation);",
            null
        )
    }

    override fun onProviderEnabled(provider: String) {
        if (isResumed) startLocationUpdates()
    }

    override fun onProviderDisabled(provider: String) {
        if (isResumed) startLocationUpdates()
    }

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun showStatus(message: String) {
        webView?.evaluateJavascript(
            "window.locationMessage = " + JSONObject.quote(message) + "; document.getElementById('status').textContent = window.locationMessage;",
            null
        )
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
        startLocationUpdates()
    }

    override fun onPause() {
        locationManager.removeUpdates(this)
        webView?.onPause()
        webView?.pauseTimers()
        super.onPause()
    }

    override fun onDestroyView() {
        locationManager.removeUpdates(this)
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }

    private val mapHtml = """
        <!doctype html>
        <html lang="pt-BR">
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <style>
        html,body,#map{height:100%;margin:0}body{font:15px sans-serif}
        #hud{position:absolute;top:12px;left:12px;right:12px;z-index:1000;background:white;padding:12px;border-radius:12px;box-shadow:0 2px 8px #0003;pointer-events:none}
        #status{margin-top:6px;font-size:13px;color:#555}
        #locate{position:absolute;bottom:32px;right:12px;z-index:1000;background:white;padding:14px;border-radius:12px;color:#1565c0;text-decoration:none;box-shadow:0 2px 8px #0003}
        .collectible{background:transparent;border:0;cursor:pointer}
        .collectible:focus{outline:2px solid #1565c0;border-radius:50%}
        #models{position:absolute;inset:0;z-index:450;pointer-events:none}
        .leaflet-top{top:90px}
        </style>
        <script type="importmap">{"imports":{"three":"https://unpkg.com/three@0.160.1/build/three.module.js","three/addons/":"https://unpkg.com/three@0.160.1/examples/jsm/"}}</script>
        </head>
        <body>
        <div id="map"></div>
        <div id="hud"><strong>Coletados: <span id="count">0</span></strong><div id="status">Obtendo sua localização…</div></div>
        <a id="locate" href="encantos://location">Minha localização</a>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <script>
        window.addEventListener('error', function() {
            document.getElementById('status').textContent = 'Não foi possível carregar o mapa 3D. Verifique a internet e reabra o aplicativo.';
        });
        if (!window.L) document.getElementById('status').textContent = 'Sem conexão. Verifique a internet e reabra o aplicativo.';
        </script>
        <script type="module">
        import * as THREE from 'three';
        import {GLTFLoader} from 'three/addons/loaders/GLTFLoader.js';
        import {clone} from 'three/addons/utils/SkeletonUtils.js';

        const status = document.getElementById('status');
        const count = document.getElementById('count');


        const collected = __SCORE__;

        count.textContent = collected;
        const map = L.map('map', {zoomControl: true}).setView([0, 0], 2);
        const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
        }).addTo(map);
        tiles.on('tileerror', () => status.textContent = 'Não foi possível carregar o mapa. Verifique a internet.');
        const scene = new THREE.Scene();
        scene.add(new THREE.HemisphereLight(0xffffff, 0x888888, 3));
        const light = new THREE.DirectionalLight(0xffffff, 3);
        light.position.set(-100, 200, 300);
        scene.add(light);
        const camera = new THREE.OrthographicCamera(0, 1, 1, 0, 0.1, 2000);
        camera.position.z = 1000;
        const renderer = new THREE.WebGLRenderer({alpha: true, antialias: true});
        renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
        renderer.domElement.id = 'models';
        document.getElementById('map').appendChild(renderer.domElement);
        function resize() {
            const size = map.getSize();
            renderer.setSize(size.x, size.y);
            camera.right = size.x;
            camera.top = size.y;
            camera.updateProjectionMatrix();
        }
        map.on('resize', resize);
        resize();
        const templates = [];
        const objects = [];
        let player, accuracyCircle, current, spawnCenter;
        let follow = true;
        map.on('dragstart', () => follow = false);
        const loader = new GLTFLoader();
        const files = __MODELS__;
        let pending = files.length;
        function modelLoaded() {
            if (--pending) return;
            if (!templates.length) status.textContent = 'Não foi possível carregar os objetos 3D. Reabra o aplicativo.';
            else if (current) { populate(); status.textContent = 'Toque em uma joia para limpar e coletar.'; }
        }
        for (const file of files) {
            loader.load(file, gltf => {
                const model = gltf.scene;
                const box = new THREE.Box3().setFromObject(model);
                const size = box.getSize(new THREE.Vector3());
                const center = box.getCenter(new THREE.Vector3());
                const scale = 48 / (Math.max(size.x, size.y, size.z) || 1);
                model.position.copy(center).multiplyScalar(-scale);
                model.scale.setScalar(scale);
                const group = new THREE.Group();
                group.add(model);
                templates.push({group, file, name: file.replace(/\.glb$/i, '').replaceAll('_', ' ')});
                modelLoaded();
            }, undefined, modelLoaded);
        }
        if (!files.length) status.textContent = 'Nenhum modelo 3D disponível.';
        function remove(item) {
            map.removeLayer(item.marker);
            scene.remove(item.model);
            objects.splice(objects.indexOf(item), 1);
        }
        function populate() {
            if (!current || !templates.length || pending) return;
            for (const item of [...objects]) {
                if (map.distance(current, item.position) > 250) remove(item);
            }
            const missing = 8 - objects.length;
            for (let i = 0; i < missing; i++) {
                const angle = Math.random() * Math.PI * 2;
                const distance = 25 + Math.random() * 100;
                const latitude = current.lat * Math.PI / 180;
                const longitude = current.lng * Math.PI / 180;
                const arc = distance / 6371000;
                const lat = Math.asin(Math.sin(latitude) * Math.cos(arc) + Math.cos(latitude) * Math.sin(arc) * Math.cos(angle));
                const lng = longitude + Math.atan2(Math.sin(angle) * Math.sin(arc) * Math.cos(latitude), Math.cos(arc) - Math.sin(latitude) * Math.sin(lat));
                const position = L.latLng(lat * 180 / Math.PI, ((lng * 180 / Math.PI + 540) % 360) - 180);
                const template = templates[Math.floor(Math.random() * templates.length)];
                const model = clone(template.group);
                scene.add(model);
                const marker = L.marker(position, {
                    icon: L.divIcon({className: 'collectible', iconSize: [64, 64], iconAnchor: [32, 32]}),
                    title: 'Coletar ' + template.name,
                    alt: 'Coletar ' + template.name,
                    keyboard: true
                }).addTo(map);
                const item = {model, marker, position, phase: Math.random() * Math.PI * 2};
                objects.push(item);
                marker.on('click', () => {
                    if (!objects.includes(item)) return;
                    const itemId = position.lat + ':' + position.lng + ':' + template.file;
                    window.location.href = 'encantos://clean?model=' + encodeURIComponent(template.file) + '&item=' + encodeURIComponent(itemId);
                });
            }
        }
        window.updateLocation = (latitude, longitude, accuracy) => {
            const first = !current;
            current = L.latLng(latitude, longitude);
            if (!player) {
                accuracyCircle = L.circle(current, {radius: accuracy, weight: 1, color: '#1976d2', fillOpacity: 0.08, interactive: false}).addTo(map);
                player = L.circleMarker(current, {radius: 8, color: 'white', weight: 3, fillColor: '#1976d2', fillOpacity: 1, interactive: false}).addTo(map);
            } else {
                player.setLatLng(current);
                accuracyCircle.setLatLng(current).setRadius(accuracy);
            }
            if (first) map.setView(current, 18);
            else if (follow) map.panTo(current, {animate: false});
            if (!spawnCenter || map.distance(spawnCenter, current) >= 20) {
                spawnCenter = current;
                populate();
            }
            status.textContent = pending ? 'Carregando objetos 3D…' : templates.length ? 'Toque em uma joia para limpar e coletar.' : 'Nenhum objeto 3D disponível.';
        };
        document.getElementById('locate').addEventListener('click', () => {
            follow = true;
            if (current) map.setView(current, 18);
        });
        if (window.locationMessage) status.textContent = window.locationMessage;
        if (window.latestLocation) window.updateLocation(...window.latestLocation);
        renderer.setAnimationLoop(time => {
            const height = map.getSize().y;
            for (const item of objects) {
                const point = map.latLngToContainerPoint(item.position);
                item.model.position.set(point.x, height - point.y + Math.sin(time / 500 + item.phase) * 4, 0);
                item.model.rotation.y = time / 1500 + item.phase;
            }
            renderer.render(scene, camera);
        });
        </script>
        </body>
        </html>
    """.trimIndent()
}
