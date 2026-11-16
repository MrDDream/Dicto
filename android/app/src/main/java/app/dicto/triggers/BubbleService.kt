package app.dicto.triggers

import app.dicto.i18n.tr
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.DictoApp
import app.dicto.R
import app.dicto.dictation.DictationTarget
import app.dicto.dictation.applyEdits
import app.dicto.dictation.joinChunks
import app.dicto.ime.KeyboardLink
import app.dicto.ui.MainActivity
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import uniffi.dicto_ffi.DictationResult
import uniffi.dicto_ffi.Mode
import kotlin.math.abs

/**
 * Fenêtres par-dessus les autres apps (permission « Afficher par-dessus les autres apps ») :
 * - la bulle flottante (écran « Bulle flottante en usage ») : toucher = mode choisi,
 *   appui long = maintenu, glisser = déplacer. Le panneau « Je t'écoute » s'affiche
 *   au-dessus de l'app sans lui prendre le focus, pour pouvoir y écrire le texte ;
 * - la zone de geste sur le bord droit : glisser vers l'intérieur ouvre la dictée rapide.
 */
class BubbleService : Service(), DictationTarget {
    private val owner = OverlayOwner()
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var panel: View? = null
    private var edge: View? = null
    private var dismissTarget: View? = null
    private var nearDismiss by mutableStateOf(false)
    private var panelParams: WindowManager.LayoutParams? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var chunks by mutableStateOf(emptyList<String>())
    private var panelVisible by mutableStateOf(false)
    private val controller get() = DictoApp.instance.dictation

    override val targetApp: String? get() = KeyboardLink.lastApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = DictoApp.instance.settings
        if (intent?.action == ACTION_HIDE_BUBBLE) settings.bubble.set(false)
        val wantBubble = settings.bubble.value
        val wantEdge = settings.edgeSwipe.value
        val run = Settings.canDrawOverlays(this) && (wantBubble || wantEdge)
        // Les fenêtres d'abord : quand Android relance le service tout seul (après l'avoir arrêté
        // pour libérer de la mémoire), il n'autorise le premier plan que si une fenêtre flottante
        // de l'app est déjà visible (Android 15).
        if (run) {
            if (wantBubble && bubble == null) showBubble() else if (!wantBubble) hideBubble()
            if (wantEdge) showEdge() else hideEdge()
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(wantBubble),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: RuntimeException) {
            android.util.Log.w("DictoBubble", "premier plan refusé", e)
        }
        if (!run) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun showBubble() {
        val density = resources.displayMetrics.density
        val storage = DictoApp.instance.storage
        bubbleParams = WindowManager.LayoutParams(
            (60 * density).toInt(), (60 * density).toInt(), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            y = storage.setting("bubble.y")?.toIntOrNull() ?: (290 * density).toInt()
        }
        val view = owner.composeView(this) { BubbleContent() }
        wm.addView(view, bubbleParams)
        bubble = view

    }

    /**
     * Panneau « Je t'écoute » en haut de l'écran, sous la barre d'état : loin du clavier et de la
     * barre de navigation. Sa fenêtre n'existe que pendant qu'il s'affiche, et elle est créée avec
     * son contenu, pour qu'Android la mesure à la bonne taille.
     */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun showPanel() {
        panelVisible = true
        if (panel != null) return
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = panelY ?: (statusBarHeight() + (8 * density).toInt())
        }
        panelParams = params
        panelHeight = -1
        val view = owner.composeView(this) {
            // Si le panneau grandit (texte dicté), on demande à Android de suivre.
            Box(Modifier.onSizeChanged { resizePanel(it.height) }) {
                DictationPanel(
                    text = joinChunks(chunks),
                    onCancel = ::closePanel,
                    onInsert = {
                        TextInserter.insert(this@BubbleService, joinChunks(chunks))
                        closePanel()
                    },
                    onMicPress = ::press,
                    onMicRelease = controller::release,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    dragModifier = Modifier.pointerInteropFilter(onTouchEvent = ::onHandleTouch),
                )
            }
        }
        runCatching { wm.addView(view, params) }.onSuccess { panel = view }
    }

    private fun hidePanelWindow() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private fun statusBarHeight(): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        } else {
            (24 * resources.displayMetrics.density).toInt()
        }

    private var panelHeight = -1

    /** Position choisie en faisant glisser le panneau, gardée tant que la bulle est active. */
    private var panelY: Int? = null

    private var lastRawY = 0f

    /**
     * Glisser la poignée du panneau. On suit la position du doigt sur l'écran (rawY) : elle ne
     * dépend pas de la fenêtre, qui bouge sous le doigt pendant le glissement.
     */
    private fun onHandleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> lastRawY = e.rawY
            MotionEvent.ACTION_MOVE -> {
                dragPanel(e.rawY - lastRawY)
                lastRawY = e.rawY
            }
        }
        return true
    }

    private fun dragPanel(dy: Float) {
        val view = panel ?: return
        val params = panelParams ?: return
        val max = (screenHeight() - maxOf(panelHeight, 0)).coerceAtLeast(0)
        params.y = (params.y + dy.toInt()).coerceIn(statusBarHeight(), maxOf(max, statusBarHeight()))
        panelY = params.y
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun resizePanel(height: Int) {
        val view = panel ?: return
        val params = panelParams ?: return
        if (height == panelHeight) return
        panelHeight = height
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun hideBubble() {
        closePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    /** Fine bande invisible sur le bord droit : glisser vers la gauche ouvre la dictée rapide. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showEdge() {
        if (edge != null) return
        val density = resources.displayMetrics.density
        val view = View(this).apply {
            contentDescription = tr("Zone de geste Dicto : glisser vers la gauche pour dicter")
            var downX = 0f
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> downX = e.rawX
                    MotionEvent.ACTION_UP -> if (downX - e.rawX > 40 * density) QuickDictateActivity.launch(this@BubbleService, "edge")
                }
                true
            }
            // Sans ça, la navigation par gestes prendrait ce glissement pour « Retour ».
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
            }
        }
        val params = WindowManager.LayoutParams(
            (14 * density).toInt(), (180 * density).toInt(), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        wm.addView(view, params)
        edge = view
    }

    private fun hideEdge() {
        edge?.let { runCatching { wm.removeView(it) } }
        edge = null
    }

    @androidx.compose.runtime.Composable
    private fun BubbleContent() {
        val ui by controller.ui.collectAsStateWithLifecycle()
        val active = ui.isActive && controller.target === this
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(10.dp, RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp))
                    .clip(RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp))
                    .background(Dicto.Surface)
                    .semantics {
                        role = Role.Button
                        contentDescription = if (active) tr("Arrêter la dictée") else tr("Dicter avec Dicto")
                        onClick { tap(); true }
                    }
                    .pointerInput(Unit) { gestures() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (active) Dicto.Recording else Dicto.Accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(DictoIcons.MicBold, null, tint = Dicto.OnAccent, modifier = Modifier.size(20.dp)) }
            }
        }
    }

    /** Toucher, appui long (maintenu) ou glisser verticalement. */
    private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.gestures() {
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                var dragged = false
                var totalY = 0f
                // Pas de mouvement pendant 400 ms : appui long.
                val released: Boolean? = withTimeoutOrNull(LONG_PRESS_MS) {
                    var up = false
                    while (!up) {
                        val change = awaitPointerEvent().changes.first()
                        totalY += change.positionChange().y
                        if (abs(totalY) > viewConfiguration.touchSlop) dragged = true
                        if (dragged) moveBy(change.positionChange().y)
                        change.consume()
                        up = !change.pressed
                    }
                    true
                }
                when {
                    released == true && !dragged -> tap()
                    released == true -> endDrag()
                    dragged -> { // glissement qui a duré plus de 400 ms
                        while (awaitPointerEvent().changes.first().also { moveBy(it.positionChange().y); it.consume() }.pressed) Unit
                        endDrag()
                    }
                    else -> { // appui long : maintenu jusqu'au relâchement
                        press(Mode.HOLD)
                        while (awaitPointerEvent().changes.first().pressed) Unit
                        controller.release()
                    }
                }
                down.consume()
            }
        }
    }

    private fun moveBy(dy: Float) {
        val view = bubble ?: return
        showDismissTarget()
        val density = resources.displayMetrics.density
        bubbleParams.y = (bubbleParams.y + dy.toInt()).coerceIn(0, screenHeight() - (60 * density).toInt())
        runCatching { wm.updateViewLayout(view, bubbleParams) }
        nearDismiss = inDismissZone()
    }

    /** Fin d'un glissement : lâchée sur la croix, la bulle se désactive ; sinon elle reste là. */
    private fun endDrag() {
        hideDismissTarget()
        if (inDismissZone()) {
            DictoApp.instance.settings.bubble.set(false)
            refresh(this)
            android.widget.Toast.makeText(this, tr("Bulle masquée. Réactive-la dans Dicto › Déclencheurs."), android.widget.Toast.LENGTH_LONG).show()
        } else {
            saveY()
        }
    }

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height() else resources.displayMetrics.heightPixels

    /** Le centre de la bulle est sur la croix (pas seulement « assez bas »), pour ne pas la masquer par mégarde. */
    private fun inDismissZone(): Boolean {
        val density = resources.displayMetrics.density
        val bubbleCenter = bubbleParams.y + 30 * density
        val targetCenter = screenHeight() - (40 + 44) * density
        return kotlin.math.abs(bubbleCenter - targetCenter) < 40 * density
    }

    /** Croix en bas à droite, visible pendant qu'on déplace la bulle. */
    private fun showDismissTarget() {
        if (dismissTarget != null) return
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            (88 * density).toInt(), (88 * density).toInt(), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            y = (40 * density).toInt()
        }
        val view = owner.composeView(this) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(if (nearDismiss) 64.dp else 52.dp).clip(CircleShape)
                        .background(if (nearDismiss) Dicto.Recording else Dicto.Ink.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(DictoIcons.Close, tr("Masquer la bulle"), tint = Dicto.OnAccent, modifier = Modifier.size(22.dp)) }
            }
        }
        runCatching { wm.addView(view, params) }.onSuccess { dismissTarget = view }
    }

    private fun hideDismissTarget() {
        dismissTarget?.let { runCatching { wm.removeView(it) } }
        dismissTarget = null
        nearDismiss = false
    }

    private fun saveY() = DictoApp.instance.storage.setSetting("bubble.y", bubbleParams.y.toString())

    private fun tap() {
        if (controller.ui.value.isActive && controller.target === this) {
            controller.press()
            controller.release()
        } else {
            // Un simple toucher ne peut pas « maintenir » : marche/arrêt dans ce cas.
            press(if (DictoApp.instance.settings.mode.value == Mode.HOLD) Mode.PUSH_TO_TALK else null)
            controller.release()
        }
    }

    private fun press(mode: Mode? = null) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!controller.ui.value.isActive) {
            chunks = emptyList()
            controller.target = this
        }
        showPanel()
        if (mode != null) controller.pressWithMode(mode) else controller.press()
    }

    private fun closePanel() {
        if (controller.ui.value.isActive && controller.target === this) controller.cancel()
        if (controller.target === this) controller.target = null
        chunks = emptyList()
        panelVisible = false
        hidePanelWindow()
    }

    override fun deliver(result: DictationResult) {
        chunks = applyEdits(chunks, result.edits)
    }

    override fun onDestroy() {
        hideDismissTarget()
        hideBubble()
        hideEdge()
        owner.destroy()
        super.onDestroy()
    }

    private fun notification(withBubble: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, DictoApp.CHANNEL_DICTATION)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (withBubble) tr("Bulle Dicto active") else tr("Geste Dicto actif"))
            .setContentText(
                if (withBubble) tr("Touche la bulle pour dicter dans n'importe quelle app.")
                else tr("Glisse depuis le bord droit de l'écran pour dicter."),
            )
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .apply {
                if (withBubble) {
                    val hide = PendingIntent.getService(
                        this@BubbleService, 1, Intent(this@BubbleService, BubbleService::class.java).setAction(ACTION_HIDE_BUBBLE),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(0, tr("Masquer la bulle"), hide)
                }
            }
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val ACTION_HIDE_BUBBLE = "app.dicto.HIDE_BUBBLE"
        private const val LONG_PRESS_MS = 400L

        fun canRun(context: Context) = Settings.canDrawOverlays(context)

        /** Démarre, met à jour ou arrête le service selon la bulle et le geste demandés. */
        fun refresh(context: Context) {
            val settings = DictoApp.instance.settings
            if (canRun(context) && (settings.bubble.value || settings.edgeSwipe.value)) {
                ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
            } else {
                context.stopService(Intent(context, BubbleService::class.java))
            }
        }
    }
}
