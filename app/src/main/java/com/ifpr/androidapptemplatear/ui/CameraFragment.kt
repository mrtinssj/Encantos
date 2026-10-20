package com.ifpr.androidapptemplatear.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.ifpr.androidapptemplatear.R
import org.json.JSONArray
import org.json.JSONObject

class CameraFragment : Fragment(), LocationListener {
    private var webView: WebView? = null
    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null
    private var permissionRequested = false
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
        val view = inflater.inflate(R.layout.fragment_camera, container, false)
        val assets = requireContext().assets
        val models = JSONArray(assets.list("").orEmpty().filter { it.endsWith(".glb", true) })
        webView = view.findViewById<WebView>(R.id.mapView).apply {
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
                    if (uri.path == "/raw/blin.mp3") {
                        return WebResourceResponse("audio/mpeg", null, view.resources.openRawResource(R.raw.blin))
                    }
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
                    if (request.url.toString() == "encantos://location") {
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
                mapHtml.replace("__MODELS__", models.toString()),
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
        const collectSound = new Audio('../raw/blin.mp3');
        collectSound.preload = 'auto';
        let collected = 0;
        try { collected = Math.max(0, Number(localStorage.getItem('collected')) || 0); } catch (_) {}
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
            else if (current) { populate(); status.textContent = 'Caminhe e toque nos objetos para coletar.'; }
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
                templates.push({group, name: file.replace(/\.glb$/i, '').replaceAll('_', ' ')});
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
                    remove(item);
                    count.textContent = ++collected;
                    try { localStorage.setItem('collected', String(collected)); } catch (_) {}
                    collectSound.currentTime = 0;
                    collectSound.play().catch(() => {});
                    status.textContent = template.name + ' coletado! Continue caminhando.';
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
            status.textContent = pending ? 'Carregando objetos 3D…' : templates.length ? 'Caminhe e toque nos objetos para coletar.' : 'Nenhum objeto 3D disponível.';
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
