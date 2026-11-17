package com.ifpr.androidapptemplatear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.ifpr.androidapptemplatear.R
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch


class ChangeObjectFragment : Fragment() {

    private lateinit var sharedViewModel: SharedModelViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_changeobject,
            container,
            false
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        sharedViewModel =
            ViewModelProvider(requireActivity())[SharedModelViewModel::class.java]

        carregarModelos()
    }

    /**
     * Procura automaticamente todos os arquivos .glb
     * existentes na pasta assets.
     */
    private fun carregarModelos() {

        val container =
            view?.findViewById<LinearLayout>(R.id.categoryContainer)

        container?.removeAllViews()

        val modelos = requireContext()
            .assets
            .list("")
            ?.filter { arquivo ->
                arquivo.endsWith(".glb", ignoreCase = true)
            }
            ?.sorted()
            ?: emptyList()

        if (modelos.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "Nenhum modelo 3D encontrado.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        adicionarTitulo(container)

        modelos.forEach { arquivo ->
            adicionarModelo(container, arquivo)
        }
    }

    /**
     * Adiciona o título da lista de modelos.
     */
    private fun adicionarTitulo(container: LinearLayout?) {

        val titulo = TextView(requireContext()).apply {

            text = "Modelos 3D"

            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                20f
            )

            setTypeface(null, Typeface.BOLD)

            setTextColor(Color.BLACK)

            setPadding(
                0,
                24,
                0,
                16
            )
        }

        container?.addView(titulo)
    }

    /**
     * Cria um item da lista para cada modelo encontrado.
     */
    private fun adicionarModelo(
        container: LinearLayout?,
        arquivo: String
    ) {

        val itemLayout = LinearLayout(requireContext()).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                0,
                12,
                0,
                12
            )

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        /*
         * Remove a extensão .glb e deixa o nome
         * mais amigável para exibição.
         *
         * Exemplo:
         * pikachu.glb -> Pikachu
         * cadeira_escolar.glb -> Cadeira escolar
         */
        val nomeModelo = arquivo
            .removeSuffix(".glb")
            .removeSuffix(".GLB")
            .replace("_", " ")
            .replaceFirstChar {
                if (it.isLowerCase()) {
                    it.titlecase()
                } else {
                    it.toString()
                }
            }

        val nome = TextView(requireContext()).apply {

            text = nomeModelo

            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                17f
            )

            setTextColor(Color.DKGRAY)

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val botao = Button(requireContext()).apply {

            text = "Exibir"

            setOnClickListener {

                /*
                 * Informa para a tela da câmera
                 * qual modelo deve ser carregado.
                 */
                sharedViewModel.selectModel(arquivo)

                /*
                 * Abre a tela de Realidade Aumentada.
                 */
                findNavController().navigate(
                    R.id.navigation_camera
                )
            }
        }

        itemLayout.addView(nome)
        itemLayout.addView(botao)

        container?.addView(itemLayout)

        adicionarDivisor(container)
    }

    /**
     * Adiciona uma linha separando os modelos.
     */
    private fun adicionarDivisor(
        container: LinearLayout?
    ) {

        val divisor = View(requireContext()).apply {

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {

                topMargin = 4
                bottomMargin = 4
            }

            setBackgroundColor(Color.LTGRAY)
        }

        container?.addView(divisor)
    }
}

class CleaningFragment : Fragment() {
    private lateinit var game: SharedModelViewModel
    private var scene: SceneView? = null
    private var gem: ModelNode? = null
    private var loaded = false


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(21, 39, 51))
        }
        fun text(tagName: String, size: Float, textId: Int? = null) {
            root.addView(TextView(requireContext()).apply {
                tag = tagName
                textSize = size
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, dp(4))
                if (textId != null) setText(textId)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        text("cleaningTitle", 24f, R.string.cleaning_title)
        text("cleaningScore", 16f)
        text("instructions", 16f, R.string.cleaning_instructions)
        text("cleaningProgressLabel", 14f)
        for ((name, color) in listOf("cleaningProgress" to "#7FE2CB", "gemIntegrity" to "#FFB870")) {
            root.addView(ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = name
                max = 100
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
            }, LinearLayout.LayoutParams(-1, dp(12)))
        }
        root.addView(FrameLayout(requireContext()).apply { tag = "cleaningScene" },
            LinearLayout.LayoutParams(-1, 0, 1f))
        text("cleaningStatus", 18f, R.string.cleaning_loading)
        root.findViewWithTag<TextView>("cleaningStatus").accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        root.addView(com.google.android.material.button.MaterialButton(requireContext()).apply {
            tag = "cleaningAction"
            setText(R.string.go_back)
            minHeight = dp(48)
        }, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        game = ViewModelProvider(requireActivity())[SharedModelViewModel::class.java]
        val host = view.findViewWithTag<FrameLayout>("cleaningScene")
        val title = view.findViewWithTag<TextView>("cleaningTitle")
        val label = view.findViewWithTag<TextView>("cleaningProgressLabel")
        val status = view.findViewWithTag<TextView>("cleaningStatus")
        val action = view.findViewWithTag<Button>("cleaningAction")
        val cleaning = GemCleaningView(requireContext()).apply {
            isEnabled = false
            onRub = game::rub
        }

        fun leave() { findNavController().popBackStack() }

        action.setOnClickListener {
            if (game.isGameOver) game.restart()
            leave()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Game Over can only be dismissed by resetting the match.
                    if (!game.isGameOver) leave()
                }
            })

        game.collected.observe(viewLifecycleOwner) { score ->
            view.findViewWithTag<TextView>("cleaningScore").text =
                getString(R.string.collection_score, score)
        }
        game.cleaning.observe(viewLifecycleOwner) { state ->
            val previousDamage = cleaning.state.damage
            cleaning.state = state
            cleaning.isEnabled = loaded && state.status == CleaningStatus.CLEANING
            val progress = state.progress.toInt()
            val integrity = (100f - state.damage).toInt()
            label.text = getString(R.string.cleaning_progress, progress, integrity)
            view.findViewWithTag<ProgressBar>("cleaningProgress").progress = progress
            view.findViewWithTag<ProgressBar>("gemIntegrity").progress = integrity
            when (state.status) {
                CleaningStatus.BROKEN -> {
                    title.setText(R.string.game_over)
                    status.setText(R.string.gem_broken)
                    action.setText(R.string.play_again)
                    gem?.isVisible = false
                }
                CleaningStatus.COLLECTED -> {
                    title.setText(R.string.gem_cleaned)
                    status.setText(R.string.gem_cleaned)
                    action.setText(R.string.continue_game)
                }
                CleaningStatus.CLEANING -> {
                    title.setText(R.string.cleaning_title)
                    if (loaded) status.setText(
                        if (state.damage > previousDamage) R.string.cleaning_warning
                        else R.string.cleaning_gentle
                    )
                    action.setText(R.string.go_back)
                }
            }
        }

        try {
            val preview = SceneView(requireContext(), isOpaque = false).apply {
                lifecycle = viewLifecycleOwner.lifecycle
                cameraNode.position = Position(0f, 0f, 3f)
                cameraNode.lookAt(Position(0f, 0f, 0f))
            }
            scene = preview
            host.addView(preview, fullSize())
            host.addView(cleaning, fullSize())
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val instance = preview.modelLoader.loadModelInstance(game.cleaningModel)
                        ?: error("Modelo indisponível")
                    val node = ModelNode(instance, scaleToUnits = 1.5f,
                        centerOrigin = Position(0f, 0f, 0f)).apply {
                        isEditable = false
                        isVisible = !game.isGameOver
                    }
                    preview.addChildNode(node)
                    gem = node
                    loaded = true
                    cleaning.isEnabled = game.cleaning.value?.status == CleaningStatus.CLEANING
                    if (cleaning.isEnabled) status.setText(R.string.cleaning_gentle)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (!game.isGameOver) status.setText(R.string.cleaning_load_error)
                }
            }
        } catch (_: Exception) {
            status.setText(R.string.cleaning_load_error)
        }
    }

    private fun fullSize() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun onDestroyView() {
        loaded = false
        gem?.let { node ->
            runCatching { scene?.removeChildNode(node); node.destroy() }
        }
        gem = null
        scene?.let { preview -> runCatching { preview.lifecycle = null; preview.destroy() } }
        scene = null
        super.onDestroyView()
    }
}

/** Transparent dirt and touch layer over the actual GLB, without moving its camera. */
class GemCleaningView(context: Context) : View(context) {
    var onRub: ((Float, Long) -> Unit)? = null
    var state = CleaningState()
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dust = Random(42).let { random ->
        List(100) { Triple(random.nextFloat(), random.nextFloat(), random.nextFloat()) }
    }
    private var pointerId = -1
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private val radius: Float get() = min(width, height) * 0.42f

    init {
        isClickable = true
        contentDescription = "Área de limpeza: deslize lentamente sobre a joia"
    }

    private fun inside(x: Float, y: Float) = hypot(x - width / 2f, y - height / 2f) <= radius

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * resources.displayMetrics.density
        paint.color = Color.argb(100, 127, 226, 203)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL
        if (state.status != CleaningStatus.BROKEN) {
            val remaining = (100f - state.progress).toInt()
            dust.take(remaining).forEach { (x, y, size) ->
                val dx = cx + (x * 2f - 1f) * radius
                val dy = cy + (y * 2f - 1f) * radius
                if (inside(dx, dy)) {
                    paint.color = Color.argb(150, 128, 103, 73)
                    canvas.drawCircle(dx, dy, radius * (0.04f + size * 0.07f), paint)
                }
            }
        }
        if (state.damage > 0f) {
            paint.color = Color.argb((state.damage * 2.55f).toInt().coerceIn(0, 255), 255, 110, 100)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 4f
            val crack = Path().apply {
                moveTo(cx - radius * 0.4f, cy - radius * 0.8f)
                lineTo(cx + radius * 0.15f, cy - radius * 0.2f)
                lineTo(cx - radius * 0.2f, cy + radius * 0.15f)
                lineTo(cx + radius * 0.4f, cy + radius * 0.8f)
            }
            canvas.drawPath(crack, paint)
            paint.style = Paint.Style.FILL
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || state.status != CleaningStatus.CLEANING) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                lastTime = event.eventTime
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                // Include batched events so high refresh rates do not change the rules.
                for (sampleIndex in 0 until event.historySize) {
                    sample(event.getHistoricalX(index, sampleIndex), event.getHistoricalY(index, sampleIndex),
                        event.getHistoricalEventTime(sampleIndex))
                }
                sample(event.getX(index), event.getY(index), event.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) pointerId = -1
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                pointerId = -1
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun sample(x: Float, y: Float, time: Long) {
        if (time <= lastTime) return
        if (inside(x, y) && inside(lastX, lastY)) {
            val distanceDp = hypot(x - lastX, y - lastY) / resources.displayMetrics.density
            onRub?.invoke(distanceDp, time - lastTime)
        }
        lastX = x
        lastY = y
        lastTime = time
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
