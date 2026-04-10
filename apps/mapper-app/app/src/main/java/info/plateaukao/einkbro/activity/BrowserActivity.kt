package info.plateaukao.einkbro.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE
import android.app.DownloadManager.Request
import android.app.PictureInPictureParams
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.Rect
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.InputType
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.view.ScaleGestureDetector
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.VideoView
import kotlin.math.abs
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.Insets
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.lifecycle.lifecycleScope
import dev.fishit.mapper.wave01.debug.RuntimeToolkitMissionWizard
import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.browser.AlbumController
import info.plateaukao.einkbro.browser.BrowserContainer
import info.plateaukao.einkbro.browser.BrowserController
import info.plateaukao.einkbro.database.Article
import info.plateaukao.einkbro.database.Bookmark
import info.plateaukao.einkbro.database.BookmarkManager
import info.plateaukao.einkbro.database.Highlight
import info.plateaukao.einkbro.database.Record
import info.plateaukao.einkbro.database.RecordDb
import info.plateaukao.einkbro.mapper.Wave02SessionLogger
import info.plateaukao.einkbro.view.MainActivityLayout
import info.plateaukao.einkbro.epub.EpubManager
import info.plateaukao.einkbro.preference.AlbumInfo
import info.plateaukao.einkbro.preference.ChatGPTActionInfo
import info.plateaukao.einkbro.preference.ConfigManager
import info.plateaukao.einkbro.preference.DarkMode
import info.plateaukao.einkbro.preference.FabPosition
import info.plateaukao.einkbro.preference.FontType
import info.plateaukao.einkbro.preference.GptActionDisplay
import info.plateaukao.einkbro.preference.GptActionScope
import info.plateaukao.einkbro.preference.HighlightStyle
import info.plateaukao.einkbro.preference.NewTabBehavior
import info.plateaukao.einkbro.preference.TranslationMode
import info.plateaukao.einkbro.preference.toggle
import info.plateaukao.einkbro.search.suggestion.SearchSuggestionViewModel
import info.plateaukao.einkbro.service.ClearService
import info.plateaukao.einkbro.unit.BackupUnit
import info.plateaukao.einkbro.unit.BrowserUnit
import info.plateaukao.einkbro.unit.BrowserUnit.createDownloadReceiver
import info.plateaukao.einkbro.unit.HelperUnit
import info.plateaukao.einkbro.unit.HelperUnit.toNormalScheme
import info.plateaukao.einkbro.unit.IntentUnit
import info.plateaukao.einkbro.unit.LocaleManager
import info.plateaukao.einkbro.unit.ShareUtil
import info.plateaukao.einkbro.unit.ViewUnit
import info.plateaukao.einkbro.unit.pruneWebTitle
import info.plateaukao.einkbro.unit.toRawPoint
import info.plateaukao.einkbro.util.Constants.Companion.ACTION_DICT
import info.plateaukao.einkbro.util.TranslationLanguage
import info.plateaukao.einkbro.view.TranslationPanelView
import info.plateaukao.einkbro.view.EBToast
import info.plateaukao.einkbro.view.EBWebView
import info.plateaukao.einkbro.view.ZoomableFrameLayout
import info.plateaukao.einkbro.view.MultitouchListener
import info.plateaukao.einkbro.view.SwipeTouchListener
import info.plateaukao.einkbro.view.dialog.BookmarkEditDialog
import info.plateaukao.einkbro.view.dialog.DialogManager
import info.plateaukao.einkbro.view.dialog.ReceiveDataDialog
import info.plateaukao.einkbro.view.dialog.SendLinkDialog
import info.plateaukao.einkbro.view.dialog.TextInputDialog
import info.plateaukao.einkbro.view.dialog.TranslationLanguageDialog
import info.plateaukao.einkbro.view.dialog.TtsLanguageDialog
import info.plateaukao.einkbro.view.compose.AutoCompleteTextField
import info.plateaukao.einkbro.view.compose.ComposedSearchBar
import info.plateaukao.einkbro.view.compose.MyTheme
import info.plateaukao.einkbro.view.dialog.compose.ActionModeMenu
import info.plateaukao.einkbro.view.dialog.compose.BookmarksDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.ContextMenuDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.ContextMenuItemType
import info.plateaukao.einkbro.view.dialog.compose.FastToggleDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.FontBoldnessDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.FontDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.LanguageSettingDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.MenuDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.ReaderFontDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.PageAiActionDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.ShowEditGptActionDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.TouchAreaDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.TranslateDialogFragment
import info.plateaukao.einkbro.view.dialog.compose.TranslationConfigDlgFragment
import info.plateaukao.einkbro.view.dialog.compose.TtsSettingDialogFragment
import info.plateaukao.einkbro.view.dialog.mission.MissionExportHistoryDialogFragment
import info.plateaukao.einkbro.view.dialog.mission.MissionExportSummaryDialogFragment
import info.plateaukao.einkbro.view.dialog.mission.MissionFixtureReplayDialogFragment
import info.plateaukao.einkbro.view.dialog.mission.MissionLauncherDialogFragment
import info.plateaukao.einkbro.view.dialog.mission.MissionWizardSetupDialogFragment
import info.plateaukao.einkbro.view.handlers.GestureHandler
import info.plateaukao.einkbro.view.handlers.MenuActionHandler
import info.plateaukao.einkbro.view.handlers.ToolbarActionHandler
import info.plateaukao.einkbro.view.viewControllers.ComposeToolbarViewController
import info.plateaukao.einkbro.view.viewControllers.FabImageViewController
import info.plateaukao.einkbro.view.viewControllers.OverviewDialogController
import info.plateaukao.einkbro.view.viewControllers.TouchAreaViewController
import info.plateaukao.einkbro.view.viewControllers.TwoPaneController
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState.DeeplTranslate
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState.GoogleTranslate
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState.Gpt
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState.HighlightText
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState.Idle
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState.Naver
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState.Papago
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState.SelectParagraph
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState.SelectSentence
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState.SplitSearch
import info.plateaukao.einkbro.viewmodel.ActionModeMenuState.Tts
import info.plateaukao.einkbro.viewmodel.ActionModeMenuViewModel
import info.plateaukao.einkbro.viewmodel.AlbumViewModel
import info.plateaukao.einkbro.viewmodel.BookmarkViewModel
import info.plateaukao.einkbro.viewmodel.BookmarkViewModelFactory
import info.plateaukao.einkbro.viewmodel.ExternalSearchViewModel
import info.plateaukao.einkbro.viewmodel.InstapaperViewModel
import info.plateaukao.einkbro.viewmodel.RemoteConnViewModel
import info.plateaukao.einkbro.viewmodel.SplitSearchViewModel
import info.plateaukao.einkbro.viewmodel.TRANSLATE_API
import info.plateaukao.einkbro.viewmodel.TranslationViewModel
import info.plateaukao.einkbro.viewmodel.TtsViewModel
import io.github.edsuns.adfilter.AdFilter
import io.github.edsuns.adfilter.FilterViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.android.ext.android.inject
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt


open class BrowserActivity : FragmentActivity(),
    BrowserController,
    MissionLauncherDialogFragment.Host,
    MissionWizardSetupDialogFragment.Host,
    MissionExportSummaryDialogFragment.Host,
    MissionFixtureReplayDialogFragment.Host,
    MissionExportHistoryDialogFragment.Host {
    private lateinit var progressBar: ProgressBar
    protected lateinit var ebWebView: EBWebView
    protected open var shouldRunClearService: Boolean = true

    private var videoView: VideoView? = null
    private var customView: View? = null
    private var languageLabelView: TextView? = null


    // Layouts
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var mainContentLayout: FrameLayout
    private lateinit var translationPanelView: TranslationPanelView

    private var fullscreenHolder: FrameLayout? = null

    // Others
    private var downloadReceiver: BroadcastReceiver? = null
    private lateinit var wave02Logger: Wave02SessionLogger
    private val config: ConfigManager by inject()
    private val backupUnit: BackupUnit by lazy { BackupUnit(this) }

    private val ttsViewModel: TtsViewModel by viewModels()

    private val translationViewModel: TranslationViewModel by viewModels()

    private val splitSearchViewModel: SplitSearchViewModel by viewModels()

    private val remoteConnViewModel: RemoteConnViewModel by viewModels()

    private val externalSearchViewModel: ExternalSearchViewModel by viewModels()

    private val instapaperViewModel: InstapaperViewModel by viewModels()

    private val searchSuggestionViewModel: SearchSuggestionViewModel by inject()

    private val keyHandler: KeyHandler by lazy { KeyHandler(this, ebWebView, config) }

    private fun prepareRecord(): Boolean {
        val webView = currentAlbumController as EBWebView
        val title = webView.title
        val url = webView.url
        return (title.isNullOrEmpty() || url.isNullOrEmpty()
                || url.startsWith(BrowserUnit.URL_SCHEME_ABOUT)
                || url.startsWith(BrowserUnit.URL_SCHEME_MAIL_TO)
                || url.startsWith(BrowserUnit.URL_SCHEME_INTENT))
    }

    private var originalOrientation = 0
    private var searchOnSite = false
    private var customViewCallback: CustomViewCallback? = null
    private var currentAlbumController: AlbumController? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private lateinit var binding: MainActivityLayout

    private val bookmarkManager: BookmarkManager by inject()

    private val epubManager: EpubManager by lazy { EpubManager(this) }

    private var uiMode = Configuration.UI_MODE_NIGHT_UNDEFINED

    private var shouldLoadTabState: Boolean = false

    private val toolbarActionHandler: ToolbarActionHandler by lazy {
        ToolbarActionHandler(this)
    }

    private val albumViewModel: AlbumViewModel by viewModels()

    private val externalSearchWebView: WebView by lazy {
        BrowserUnit.createNaverDictWebView(this)
    }

    protected val composeToolbarViewController: ComposeToolbarViewController by lazy {
        ComposeToolbarViewController(
            binding.composeIconBar,
            albumViewModel.albums,
            ttsViewModel,
            { toolbarActionHandler.handleClick(it) },
            { toolbarActionHandler.handleLongClick(it) },
            onTabClick = { it.showOrJumpToTop() },
            onTabLongClick = { it.remove() },
        )
    }

    override fun newATab() {
        // fix: https://github.com/plateaukao/einkbro/issues/343
        if (searchOnSite) {
            hideSearchPanel()
        }

        when (config.newTabBehavior) {
            NewTabBehavior.START_INPUT -> {
                addAlbum(getString(R.string.app_name), "")
                focusOnInput()
            }

            NewTabBehavior.SHOW_HOME -> addAlbum("", config.favoriteUrl)
            NewTabBehavior.SHOW_RECENT_BOOKMARKS -> {
                addAlbum("", "")
                BrowserUnit.loadRecentlyUsedBookmarks(ebWebView)
            }
        }
    }

    override fun duplicateTab() {
        val webView = currentAlbumController as EBWebView
        val title = webView.title.orEmpty()
        val url = webView.url ?: return
        addAlbum(title, url)
    }

    override fun refreshAction() {
        if (ebWebView.isLoadFinish && ebWebView.url?.isNotEmpty() == true) {
            ebWebView.reload()
        } else {
            ebWebView.stopLoading()
        }
    }

    private lateinit var overviewDialogController: OverviewDialogController

    private val browserContainer: BrowserContainer = BrowserContainer()

    private lateinit var touchController: TouchAreaViewController

    private lateinit var twoPaneController: TwoPaneController

    private val dialogManager: DialogManager by lazy { DialogManager(this) }

    private val recordDb: RecordDb by inject()

    private lateinit var customFontResultLauncher: ActivityResultLauncher<Intent>
    private lateinit var saveImageFilePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var writeEpubFilePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var createWebArchivePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var openBookmarkFileLauncher: ActivityResultLauncher<Intent>
    private lateinit var createBookmarkFileLauncher: ActivityResultLauncher<Intent>
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var openEpubFilePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var runtimeExportPickerLauncher: ActivityResultLauncher<Intent>

    // Classes
    private inner class VideoCompletionListener : OnCompletionListener,
        MediaPlayer.OnErrorListener {
        override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean = false

        override fun onCompletion(mp: MediaPlayer) {
            onHideCustomView()
        }
    }

    private val adFilter: AdFilter = AdFilter.get()
    private val filterViewModel: FilterViewModel = adFilter.viewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        // workaround for crash issue
        // Caused by java.lang.NoSuchMethodException:
        super.onCreate(null)

        //android.os.Debug.waitForDebugger()

        binding = MainActivityLayout.create(this)

        savedInstanceState?.let {
            shouldLoadTabState = it.getBoolean(K_SHOULD_LOAD_TAB_STATE)
        }

        config.restartChanged = false
        HelperUnit.applyTheme(this)
        setContentView(binding.root)

        wave02Logger = Wave02SessionLogger(this)
        wave02Logger.logEvent(
            eventType = "activity_created",
            payload = mapOf(
                "activity" to "BrowserActivity",
                "saved_instance_state" to (savedInstanceState != null)
            )
        )

        orientation = resources.configuration.orientation

        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        mainContentLayout = findViewById(R.id.main_content)
        translationPanelView = TranslationPanelView(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        binding.twoPanelLayout.addView(translationPanelView)

        swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            if (!this::ebWebView.isInitialized) {
                true
            } else {
                !ebWebView.wasAtTopOnTouchStart || ebWebView.scrollY > 0 || !ebWebView.isInnerScrollAtTop
            }
        }
        swipeRefreshLayout.setOnRefreshListener {
            if (currentAlbumController != null && this::ebWebView.isInitialized) {
                ebWebView.reload()
            } else {
                swipeRefreshLayout.isRefreshing = false
            }
        }
        ViewUnit.updateAppbarPosition(binding)
        initLaunchers()
        initToolbar()
        initSearchPanel()
        initInputBar()
        initOverview()
        initTouchArea()
        initActionModeViewModel()
        initInstapaperViewModel()

        downloadReceiver = createDownloadReceiver(this)
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        dispatchIntent(intent)
        // after dispatching intent, the value should be reset to false
        shouldLoadTabState = false

        if (config.keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        initLanguageLabel()
        initTouchAreaViewController()
        initTextSearchButton()
        initExternalSearchCloseButton()
        initTranslationViewModel()
        initTtsViewModel()

        if (config.hideStatusbar) {
            hideStatusBar()
        }

        handleWindowInsets()
        listenKeyboardShowHide()

        // post delay to update filter list
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission()
        } else {
            binding.root.postDelayed({
                checkAdBlockerList()
            }, 1000)
        }
    }

    private fun checkAdBlockerList() {
        if (!adFilter.hasInstallation) {
            val map = mapOf(
                "AdGuard Base" to "https://filters.adtidy.org/extension/chromium/filters/2.txt",
//                "EasyPrivacy Lite" to "https://filters.adtidy.org/extension/chromium/filters/118_optimized.txt",
//                "AdGuard Tracking Protection" to "https://filters.adtidy.org/extension/chromium/filters/3.txt",
//                "AdGuard Annoyances" to "https://filters.adtidy.org/extension/chromium/filters/14.txt",
//                "AdGuard Chinese" to "https://filters.adtidy.org/extension/chromium/filters/224.txt",
//                "NoCoin Filter List" to "https://filters.adtidy.org/extension/chromium/filters/242.txt"
            )
            for ((key, value) in map) {
                filterViewModel.addFilter(key, value)
            }
            val filters = filterViewModel.filters.value
            for ((key, _) in filters) {
                filterViewModel.download(key)
            }
        }
    }

    private fun requestNotificationPermission() {
        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
                if (isGranted) {
                    checkAdBlockerList()
                } else {
                }
            }
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun initTtsViewModel() {
        lifecycleScope.launch {
            ttsViewModel.readingState.collect { _ ->
                composeToolbarViewController.updateIcons()
            }
        }
    }

    private fun initTranslationViewModel() {
        lifecycleScope.launch {
            translationViewModel.showEditDialogWithIndex.collect { index ->
                if (index == -1) return@collect
                ShowEditGptActionDialogFragment(index)
                    .showNow(supportFragmentManager, "editGptAction")
                translationViewModel.resetEditDialogIndex()
            }
        }

    }

    private fun handleWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { view, windowInsets ->
            val insetsNavigationBar: Insets =
                windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val insetsKeyboard: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val params = view.layoutParams as FrameLayout.LayoutParams

            if (config.hideStatusbar) {
                // When keyboard is visible, adjust for keyboard height
                // Otherwise, adjust for navigation bar
                if (insetsKeyboard.bottom > 0) {
                    params.bottomMargin = insetsKeyboard.bottom
                } else if (insetsNavigationBar.bottom > 0) {
                    params.bottomMargin = insetsNavigationBar.bottom
                } else {
                    params.bottomMargin = 0
                }
                view.layoutParams = params
            }
            // Return windowInsets instead of CONSUMED to allow proper inset propagation
            // This is critical for WebView to properly resize when keyboard appears
            windowInsets
        }
    }


    @SuppressLint("UseCompatLoadingForDrawables")
    private fun initExternalSearchCloseButton() {
        binding.activityMainContent.externalSearchClose.setOnClickListener {
            moveTaskToBack(true)
            externalSearchViewModel.setButtonVisibility(false)
        }
        val externalSearchContainer = binding.activityMainContent.externalSearchActionContainer
        externalSearchViewModel.searchActions.forEach { action ->
            val button = TextView(this).apply {
                height = 40.dp.value.toInt()
                textSize = 10.sp.value
                gravity = Gravity.CENTER
                background = getDrawable(R.drawable.background_with_border)
                text = action.title.take(2).uppercase(Locale.getDefault())
                setOnClickListener {
                    externalSearchViewModel.currentSearchAction = action
                    ebWebView.loadUrl(
                        externalSearchViewModel.generateSearchUrl(
                            splitSearchItemInfo = action
                        )
                    )
                }
            }
            externalSearchContainer.addView(button, 0)
        }
        lifecycleScope.launch {
            externalSearchViewModel.showButton.collect { show ->
                externalSearchContainer.visibility = if (show) VISIBLE else INVISIBLE
            }
        }
    }

    private fun initTextSearchButton() {
        val remoteTextSearch = findViewById<ImageButton>(R.id.remote_text_search)
        remoteTextSearch.setOnClickListener {
            remoteConnViewModel.reset()
        }
        lifecycleScope.launch {
            remoteConnViewModel.remoteConnected.collect { connected ->
                remoteTextSearch.setImageResource(
                    if (remoteConnViewModel.isSendingTextSearch) R.drawable.ic_send
                    else R.drawable.ic_receive
                )
                remoteTextSearch.isVisible = connected
            }
        }
    }

    private fun initTouchAreaViewController() {
        touchController = TouchAreaViewController(binding.activityMainContent, this)
    }

    private fun initActionModeViewModel() {
        lifecycleScope.launch {
            actionModeMenuViewModel.actionModeMenuState.collect { state ->
                when (state) {
                    is HighlightText -> {
                        lifecycleScope.launch {
                            highlightText(state.highlightStyle)
                            actionModeMenuViewModel.finish()
                        }
                    }

                    GoogleTranslate, DeeplTranslate, Papago, Naver -> {
                        val api =
                            if (GoogleTranslate == state) TRANSLATE_API.GOOGLE
                            else if (Papago == state) TRANSLATE_API.PAPAGO
                            else if (DeeplTranslate == state) TRANSLATE_API.DEEPL
                            else TRANSLATE_API.NAVER
                        translationViewModel.updateTranslateMethod(api)

                        lifecycleScope.launch {
                            updateTranslationInput()
                            showTranslationDialog()

                            actionModeMenuViewModel.finish()
                        }
                    }

                    is ActionModeMenuState.ReadFromHere -> readFromThisSentence()

                    is Gpt -> {
                        val gptAction = config.gptActionList[state.gptActionIndex]
                        lifecycleScope.launch {
                            updateTranslationInput()
                            if (translationViewModel.hasOpenAiApiKey()) {
                                translationViewModel.setupGptAction(gptAction)
                                translationViewModel.url = getFocusedWebView().url.orEmpty()

                                when (gptAction.display) {
                                    GptActionDisplay.Popup -> showTranslationDialog()
                                    GptActionDisplay.NewTab -> {
                                        chatWithWeb(false, actionModeMenuViewModel.selectedText.value, gptAction)
                                    }

                                    GptActionDisplay.SplitScreen -> {
                                        chatWithWeb(true, actionModeMenuViewModel.selectedText.value, gptAction)
                                    }
                                }
                            } else {
                                EBToast.show(this@BrowserActivity, R.string.gpt_api_key_not_set)
                            }
                            actionModeMenuViewModel.finish()
                        }
                    }

                    is SplitSearch -> {
                        splitSearchViewModel.state = state
                        val selectedText = actionModeMenuViewModel.selectedText.value
                        toggleSplitScreen(splitSearchViewModel.getUrl(selectedText))

                        actionModeMenuViewModel.finish()
                    }

                    is Tts -> {
                        IntentUnit.tts(this@BrowserActivity, state.text)
                        actionModeMenuViewModel.finish()
                    }

                    is SelectSentence -> getFocusedWebView().selectSentence(longPressPoint)
                    is SelectParagraph -> getFocusedWebView().selectParagraph(longPressPoint)

                    Idle -> Unit
                }
            }
        }

        lifecycleScope.launch {
            actionModeMenuViewModel.clickedPoint.collect { point ->
                val view = actionModeView ?: return@collect
                ViewUnit.updateViewPosition(view, point)
            }
        }

        lifecycleScope.launch {
            actionModeMenuViewModel.shouldShow.collect { shouldShow ->
                val view = actionModeView ?: return@collect
                if (shouldShow) {
                    val point = actionModeMenuViewModel.clickedPoint.value
                    // when it's first time to show action mode view
                    // need to wait until width and height is available
                    if (view.width == 0 || view.height == 0) {
                        view.post {
                            ViewUnit.updateViewPosition(view, point)
                            view.visibility = VISIBLE
                        }
                    } else {
                        ViewUnit.updateViewPosition(view, point)
                        view.visibility = VISIBLE
                    }
                } else {
                    view.visibility = INVISIBLE
                }
            }
        }
    }

    private fun initInstapaperViewModel() {
        lifecycleScope.launch {
            instapaperViewModel.uiState.collect { state ->
                if (state.showConfigureDialog) {
                    configureInstapaper()
                } else if (state.successMessage != null) {
                    EBToast.show(this@BrowserActivity, state.successMessage)
                } else if (state.errorMessage != null) {
                    EBToast.show(this@BrowserActivity, state.errorMessage)
                }
                instapaperViewModel.resetState()
            }
        }
    }

    private fun readFromThisSentence() {
        lifecycleScope.launch {
            val selectedSentence = ebWebView.getSelectedText()
            val fullText = ebWebView.getRawText()
            // read from selected sentence to the end of the article
            val startIndex = fullText.indexOf(selectedSentence)
            ttsViewModel.readArticle(fullText.substring(startIndex))
        }
    }

    private suspend fun updateTranslationInput() {
        // need to handle where data is from: ebWebView or twoPaneController.getSecondWebView()
        with(translationViewModel) {
            updateInputMessage(actionModeMenuViewModel.selectedText.value)
            updateMessageWithContext(getFocusedWebView().getSelectedTextWithContext())
            url = getFocusedWebView().url.orEmpty()
        }
    }

    private suspend fun highlightText(highlightStyle: HighlightStyle) {
        val focusedWebView = getFocusedWebView()
        // work on UI first
        focusedWebView.highlightTextSelection(highlightStyle)

        // work on db saving
        val url = focusedWebView.url.orEmpty()
        val title = focusedWebView.title.orEmpty()
        val article = Article(title, url, System.currentTimeMillis(), "")

        val articleInDb =
            bookmarkManager.getArticleByUrl(url) ?: bookmarkManager.insertArticle(article)

        val selectedText = actionModeMenuViewModel.selectedText.value
        val highlight = Highlight(articleInDb.id, selectedText)
        bookmarkManager.insertHighlight(highlight)
    }

    private fun isInSplitSearchMode(): Boolean =
        splitSearchViewModel.state != null && twoPaneController.isSecondPaneDisplayed()

    private fun initLanguageLabel() {
        languageLabelView = findViewById(R.id.translation_language)
        lifecycleScope.launch {
            translationViewModel.translationLanguage.collect {
                ViewUnit.updateLanguageLabel(languageLabelView!!, it)
            }
        }

        languageLabelView?.setOnClickListener {
            lifecycleScope.launch {
                val translationLanguage =
                    TranslationLanguageDialog(this@BrowserActivity).show() ?: return@launch
                translationViewModel.updateTranslationLanguage(translationLanguage)
                ebWebView.clearTranslationElements()
                translateByParagraph(ebWebView.translateApi)
            }
        }
        languageLabelView?.setOnLongClickListener {
            languageLabelView?.visibility = GONE
            true
        }
    }

    override fun isAtTop(): Boolean = ebWebView.isAtTop()
    override fun jumpToTop() = ebWebView.jumpToTop()
    override fun jumpToBottom() = ebWebView.jumpToBottom()
    override fun pageDown() = ebWebView.pageDownWithNoAnimation()
    override fun pageUp() = ebWebView.pageUpWithNoAnimation()
    override fun toggleReaderMode() = ebWebView.toggleReaderMode()
    override fun toggleVerticalRead() = ebWebView.toggleVerticalRead()
    override fun updatePageInfo(info: String) = composeToolbarViewController.updatePageInfo(info)

    override fun sendPageUpKey() = ebWebView.sendPageUpKey()
    override fun sendPageDownKey() = ebWebView.sendPageDownKey()
    override fun sendLeftKey() {
        ebWebView.dispatchKeyEvent(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    override fun sendRightKey() {
        ebWebView.dispatchKeyEvent(KeyEvent(ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    override fun translate(translationMode: TranslationMode) {
        when (translationMode) {
            TranslationMode.TRANSLATE_BY_PARAGRAPH -> translateByParagraph(TRANSLATE_API.GOOGLE)
            TranslationMode.PAPAGO_TRANSLATE_BY_PARAGRAPH -> translateByParagraph(TRANSLATE_API.PAPAGO)
            TranslationMode.DEEPL_BY_PARAGRAPH -> translateByParagraph(TRANSLATE_API.DEEPL)

            TranslationMode.PAPAGO_TRANSLATE_BY_SCREEN -> translateWebView()
            TranslationMode.GOOGLE_IN_PLACE -> ebWebView.addGoogleTranslation()
            else -> Unit
        }
    }

    override fun resetTranslateUI() {
        languageLabelView?.visibility = GONE
    }

    override fun configureTranslationLanguage(translateApi: TRANSLATE_API) {
        LanguageSettingDialogFragment(translateApi, translationViewModel) {
            if (translateApi == TRANSLATE_API.GOOGLE) {
                translateByParagraph(TRANSLATE_API.GOOGLE)
            } else if (translateApi == TRANSLATE_API.PAPAGO) {
                translateByParagraph(TRANSLATE_API.PAPAGO)
            } else if (translateApi == TRANSLATE_API.DEEPL) {
                translateByParagraph(TRANSLATE_API.DEEPL)
            }
        }
            .show(supportFragmentManager, "LanguageSettingDialog")
    }

    override fun toggleTouchPagination() = toggleTouchTurnPageFeature()

    override fun showFontBoldnessDialog() {
        FontBoldnessDialogFragment(
            config.fontBoldness,
            okAction = { changedBoldness ->
                config.fontBoldness = changedBoldness
                ebWebView.applyFontBoldness()
            }
        ).show(supportFragmentManager, "FontBoldnessDialog")
    }

    override fun toggleTextSearch() {
        remoteConnViewModel.toggleTextSearch()
    }

    override fun sendToRemote(text: String) {
        if (remoteConnViewModel.isSendingTextSearch) {
            remoteConnViewModel.toggleTextSearch()
            EBToast.show(this, R.string.send_to_remote_terminate)
            return
        }

        SendLinkDialog(this, lifecycleScope).show(text)
    }

    private val linkContentWebView: EBWebView by lazy {
        EBWebView(this, this).apply {
            setOnPageFinishedAction {
                lifecycleScope.launch {
                    val content = linkContentWebView.getRawText()
                    loadUrl("about:blank")
                    if (content.isNotEmpty()) {
                        val isSuccess = translationViewModel.setupTextSummary(content)
                        if (!isSuccess) {
                            EBToast.show(this@BrowserActivity, R.string.gpt_api_key_not_set)
                            return@launch
                        }

                        showTranslationDialog()
                    }
                }
            }
        }
    }

    private fun summarizeLinkContent(url: String) {
        if (translationViewModel.hasOpenAiApiKey()) {
            translationViewModel.url = url
            linkContentWebView.loadUrl(url)
        }
    }

    override fun summarizeContent() {
        if (translationViewModel.hasOpenAiApiKey()) {
            lifecycleScope.launch {
                translationViewModel.url = ebWebView.url.orEmpty()
                val isSuccess = translationViewModel.setupTextSummary(ebWebView.getRawText())

                if (!isSuccess) {
                    EBToast.show(this@BrowserActivity, R.string.gpt_api_key_not_set)
                    return@launch
                }

                showTranslationDialog()
            }
        }
    }

    override fun chatWithWeb(useSplitScreen: Boolean, content: String?, runWithAction: ChatGPTActionInfo?) {
        lifecycleScope.launch {
            val rawText = content ?: ebWebView.getRawText()
            withContext(Dispatchers.Main) {
                val scope = this@BrowserActivity.lifecycleScope
                if (useSplitScreen) {
                    maybeInitTwoPaneController()
                    // get current web title/url
                    val webTitle = ebWebView.title ?: "No Title"
                    val webUrl = ebWebView.url.orEmpty()
                    // add new tab
                    twoPaneController.showSecondPaneAsAi(rawText, webTitle, webUrl)
                    runWithAction?.let { twoPaneController.runGptAction(it) }
                } else {
                    // get current web title/url
                    val webTitle = ebWebView.title ?: "No Title"
                    val webUrl = ebWebView.url.orEmpty()
                    // add new tab
                    addAlbum("Chat With Web")
                    runWithAction?.let { action ->
                        ebWebView.setOnPageFinishedAction {
                            ebWebView.setOnPageFinishedAction {}
                            ebWebView.runGptAction(action)
                        }
                    }
                    ebWebView.setupAiPage(scope, rawText, webTitle, webUrl)
                }
            }
        }
    }

    override fun showPageAiActionMenu() {
        val pageActions =
            config.gptActionList.filter { it.scope == GptActionScope.WholePage }
        if (pageActions.isEmpty()) {
            EBToast.show(this, R.string.page_ai_action_empty)
            return
        }

        PageAiActionDialogFragment(
            actions = pageActions,
            onActionClicked = { runPageAiAction(it) },
            onActionLongClicked = { action ->
                val index = config.gptActionList.indexOf(action)
                if (index >= 0) {
                    ShowEditGptActionDialogFragment(index)
                        .showNow(supportFragmentManager, "editGptAction")
                }
            }
        ).showNow(supportFragmentManager, "pageAiAction")
    }

    private fun runPageAiAction(action: ChatGPTActionInfo) {
        lifecycleScope.launch {
            if (!translationViewModel.hasOpenAiApiKey()) {
                EBToast.show(this@BrowserActivity, R.string.gpt_api_key_not_set)
                return@launch
            }

            val content = ebWebView.getRawText()
            translationViewModel.setupGptAction(action)
            translationViewModel.url = ebWebView.url.orEmpty()
            translationViewModel.pageTitle = ebWebView.title.orEmpty()

            when (action.display) {
                GptActionDisplay.Popup -> {
                    translationViewModel.updateInputMessage(content)
                    translationViewModel.updateMessageWithContext(content)
                    showTranslationDialog(isWholePageMode = true)
                }

                GptActionDisplay.NewTab -> chatWithWeb(false, content, action)

                GptActionDisplay.SplitScreen -> chatWithWeb(true, content, action)
            }
        }
    }

    override fun addToInstapaper() {
        val url = ebWebView.url.orEmpty()
        if (url.isEmpty()) {
            EBToast.show(this, R.string.url_empty)
            return
        }

        instapaperViewModel.addUrl(
            url = url,
            username = config.instapaperUsername,
            password = config.instapaperPassword,
            title = ebWebView.title
        )
    }

    override fun configureInstapaper() {
        dialogManager.showInstapaperCredentialsDialog { name, password -> Unit }
    }

    private fun showTranslationDialog(isWholePageMode: Boolean = false) {
        supportFragmentManager.findFragmentByTag("translateDialog")?.let {
            supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
        }
        TranslateDialogFragment(
            translationViewModel,
            externalSearchWebView,
            actionModeMenuViewModel.clickedPoint.value,
            isWholePageMode = isWholePageMode,
        )
            .show(supportFragmentManager, "translateDialog")
    }

    override fun invertColors() {
        val hasInvertedColor = config.toggleInvertedColor(ebWebView.url.orEmpty())
        ViewUnit.invertColor(ebWebView, hasInvertedColor)
    }

    override fun shareLink() {
        IntentUnit.share(this, ebWebView.title, ebWebView.url)
    }

    override fun updateSelectionRect(left: Float, top: Float, right: Float, bottom: Float) {
        //Log.d("touch", "updateSelectionRect: $left, $top, $right, $bottom")
        // 10 for the selection indicator height
        val newPoint = Point(
            ViewUnit.dpToPixel(right.toInt()).toInt(),
            ViewUnit.dpToPixel(bottom.toInt() + 16).toInt()
        )
        if (abs(newPoint.x - actionModeMenuViewModel.clickedPoint.value.x) > ViewUnit.dpToPixel(15) ||
            abs(newPoint.y - actionModeMenuViewModel.clickedPoint.value.y) > ViewUnit.dpToPixel(15)
        ) {
            actionModeView?.visibility = INVISIBLE
        }
        actionModeMenuViewModel.updateClickedPoint(newPoint)

        // update the long press point so that it can be used for selecting sentence
        longPressPoint = Point(
            ViewUnit.dpToPixel(left.toInt() - 1).toInt(),
            ViewUnit.dpToPixel(top.toInt() + 1).toInt()
        )
    }

    override fun toggleReceiveTextSearch() {
        if (remoteConnViewModel.isReceivingLink) {
            remoteConnViewModel.toggleReceiveLink {}
        } else {
            remoteConnViewModel.toggleReceiveLink {
                if (it.startsWith("action")) {
                    val (_, content, actionString) = it.split("|||")
                    val action = Json.decodeFromString<ChatGPTActionInfo>(actionString)
                    if (action.display == GptActionDisplay.Popup) {
                        translationViewModel.setupGptAction(action)
                        translationViewModel.updateMessageWithContext(content)
                        translationViewModel.updateInputMessage(content)
                        showTranslationDialog(isWholePageMode = action.scope == GptActionScope.WholePage)
                    } else {
                        chatWithWeb(false, content, action)
                    }
                } else {
                    ebWebView.loadUrl(it)
                }
            }
        }
    }

    override fun toggleReceiveLink() {
        if (remoteConnViewModel.isReceivingLink) {
            remoteConnViewModel.toggleReceiveLink {}
            EBToast.show(this, R.string.receive_link_terminate)
            return
        }

        ReceiveDataDialog(this@BrowserActivity, lifecycleScope).show {
            ShareUtil.startReceiving(lifecycleScope) { url ->
                if (url.isNotBlank()) {
                    trackedLoadUrl(url, "intent_send_or_process")
                    ShareUtil.stopBroadcast()
                }
            }
        }
    }

    private fun initLaunchers() {
        saveImageFilePickerLauncher = IntentUnit.createSaveImageFilePickerLauncher(this)
        customFontResultLauncher =
            IntentUnit.createResultLauncher(this) { handleFontSelectionResult(it) }
        openBookmarkFileLauncher = backupUnit.createOpenBookmarkFileLauncher(this)
        createBookmarkFileLauncher = backupUnit.createCreateBookmarkFileLauncher(this)
        createWebArchivePickerLauncher =
            IntentUnit.createResultLauncher(this) { saveWebArchive(it) }
        writeEpubFilePickerLauncher =
            IntentUnit.createResultLauncher(this) {
                val uri = backupUnit.preprocessActivityResult(it) ?: return@createResultLauncher
                saveEpub(uri)
            }
        fileChooserLauncher =
            IntentUnit.createResultLauncher(this) { handleWebViewFileChooser(it) }
        openEpubFilePickerLauncher =
            IntentUnit.createResultLauncher(this) { handleEpubUri(it) }
        runtimeExportPickerLauncher =
            IntentUnit.createResultLauncher(this) { handleRuntimeExportPickerResult(it) }
    }

    private fun handleEpubUri(result: ActivityResult) {
        if (result.resultCode != RESULT_OK) return
        val uri = result.data?.data ?: return
        HelperUnit.openFile(this, uri)
    }

    private fun handleFontSelectionResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK) return
        BrowserUnit.handleFontSelectionResult(this, result, ebWebView.shouldUseReaderFont())
    }

    private fun handleWebViewFileChooser(result: ActivityResult) {
        if (result.resultCode != RESULT_OK || filePathCallback == null) {
            filePathCallback = null
            return
        }
        var results: Array<Uri>?
        // Check that the response is a good one
        val data = result.data
        if (data != null) {
            // If there is not data, then we may have taken a photo
            val dataString = data.dataString
            results = arrayOf(Uri.parse(dataString))
            filePathCallback?.onReceiveValue(results)
        }
        filePathCallback = null
    }

    private fun promptRuntimeExportDestination() {
        val summary = RuntimeToolkitTelemetry.buildMissionExportSummary(this)
        RuntimeToolkitTelemetry.logMissionEvent(
            context = this,
            operation = "export_requested",
            exportReadiness = summary.exportReadiness,
            reason = summary.reason,
            payload = mapOf(
                "missing_required_steps" to summary.missingRequiredSteps,
                "missing_required_artifacts" to summary.missingRequiredArtifacts,
            ),
        )
        val filename = "mapper_runtime_export_${Instant.now().toString().replace(':', '-').replace('.', '-')}.zip"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        runtimeExportPickerLauncher.launch(intent)
    }

    private fun handleRuntimeExportPickerResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK) {
            return
        }
        val destinationUri = result.data?.data
        if (destinationUri == null) {
            EBToast.showShort(this, getString(R.string.mapper_capture_export_failed, "no destination selected"))
            return
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val exportFile = RuntimeToolkitTelemetry.exportRuntimeArtifacts(this@BrowserActivity)
                    contentResolver.openOutputStream(destinationUri, "w").use { output ->
                        requireNotNull(output) { "cannot open destination stream" }
                        exportFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }
            }.onSuccess {
                val updatedSummary = RuntimeToolkitTelemetry.buildMissionExportSummary(this@BrowserActivity)
                RuntimeToolkitTelemetry.logMissionEvent(
                    context = this@BrowserActivity,
                    operation = "export_finalized",
                    exportReadiness = updatedSummary.exportReadiness,
                    reason = "runtime_export_written",
                    payload = mapOf(
                        "destination_uri" to destinationUri.toString(),
                        "missing_required_artifacts" to updatedSummary.missingRequiredArtifacts,
                    ),
                )
                EBToast.showShort(
                    this@BrowserActivity,
                    getString(R.string.mapper_capture_exported, destinationUri.toString())
                )
            }.onFailure { throwable ->
                val updatedSummary = RuntimeToolkitTelemetry.buildMissionExportSummary(this@BrowserActivity)
                RuntimeToolkitTelemetry.logMissionEvent(
                    context = this@BrowserActivity,
                    operation = "export_finalized",
                    exportReadiness = "BLOCKED",
                    reason = "runtime_export_failed",
                    payload = mapOf(
                        "error" to (throwable.message ?: "unknown"),
                        "missing_required_artifacts" to updatedSummary.missingRequiredArtifacts,
                    ),
                )
                EBToast.showShort(
                    this@BrowserActivity,
                    getString(
                        R.string.mapper_capture_export_failed,
                        throwable.message ?: "unknown"
                    )
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveEpub(uri: Uri) {
        val progressDialog =
            dialogManager.createProgressDialog(R.string.saving_epub).apply { show() }
        epubManager.saveEpub(
            this,
            uri,
            ebWebView,
            {
                progressDialog.progress = it
                if (it == 100) {
                    progressDialog.dismiss()
                }
            },
            {
                progressDialog.dismiss()
                dialogManager.showOkCancelDialog(
                    messageResId = R.string.cannot_save_epub,
                    okAction = {},
                    showInCenter = true,
                    showNegativeButton = false,
                )
            }
        )
    }

    private fun saveWebArchive(result: ActivityResult) {
        val uri = backupUnit.preprocessActivityResult(result) ?: return
        saveWebArchiveToUri(uri)
    }

    private fun saveWebArchiveToUri(uri: Uri) {
        // get archive from webview
        val filePath = File(filesDir.absolutePath + "/temp.mht").absolutePath
        ebWebView.saveWebArchive(filePath, false) {
            val tempFile = File(filePath)
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        if (isMeetPipCriteria()) {
            enterPipMode()
        }
    }

    private fun isMeetPipCriteria() = config.enableVideoPip &&
            fullscreenHolder != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        val params = PictureInPictureParams.Builder().build();
        enterPictureInPictureMode(params)
    }

    private var searchJob: Job? = null
    private var shouldRestoreFullscreen = false

    // State for AutoCompleteTextField (previously in AutoCompleteTextComposeView)
    private val inputTextOrUrl = mutableStateOf(TextFieldValue(""))
    private val inputRecordList = mutableStateOf(listOf<Record>())
    private val inputUrlFocusRequester = FocusRequester()
    private var inputIsWideLayout by mutableStateOf(false)
    private var inputShouldReverse by mutableStateOf(true)
    private var inputHasCopiedText by mutableStateOf(false)

    private fun initInputBar() {
        binding.inputUrl.apply {
            visibility = INVISIBLE
            setContent {
                MyTheme {
                    AutoCompleteTextField(
                        text = inputTextOrUrl,
                        recordList = inputRecordList,
                        bookmarkManager = bookmarkManager,
                        focusRequester = inputUrlFocusRequester,
                        isWideLayout = inputIsWideLayout,
                        shouldReverse = inputShouldReverse,
                        hasCopiedText = inputHasCopiedText,
                        onTextSubmit = { text ->
                            updateAlbum(text.trim())
                            showToolbar()
                            if (shouldRestoreFullscreen) {
                                toggleFullscreen()
                                shouldRestoreFullscreen = false
                            }
                        },
                        onTextChange = { query ->
                            searchJob?.cancel()
                            searchJob = lifecycleScope.launch {
                                kotlinx.coroutines.delay(300)
                                withContext(Dispatchers.IO) {
                                    searchSuggestionViewModel.updateSuggestions(query)
                                }
                                inputRecordList.value = searchSuggestionViewModel.suggestions.value
                            }
                        },
                        onPasteClick = { updateAlbum(getClipboardText()); showToolbar() },
                        closeAction = {
                            showToolbar()
                            if (shouldRestoreFullscreen) {
                                toggleFullscreen()
                                shouldRestoreFullscreen = false
                            }
                        },
                        onRecordClick = {
                            updateAlbum(it.url)
                            showToolbar()
                            if (shouldRestoreFullscreen) {
                                toggleFullscreen()
                                shouldRestoreFullscreen = false
                            }
                        },
                    )
                }
            }
        }
    }

    private fun isKeyboardDisplaying(): Boolean {
        val rect = Rect()
        binding.root.getWindowVisibleDisplayFrame(rect)
        val heightDiff: Int = binding.root.rootView.height - rect.bottom
        return heightDiff > binding.root.rootView.height * 0.15
    }

    private fun listenKeyboardShowHide() {
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            if (isKeyboardDisplaying()) { // Value should be less than keyboard's height
                touchController.maybeDisableTemporarily()
            } else {
                touchController.maybeEnableAgain()
            }

            // Manual keyboard handling for pre-R devices when status bar is hidden
            @Suppress("DEPRECATION")
            val isFullscreen = (window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && isFullscreen) {
                val rect = Rect()
                binding.root.getWindowVisibleDisplayFrame(rect)
                val screenHeight = binding.root.rootView.height
                val keypadHeight = screenHeight - rect.bottom
                
                val params = binding.root.layoutParams as FrameLayout.LayoutParams
                if (keypadHeight > screenHeight * 0.15) { // Keyboard is open
                    if (params.bottomMargin != keypadHeight) {
                        params.bottomMargin = keypadHeight
                        binding.root.layoutParams = params
                    }
                } else { // Keyboard is closed
                    if (params.bottomMargin != 0) {
                        params.bottomMargin = 0
                        binding.root.layoutParams = params
                    }
                }
            }
        }
    }

    private var orientation: Int = 0
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightModeFlags != uiMode && config.darkMode == DarkMode.SYSTEM) {
                recreate()
            }
        }
        if (newConfig.orientation != orientation) {
            composeToolbarViewController.updateIcons()
            orientation = newConfig.orientation

            if (config.fabPosition == FabPosition.Custom) {
                fabImageViewController.updateImagePosition(orientation)
            }
        }
    }

    private fun initTouchArea() = updateTouchView()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dispatchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (config.restartChanged) {
            config.restartChanged = false
            dialogManager.showRestartConfirmDialog()
        }

        updateTitle()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        if (config.customFontChanged &&
            (config.fontType == FontType.CUSTOM || config.readerFontType == FontType.CUSTOM)
        ) {
            if (!ebWebView.shouldUseReaderFont()) {
                ebWebView.reload()
            } else {
                ebWebView.updateCssStyle()
            }
            config.customFontChanged = false
        }
        if (!config.continueMedia) {
            if (this::ebWebView.isInitialized) {
                ebWebView.resumeTimers()
            }
        }
        if (this::captureToggleButton.isInitialized) {
            updateCaptureToggleUi(RuntimeToolkitTelemetry.isCaptureEnabled(this))
        }
        if (this::missionWizardButton.isInitialized) {
            updateMissionWizardUi()
        }
    }

    override fun onDestroy() {
        ttsViewModel.reset()

        updateSavedAlbumInfo()

        if (config.clearWhenQuit && shouldRunClearService) {
            startService(Intent(this, ClearService::class.java))
        }

        browserContainer.clear()
        clearPendingWizardAutoAdvance()
        clearWizardUndoWindow()
        unregisterReceiver(downloadReceiver)
        wave02Logger.finish("activity_destroyed")

        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return keyHandler.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        return keyHandler.onKeyLongPress(keyCode, event) || super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return keyHandler.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }

    override fun handleBackKey() {
        ViewUnit.hideKeyboard(this)
        if (overviewDialogController.isVisible()) {
            hideOverview()
        }
        if (fullscreenHolder != null || customView != null || videoView != null) {
            onHideCustomView()
        } else if (!binding.appBar.isVisible && config.showToolbarFirst) {
            showToolbar()
        } else if (!composeToolbarViewController.isDisplayed()) {
            composeToolbarViewController.show()
        } else {
            // disable back key when it's translate mode web page
            if (!ebWebView.isTranslatePage && ebWebView.canGoBack()) {
                ebWebView.goBack()
            } else {
                if (config.closeTabWhenNoMoreBackHistory) {
                    removeAlbum()
                } else {
                    EBToast.show(this, R.string.no_previous_page)
                }
            }
        }
    }

    override fun isCurrentAlbum(albumController: AlbumController): Boolean =
        currentAlbumController == albumController

    override fun showAlbum(controller: AlbumController) {
        if (currentAlbumController != null) {
            if (currentAlbumController == controller) {
                // if it's the same controller, just scroll to top
                if (ebWebView.isAtTop()) {
                    ebWebView.reload()
                } else {
                    jumpToTop()
                }
                return
            }
            currentAlbumController?.deactivate()
        }

        // remove current view from the container first
        val controllerView = controller as View
        if (mainContentLayout.childCount > 0) {
            for (i in 0 until mainContentLayout.childCount) {
                if (mainContentLayout.getChildAt(i) == controllerView) {
                    mainContentLayout.removeView(controllerView)
                    break
                }
            }
        }

        mainContentLayout.addView(
            controller as View,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        currentAlbumController = controller
        currentAlbumController?.activate()

        updateSavedAlbumInfo()
        updateWebViewCount()

        progressBar.visibility = GONE
        ebWebView = controller as EBWebView
        keyHandler.setWebView(ebWebView)

        updateTitle()
        ebWebView.updatePageInfo()

        // when showing a new album, should turn off externalSearch button visibility
        externalSearchViewModel.setButtonVisibility(false)
        runOnUiThread {
            composeToolbarViewController.updateFocusIndex(
                albumViewModel.albums.value.indexOfFirst { it.isActivated }
            )
        }
        updateLanguageLabel()
    }

    private fun updateLanguageLabel() {
        languageLabelView?.visibility =
            if (ebWebView.isTranslatePage || ebWebView.isTranslateByParagraph) VISIBLE else GONE
    }

    private fun openCustomFontPicker() = BrowserUnit.openFontFilePicker(customFontResultLauncher)

    override fun showOverview() = overviewDialogController.show()

    override fun hideOverview() = overviewDialogController.hide()

    override fun rotateScreen() = IntentUnit.rotateScreen(this)

    override fun saveBookmark(url: String?, title: String?) {
        val currentUrl = url ?: ebWebView.url ?: return
        var nonNullTitle = title ?: HelperUnit.secString(ebWebView.title)
        try {
            lifecycleScope.launch {
                BookmarkEditDialog(
                    this@BrowserActivity,
                    bookmarkViewModel,
                    Bookmark(
                        nonNullTitle.pruneWebTitle(),
                        currentUrl, order = if (ViewUnit.isWideLayout(this@BrowserActivity)) 999 else 0
                    ),
                    {
                        handleBookmarkSync(true)
                        ViewUnit.hideKeyboard(this@BrowserActivity)
                        EBToast.show(this@BrowserActivity, R.string.toast_edit_successful)
                    },
                    { ViewUnit.hideKeyboard(this@BrowserActivity) }
                ).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            EBToast.show(this, R.string.toast_error)
        }
    }

    override fun createShortcut() = BrowserUnit.createShortcut(this, ebWebView)

    override fun toggleTouchTurnPageFeature() = config::enableTouchTurn.toggle()

    override fun toggleSwitchTouchAreaAction() = config::switchTouchAreaAction.toggle()

    private fun updateTouchView() = composeToolbarViewController.updateIcons()

    // Methods
    override fun showFontSizeChangeDialog() {
        if (ebWebView.shouldUseReaderFont()) {
            ReaderFontDialogFragment { openCustomFontPicker() }.show(
                supportFragmentManager,
                "font_dialog"
            )
        } else {
            FontDialogFragment { openCustomFontPicker() }.show(
                supportFragmentManager,
                "font_dialog"
            )
        }
    }

    private fun changeFontSize(size: Int) {
        if (ebWebView.shouldUseReaderFont()) {
            config.readerFontSize = size
        } else {
            config.fontSize = size
        }
    }

    override fun increaseFontSize() {
        val fontSize =
            if (ebWebView.shouldUseReaderFont()) config.readerFontSize else config.fontSize
        changeFontSize(fontSize + 20)
    }

    override fun decreaseFontSize() {
        val fontSize =
            if (ebWebView.shouldUseReaderFont()) config.readerFontSize else config.fontSize
        if (fontSize > 50) changeFontSize(fontSize - 20)
    }

    private fun maybeInitTwoPaneController() {
        if (!isTwoPaneControllerInitialized()) {
            twoPaneController = TwoPaneController(
                this,
                lifecycleScope,
                translationPanelView,
                binding.twoPanelLayout,
                { showTranslation() },
                { if (ebWebView.isReaderModeOn) ebWebView.toggleReaderMode() },
                { url -> trackedLoadUrl(url, "intent_send_or_process") },
                { api, webView -> translateByParagraph(api, webView) },
                this::translateWebView
            )
        }
    }

    private fun translateByParagraph(
        translateApi: TRANSLATE_API,
        webView: EBWebView = ebWebView,
    ) {
        translateByParagraphInPlace(translateApi, webView)
    }

    private fun translateByParagraphInPlace(
        translateApi: TRANSLATE_API,
        webView: EBWebView = ebWebView,
    ) {
        lifecycleScope.launch {
            webView.translateApi = translateApi
            webView.translateByParagraphInPlace()
            if (webView == ebWebView) {
                languageLabelView?.visibility = VISIBLE
            }
        }
    }

    private fun isTwoPaneControllerInitialized(): Boolean = ::twoPaneController.isInitialized

    override fun showTranslation(webView: EBWebView?) {
        maybeInitTwoPaneController()

        lifecycleScope.launch(Dispatchers.Main) {
            twoPaneController.showTranslation(webView ?: ebWebView)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(K_SHOULD_LOAD_TAB_STATE, true)
        super.onSaveInstanceState(outState)
    }

    @SuppressLint("InlinedApi")
    open fun dispatchIntent(intent: Intent) {
        if (overviewDialogController.isVisible()) {
            overviewDialogController.hide()
        }

        wave02Logger.logEvent(
            eventType = "intent_dispatched",
            payload = mapOf(
                "action" to (intent.action ?: "null"),
                "data" to intent.dataString,
                "type" to intent.type
            )
        )

        when (intent.action) {
            "", Intent.ACTION_MAIN -> {
                if (isLauncherMainIntent(intent)) {
                    initSavedTabs()
                    val session = RuntimeToolkitTelemetry.missionSessionState(this)
                    if (currentAlbumController == null) {
                        if (session.missionId.isBlank()) {
                            showMissionLauncherDialog()
                        } else {
                            // Resume persisted wizard state without reopening blocking setup dialogs.
                            updateMissionWizardUi()
                        }
                    } else {
                        updateMissionWizardUi()
                    }
                } else {
                    initSavedTabs { addAlbum() }
                }
            }

            ACTION_VIEW -> {
                initSavedTabs()
                // if webview for that url already exists, show the original tab, otherwise, create new
                val viewUri = intent.data?.toNormalScheme() ?: return
                if (viewUri.scheme == "content") {
                    lifecycleScope.launch {
                        val (filename, mimeType) = withContext(Dispatchers.IO) {
                            val (fName, _) = HelperUnit.getFileInfoFromContentUri(this@BrowserActivity, viewUri)
                            val mType = contentResolver.getType(viewUri)
                            Pair(fName, mType)
                        }

                        if (filename?.endsWith(".srt") == true ||
                            mimeType == "application/x-subrip"
                        ) {
                            // srt
                            addAlbum()
                            val htmlContent = withContext(Dispatchers.IO) {
                                val stringList =
                                    HelperUnit.readContentAsStringList(contentResolver, viewUri)
                                HelperUnit.srtToHtml(stringList)
                            }
                            ebWebView.isPlainText = true
                            ebWebView.rawHtmlCache = htmlContent
                            ebWebView.loadData(htmlContent, "text/html", "utf-8")

                        } else if (mimeType == "application/octet-stream") {
                            val cachedPath = withContext(Dispatchers.IO) {
                                HelperUnit.getCachedPathFromURI(this@BrowserActivity, viewUri)
                            }
                            cachedPath.let {
                                addAlbum(url = "file://$it")
                            }
                        } else if (filename?.endsWith(".mht") == true) {
                            // mht
                            val cachedPath = withContext(Dispatchers.IO) {
                                HelperUnit.getCachedPathFromURI(this@BrowserActivity, viewUri)
                            }
                            addAlbum(url = "file://$cachedPath")
                        } else if (filename?.endsWith(".html") == true || mimeType == "text/html") {
                            // local html
                            updateAlbum(url = viewUri.toString())
                        } else {
                            // epub
                            epubManager.showEpubReader(viewUri)
                            finish()
                        }
                    }
                } else {
                    val url = viewUri.toString()
                    getUrlMatchedBrowser(url)?.let { showAlbum(it) } ?: trackedAddAlbum(url, "intent_view")
                    showMissionWizardBannerForUrl(url)
                }
            }

            Intent.ACTION_WEB_SEARCH -> {
                initSavedTabs()
                val searchedKeyword = intent.getStringExtra(SearchManager.QUERY).orEmpty()
                if (currentAlbumController != null && config.isExternalSearchInSameTab) {
                    trackedLoadUrl(searchedKeyword, "intent_web_search")
                } else {
                    trackedAddAlbum(searchedKeyword, "intent_web_search")
                }
            }

            "sc_history" -> {
                addAlbum(); openHistoryPage()
            }

            "sc_home" -> {
                addAlbum(config.favoriteUrl)
            }

            "sc_bookmark" -> {
                addAlbum(); openBookmarkPage()
            }

            "sc_disable_adblock" -> {
                config.adBlock = false
                BrowserUnit.restartApp(this)
            }

            Intent.ACTION_SEND -> {
                initSavedTabs()
                val sentKeyword = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val url =
                    if (BrowserUnit.isURL(sentKeyword)) sentKeyword else externalSearchViewModel.generateSearchUrl(
                        sentKeyword
                    )
                if (currentAlbumController != null && config.isExternalSearchInSameTab) {
                    trackedLoadUrl(url, "intent_send_or_process")
                } else {
                    trackedAddAlbum(url, "intent_send_or_process")
                }
            }

            Intent.ACTION_PROCESS_TEXT -> {
                initSavedTabs()
                val text = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT) ?: return

                if (remoteConnViewModel.isSendingTextSearch) {
                    remoteConnViewModel.sendTextSearch(
                        externalSearchViewModel.generateSearchUrl(
                            text
                        )
                    )
                    moveTaskToBack(true)
                    return
                }

                val url =
                    if (BrowserUnit.isURL(text)) text else externalSearchViewModel.generateSearchUrl(
                        text
                    )
                if (currentAlbumController != null && config.isExternalSearchInSameTab) {
                    trackedLoadUrl(url, "intent_send_or_process")
                } else {
                    trackedAddAlbum(url, "intent_send_or_process")
                }
                // set minimize button visible
                externalSearchViewModel.setButtonVisibility(true)
            }

            ACTION_DICT -> {
                val text = intent.getStringExtra("EXTRA_QUERY") ?: return

                if (remoteConnViewModel.isSendingTextSearch) {
                    remoteConnViewModel.sendTextSearch(
                        externalSearchViewModel.generateSearchUrl(
                            text
                        )
                    )
                    moveTaskToBack(true)
                    return
                }

                initSavedTabs()
                val url = externalSearchViewModel.generateSearchUrl(text)
                if (currentAlbumController != null && config.isExternalSearchInSameTab) {
                    trackedLoadUrl(url, "intent_send_or_process")
                } else {
                    trackedAddAlbum(url, "intent_send_or_process")
                }
                // set minimize button visible
                externalSearchViewModel.setButtonVisibility(true)
            }

            ACTION_READ_ALOUD -> readArticle()

            null -> {
                if (browserContainer.isEmpty()) {
                    initSavedTabs { addAlbum() }
                } else {
                    return
                }
            }

            else -> addAlbum()
        }
        getIntent().action = ""
    }

    private fun trackedLoadUrl(url: String, source: String) {
        wave02Logger.logEvent(
            eventType = "url_load_requested",
            payload = mapOf(
                "source" to source,
                "url" to url
            )
        )
        ebWebView.loadUrl(url)
    }

    private fun trackedAddAlbum(url: String, source: String) {
        wave02Logger.logEvent(
            eventType = "url_open_requested",
            payload = mapOf(
                "source" to source,
                "url" to url
            )
        )
        addAlbum(url = url)
    }

    private fun initSavedTabs(whenNoSavedTabs: (() -> Unit)? = null) {
        if (currentAlbumController == null) { // newly opened Activity
            if ((shouldLoadTabState || config.shouldSaveTabs) &&
                config.savedAlbumInfoList.isNotEmpty()
            ) {
                // fix current album index is larger than album size
                if (config.currentAlbumIndex >= config.savedAlbumInfoList.size) {
                    config.currentAlbumIndex = config.savedAlbumInfoList.size - 1
                }
                val albumList = config.savedAlbumInfoList.toList()
                var savedIndex = config.currentAlbumIndex
                // fix issue
                if (savedIndex == -1) savedIndex = 0
                albumList.forEachIndexed { index, albumInfo ->
                    addAlbum(
                        title = albumInfo.title,
                        url = albumInfo.url,
                        foreground = (index == savedIndex)
                    )
                }
            } else {
                whenNoSavedTabs?.invoke()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initToolbar() {
        progressBar = findViewById(R.id.main_progress_bar)
        if (config.darkMode == DarkMode.FORCE_ON) {
            val nightModeFlags: Int =
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightModeFlags == Configuration.UI_MODE_NIGHT_NO) {
                progressBar.progressTintMode = PorterDuff.Mode.LIGHTEN
            }
        }
        initFAB()
        initCaptureOverlayButton()
        initMissionWizardOverlay()
        if (config.enableNavButtonGesture) {
            val onNavButtonTouchListener = object : SwipeTouchListener(this@BrowserActivity) {
                override fun onSwipeTop() = gestureHandler.handle(config.navGestureUp)
                override fun onSwipeBottom() = gestureHandler.handle(config.navGestureDown)
                override fun onSwipeRight() = gestureHandler.handle(config.navGestureRight)
                override fun onSwipeLeft() = gestureHandler.handle(config.navGestureLeft)
            }
            fabImageViewController.defaultTouchListener = onNavButtonTouchListener
        }

        composeToolbarViewController.updateIcons()
        // strange crash on my device. register later
        runOnUiThread {
            config.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        }
    }

    override fun showTouchAreaDialog() = TouchAreaDialogFragment(ebWebView.url.orEmpty())
        .show(supportFragmentManager, "TouchAreaDialog")

    override fun showTranslationConfigDialog(translateDirectly: Boolean) {
        maybeInitTwoPaneController()
        TranslationConfigDlgFragment(ebWebView.url.orEmpty()) { shouldTranslate ->
            if (shouldTranslate) {
                translate(config.translationMode)
            } else {
                ebWebView.reload()
            }
        }
            .show(supportFragmentManager, "TranslationConfigDialog")
    }

    override fun attachBaseContext(newBase: Context) {
        if (config.uiLocaleLanguage.isNotEmpty()) {
            super.attachBaseContext(
                LocaleManager.setLocale(newBase, config.uiLocaleLanguage)
            )
        } else {
            super.attachBaseContext(newBase)
        }
    }

    private fun updateLocale(context: Context, languageCode: String) {
        val newContext = LocaleManager.setLocale(context, languageCode)
        val intent = Intent(newContext, BrowserActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }


    private var fabImagePositionChanged = false
    private val preferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                ConfigManager.K_HIDE_STATUSBAR -> {
                    if (config.hideStatusbar) {
                        hideStatusBar()
                    } else {
                        showStatusBar()
                    }
                }

                ConfigManager.K_TOOLBAR_ICONS_FOR_LARGE,
                ConfigManager.K_TOOLBAR_ICONS,
                    -> {
                    composeToolbarViewController.updateIcons()
                }

                ConfigManager.K_SHOW_TAB_BAR -> {
                    composeToolbarViewController.showTabbar(config.shouldShowTabBar)
                }

                ConfigManager.K_FONT_TYPE -> {
                    if (config.fontType == FontType.SYSTEM_DEFAULT) {
                        ebWebView.reload()
                    } else {
                        ebWebView.updateCssStyle()
                    }
                }

                ConfigManager.K_READER_FONT_TYPE -> {
                    if (config.readerFontType == FontType.SYSTEM_DEFAULT) {
                        ebWebView.reload()
                    } else {
                        ebWebView.updateCssStyle()
                    }
                }

                ConfigManager.K_FONT_SIZE -> {
                    ebWebView.settings.textZoom = config.fontSize
                }

                ConfigManager.K_READER_FONT_SIZE -> {
                    if (ebWebView.shouldUseReaderFont()) {
                        ebWebView.settings.textZoom = config.readerFontSize
                    }
                }

                ConfigManager.K_BOLD_FONT -> {
                    composeToolbarViewController.updateIcons()
                    if (config.boldFontStyle) {
                        ebWebView.updateCssStyle()
                    } else {
                        ebWebView.reload()
                    }
                }

                ConfigManager.K_BLACK_FONT -> {
                    composeToolbarViewController.updateIcons()
                    if (config.blackFontStyle) {
                        ebWebView.updateCssStyle()
                    } else {
                        ebWebView.reload()
                    }
                }

                ConfigManager.K_ENABLE_IMAGE_ADJUSTMENT -> ebWebView.reload()

                ConfigManager.K_CUSTOM_FONT -> {
                    if (config.fontType == FontType.CUSTOM) {
                        ebWebView.updateCssStyle()
                    }
                }

                ConfigManager.K_READER_CUSTOM_FONT -> {
                    if (config.readerFontType == FontType.CUSTOM && ebWebView.shouldUseReaderFont()) {
                        ebWebView.updateCssStyle()
                    }
                }

                ConfigManager.K_IS_INCOGNITO_MODE -> {
                    ebWebView.incognito = config.isIncognitoMode
                    composeToolbarViewController.updateIcons()
                    EBToast.showShort(
                        this,
                        "Incognito mode is " + if (config.isIncognitoMode) "enabled." else "disabled."
                    )
                }

                ConfigManager.K_KEEP_AWAKE -> {
                    if (config.keepAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                ConfigManager.K_DESKTOP -> {
                    ebWebView.updateUserAgentString()
                    ebWebView.reload()
                    composeToolbarViewController.updateIcons()
                }

                ConfigManager.K_DARK_MODE -> config.restartChanged = true
                ConfigManager.K_TOOLBAR_TOP -> ViewUnit.updateAppbarPosition(binding)

                ConfigManager.K_NAV_POSITION -> config.restartChanged = true

                ConfigManager.K_TTS_SPEED_VALUE ->
                    ttsViewModel.setSpeechRate(config.ttsSpeedValue / 100f)

                ConfigManager.K_CUSTOM_USER_AGENT,
                ConfigManager.K_ENABLE_CUSTOM_USER_AGENT,
                    -> {
                    ebWebView.updateUserAgentString()
                    ebWebView.reload()
                }

                ConfigManager.K_ENABLE_TOUCH -> {
                    updateTouchView()
                    touchController.toggleTouchPageTurn(config.enableTouchTurn)
                }

                ConfigManager.K_TOUCH_AREA_ACTION_SWITCH -> {
                    updateTouchView()
                }

                ConfigManager.K_GPT_ACTION_ITEMS ->
                    actionModeMenuViewModel.updateMenuInfos(this, translationViewModel)
            }
        }

    private lateinit var fabImageViewController: FabImageViewController
    private lateinit var captureToggleButton: TextView
    private lateinit var missionWizardButton: TextView
    private lateinit var missionWizardBanner: TextView
    private lateinit var missionWizardCard: LinearLayout
    private lateinit var missionWizardStepTitle: TextView
    private lateinit var missionWizardStepState: TextView
    private lateinit var missionWizardStepProgress: ProgressBar
    private lateinit var missionWizardStepMissing: TextView
    private lateinit var missionWizardStepHints: TextView
    private lateinit var missionWizardStepStatus: TextView
    private lateinit var missionWizardActionStart: TextView
    private lateinit var missionWizardActionReady: TextView
    private lateinit var missionWizardActionCheck: TextView
    private lateinit var missionWizardActionPause: TextView
    private lateinit var missionWizardActionNext: TextView
    private lateinit var missionWizardActionMinimize: TextView
    private var missionWizardOverlayMinimized: Boolean = false
    private var missionWizardCardDragActive: Boolean = false
    private var missionWizardCardDragMoved: Boolean = false
    private var missionWizardCardDragFrameVisible: Boolean = false
    private var missionWizardCardDragStartRawX: Float = 0f
    private var missionWizardCardDragStartRawY: Float = 0f
    private var missionWizardCardDragStartTranslationX: Float = 0f
    private var missionWizardCardDragStartTranslationY: Float = 0f
    private val missionWizardCardDragEdgeDp: Float = 18f
    private var pendingWizardAutoAdvanceStepId: String? = null
    private var pendingWizardAutoAdvanceRunnable: Runnable? = null
    private var pendingWizardUndoState: RuntimeToolkitTelemetry.MissionSessionState? = null
    private var pendingWizardUndoRunnable: Runnable? = null
    private var pendingWizardUndoExpiresAtMs: Long = 0L
    private var pendingMissionBannerUrl: String = ""
    private val wizardAutoAdvanceDelayMs: Long = 3_000L
    private val wizardReadyWindowSeconds: Long = 90L

    private fun initFAB() {
        fabImageViewController = FabImageViewController(
            orientation,
            findViewById(R.id.fab_imageButtonNav),
            this::showToolbar,
            longClickAction = {
                if (config.enableNavButtonGesture) {
                    gestureHandler.handle(config.navButtonLongClickGesture)
                } else {
                    showFastToggleDialog()
                }
            }
        )
    }

    private fun initCaptureOverlayButton() {
        captureToggleButton = findViewById(R.id.fab_capture_toggle)
        if (!isMapperDebuggableBuild()) {
            captureToggleButton.visibility = GONE
            return
        }

        captureToggleButton.visibility = VISIBLE
        captureToggleButton.contentDescription = getString(R.string.mapper_capture_button_desc)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captureToggleButton.tooltipText = getString(R.string.mapper_capture_button_desc)
        }
        updateCaptureToggleUi(RuntimeToolkitTelemetry.isCaptureEnabled(this))
        captureToggleButton.setOnClickListener {
            val isEnabled = RuntimeToolkitTelemetry.isCaptureEnabled(this)
            if (isEnabled) {
                RuntimeToolkitTelemetry.stopCaptureSession(this, source = "floating_button")
                updateCaptureToggleUi(false)
                EBToast.showShort(this, getString(R.string.mapper_capture_stopped))
            } else {
                RuntimeToolkitTelemetry.startCaptureSession(this, source = "floating_button")
                updateCaptureToggleUi(true)
                EBToast.showShort(this, getString(R.string.mapper_capture_started))
            }
        }
        captureToggleButton.setOnLongClickListener {
            promptRuntimeExportDestination()
            true
        }
    }

    private fun updateCaptureToggleUi(captureEnabled: Boolean) {
        if (!this::captureToggleButton.isInitialized) return
        if (captureEnabled) {
            captureToggleButton.text = getString(R.string.mapper_capture_label_on)
            captureToggleButton.contentDescription = getString(R.string.mapper_capture_button_desc_on)
            captureToggleButton.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            captureToggleButton.text = getString(R.string.mapper_capture_label_off)
            captureToggleButton.contentDescription = getString(R.string.mapper_capture_button_desc_off)
            captureToggleButton.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    private fun initMissionWizardOverlay() {
        RuntimeToolkitMissionWizard.ensureRegistryLoaded(this)
        missionWizardButton = findViewById(R.id.fab_mission_wizard)
        missionWizardBanner = findViewById(R.id.mapper_wizard_banner)
        missionWizardCard = findViewById(R.id.mapper_wizard_card)
        missionWizardStepTitle = findViewById(R.id.mapper_wizard_step_title)
        missionWizardStepState = findViewById(R.id.mapper_wizard_step_state)
        missionWizardStepProgress = findViewById(R.id.mapper_wizard_step_progress)
        missionWizardStepMissing = findViewById(R.id.mapper_wizard_step_missing)
        missionWizardStepHints = findViewById(R.id.mapper_wizard_step_hints)
        missionWizardStepStatus = findViewById(R.id.mapper_wizard_step_status)
        missionWizardActionStart = findViewById(R.id.mapper_wizard_action_start)
        missionWizardActionReady = findViewById(R.id.mapper_wizard_action_ready)
        missionWizardActionCheck = findViewById(R.id.mapper_wizard_action_check)
        missionWizardActionPause = findViewById(R.id.mapper_wizard_action_pause)
        missionWizardActionNext = findViewById(R.id.mapper_wizard_action_next)
        missionWizardActionMinimize = findViewById(R.id.mapper_wizard_action_minimize)

        missionWizardButton.visibility = VISIBLE
        missionWizardButton.text = getString(R.string.mapper_wizard_button_label)
        missionWizardButton.contentDescription = getString(R.string.mapper_wizard_button_desc)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            missionWizardButton.tooltipText = getString(R.string.mapper_wizard_button_desc)
        }
        missionWizardButton.setOnClickListener {
            val session = RuntimeToolkitTelemetry.missionSessionState(this)
            if (session.missionId.isBlank()) {
                showMissionLauncherDialog()
            } else if (missionWizardOverlayMinimized) {
                setMissionWizardOverlayMinimized(false)
            } else {
                showMissionWizardPanel()
            }
        }
        missionWizardButton.setOnLongClickListener {
            showMissionLauncherDialog()
            true
        }

        missionWizardBanner.visibility = GONE
        missionWizardBanner.setOnClickListener {
            val seedUrl = pendingMissionBannerUrl
            pendingMissionBannerUrl = ""
            missionWizardBanner.visibility = GONE
            showMissionWizardSetupDialog(
                missionId = RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE,
                seedUrl = seedUrl,
            )
        }
        if (pendingMissionBannerUrl.isNotBlank()) {
            missionWizardBanner.text = getString(R.string.mapper_wizard_banner_label)
            missionWizardBanner.visibility = VISIBLE
        }

        missionWizardActionStart.setOnClickListener { beginCurrentWizardStep() }
        missionWizardActionReady.setOnClickListener { armCurrentWizardReadyWindow() }
        missionWizardActionCheck.setOnClickListener { checkCurrentWizardStepSaturation(openWizardPanel = false) }
        missionWizardActionPause.setOnClickListener {
            if (!holdPendingWizardAutoAdvance()) {
                if (RuntimeToolkitTelemetry.isCaptureEnabled(this)) {
                    RuntimeToolkitTelemetry.stopCaptureSession(this, source = "wizard_overlay_pause")
                    updateCaptureToggleUi(false)
                    EBToast.showShort(this, getString(R.string.mapper_capture_stopped))
                }
            }
            updateMissionWizardUi()
        }
        missionWizardActionNext.setOnClickListener {
            if (isWizardUndoWindowActive()) {
                undoLastWizardAutoAdvance()
            } else {
                moveToNextWizardStep(openWizardPanel = false)
            }
        }
        missionWizardActionMinimize.setOnClickListener {
            setMissionWizardOverlayMinimized(!missionWizardOverlayMinimized)
        }
        missionWizardCard.setOnTouchListener { _, event ->
            handleMissionWizardCardDrag(event)
        }
        updateMissionWizardUi()
    }

    private fun updateMissionWizardUi() {
        if (!this::missionWizardButton.isInitialized) return
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        if (state.missionId.isBlank()) {
            missionWizardOverlayMinimized = false
            missionWizardCard.translationX = 0f
            missionWizardCard.translationY = 0f
            setMissionWizardCardDragFrameVisible(false)
            missionWizardButton.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
            missionWizardButton.text = getString(R.string.mapper_wizard_button_label)
        } else {
            val stepLabel = when {
                state.wizardStepId.startsWith("home") -> "HOME"
                state.wizardStepId.startsWith("search") -> "SRCH"
                state.wizardStepId.startsWith("detail") -> "DTL"
                state.wizardStepId.startsWith("playback") -> "PLAY"
                state.wizardStepId.startsWith("auth") -> "AUTH"
                state.wizardStepId.startsWith("final") -> "DONE"
                else -> "WIZ"
            }
            missionWizardButton.text = stepLabel
            val color = if (state.saturationState == RuntimeToolkitMissionWizard.SATURATION_SATURATED) {
                android.R.color.holo_green_dark
            } else {
                android.R.color.holo_orange_dark
            }
            missionWizardButton.setTextColor(ContextCompat.getColor(this, color))
        }
        updateMissionWizardCard()
    }

    private fun updateMissionWizardCard() {
        if (!this::missionWizardCard.isInitialized) return
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        if (state.missionId.isBlank()) {
            missionWizardCard.visibility = GONE
            return
        }
        missionWizardCard.visibility = VISIBLE
        val step = RuntimeToolkitMissionWizard.stepById(state.missionId, state.wizardStepId, this)
        val feedback = RuntimeToolkitTelemetry.evaluateMissionStepFeedback(this, state.wizardStepId)
        val displayName = step?.displayName?.ifBlank { state.wizardStepId } ?: state.wizardStepId
        missionWizardStepTitle.text = "$displayName (${state.wizardStepId})"
        val optionalSuffix = if (step?.optional == true) " | optional" else ""
        missionWizardStepState.text = "${feedback.state} | ${feedback.progressPercent}%$optionalSuffix"
        val stateColor = when (feedback.state) {
            RuntimeToolkitMissionWizard.SATURATION_SATURATED -> android.R.color.holo_green_dark
            RuntimeToolkitMissionWizard.SATURATION_BLOCKED -> android.R.color.holo_red_dark
            RuntimeToolkitMissionWizard.SATURATION_NEEDS_MORE_EVIDENCE -> android.R.color.holo_orange_dark
            else -> android.R.color.black
        }
        missionWizardStepState.setTextColor(ContextCompat.getColor(this, stateColor))
        missionWizardStepProgress.progress = feedback.progressPercent
        val missingSignals = feedback.missingSignals.take(3)
        val hints = feedback.userHints.take(3)
        missionWizardStepMissing.text = if (missingSignals.isEmpty()) {
            getString(R.string.mapper_wizard_overlay_missing_prefix, "-")
        } else {
            getString(R.string.mapper_wizard_overlay_missing_prefix, missingSignals.joinToString(", "))
        }
        missionWizardStepHints.text = if (hints.isEmpty()) {
            getString(R.string.mapper_wizard_overlay_hints_prefix, "-")
        } else {
            getString(
                R.string.mapper_wizard_overlay_hints_prefix,
                hints.joinToString(" | ")
            )
        }

        val readyWindow = RuntimeToolkitTelemetry.readyWindowState(this)
        val readyHit = RuntimeToolkitTelemetry.latestReadyHitState(this)
        val now = System.currentTimeMillis()
        val readySecondsLeft = if (readyWindow.withinWindow) {
            ((readyWindow.expiresAtEpochMillis - now).coerceAtLeast(0L) / 1000L).toInt()
        } else {
            0
        }
        val statusText = when {
            pendingWizardAutoAdvanceStepId != null -> getString(R.string.mapper_wizard_overlay_status_auto_advance)
            isWizardUndoWindowActive() -> {
                val secondsLeft = ((pendingWizardUndoExpiresAtMs - now).coerceAtLeast(0L) / 1000L).toInt()
                getString(R.string.mapper_wizard_overlay_status_undo, secondsLeft)
            }
            readyWindow.withinWindow -> getString(
                R.string.mapper_wizard_overlay_status_ready_window,
                readySecondsLeft,
                readyWindow.armedStepId,
            )
            readyHit.recent -> getString(R.string.mapper_wizard_overlay_status_ready_hit)
            else -> "${getString(R.string.mapper_wizard_overlay_status_idle)} (${wizardReasonLabel(feedback.reason)})"
        }
        val compactMode = missionWizardOverlayMinimized
        if (compactMode) {
            missionWizardStepTitle.text = "Focus: $displayName (${state.wizardStepId})"
        }
        missionWizardStepStatus.text = if (compactMode) {
            "Minimized | Focus ${feedback.progressPercent}% | $statusText"
        } else {
            statusText
        }

        missionWizardStepProgress.visibility = if (compactMode) GONE else VISIBLE
        missionWizardStepMissing.visibility = if (compactMode) GONE else VISIBLE
        missionWizardStepHints.visibility = if (compactMode) GONE else VISIBLE
        missionWizardActionStart.visibility = if (compactMode) GONE else VISIBLE
        missionWizardActionReady.visibility =
            if (!compactMode && RuntimeToolkitTelemetry.isReadyActionStep(state.wizardStepId)) VISIBLE else GONE
        missionWizardActionCheck.visibility = if (compactMode) GONE else VISIBLE
        missionWizardActionPause.visibility = if (compactMode) GONE else VISIBLE
        missionWizardActionNext.visibility = if (compactMode) GONE else VISIBLE
        missionWizardActionPause.text = getString(
            if (pendingWizardAutoAdvanceStepId != null) {
                R.string.mapper_wizard_overlay_hold
            } else {
                R.string.mapper_wizard_overlay_pause
            }
        )
        missionWizardActionNext.text = getString(
            if (isWizardUndoWindowActive()) {
                R.string.mapper_wizard_overlay_undo
            } else {
                R.string.mapper_wizard_overlay_next
            }
        )
        missionWizardActionMinimize.text = getString(
            if (missionWizardOverlayMinimized) {
                R.string.mapper_wizard_overlay_menu_restore
            } else {
                R.string.mapper_wizard_overlay_minimize
            }
        )
    }

    private fun setMissionWizardOverlayMinimized(minimized: Boolean) {
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        if (state.missionId.isBlank()) {
            missionWizardOverlayMinimized = false
            missionWizardCard.visibility = GONE
            setMissionWizardCardDragFrameVisible(false)
            return
        }
        if (missionWizardOverlayMinimized == minimized) return
        missionWizardOverlayMinimized = minimized
        updateMissionWizardUi()
        EBToast.showShort(
            this,
            getString(
                if (minimized) {
                    R.string.mapper_wizard_overlay_minimized_toast
                } else {
                    R.string.mapper_wizard_overlay_restored_toast
                }
            ),
        )
    }

    private fun handleMissionWizardCardDrag(event: MotionEvent): Boolean {
        if (!this::missionWizardCard.isInitialized) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isMissionWizardCardDragTouch(event)) return false
                missionWizardCardDragActive = true
                missionWizardCardDragMoved = false
                missionWizardCardDragStartRawX = event.rawX
                missionWizardCardDragStartRawY = event.rawY
                missionWizardCardDragStartTranslationX = missionWizardCard.translationX
                missionWizardCardDragStartTranslationY = missionWizardCard.translationY
                setMissionWizardCardDragFrameVisible(true)
                missionWizardCard.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!missionWizardCardDragActive) return false
                val dx = event.rawX - missionWizardCardDragStartRawX
                val dy = event.rawY - missionWizardCardDragStartRawY
                if (!missionWizardCardDragMoved && (abs(dx) > 6f || abs(dy) > 6f)) {
                    missionWizardCardDragMoved = true
                }
                updateMissionWizardCardTranslation(
                    desiredTranslationX = missionWizardCardDragStartTranslationX + dx,
                    desiredTranslationY = missionWizardCardDragStartTranslationY + dy,
                )
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val moved = missionWizardCardDragMoved
                missionWizardCardDragActive = false
                missionWizardCardDragMoved = false
                setMissionWizardCardDragFrameVisible(false)
                missionWizardCard.parent?.requestDisallowInterceptTouchEvent(false)
                return moved
            }
        }
        return false
    }

    private fun isMissionWizardCardDragTouch(event: MotionEvent): Boolean {
        if (!this::missionWizardCard.isInitialized) return false
        val width = missionWizardCard.width.toFloat()
        val height = missionWizardCard.height.toFloat()
        if (width <= 0f || height <= 0f) return false
        val edgePx = missionWizardCardDragEdgeDp * resources.displayMetrics.density
        val x = event.x
        val y = event.y
        return x <= edgePx ||
            y <= edgePx ||
            x >= (width - edgePx) ||
            y >= (height - edgePx)
    }

    private fun setMissionWizardCardDragFrameVisible(visible: Boolean) {
        if (!this::missionWizardCard.isInitialized) return
        if (missionWizardCardDragFrameVisible == visible) return
        missionWizardCardDragFrameVisible = visible
        val backgroundRes = if (visible) {
            R.drawable.mapper_wizard_drag_border
        } else {
            R.drawable.background_with_border
        }
        missionWizardCard.background = ContextCompat.getDrawable(this, backgroundRes)
    }

    private fun updateMissionWizardCardTranslation(
        desiredTranslationX: Float,
        desiredTranslationY: Float,
    ) {
        if (!this::missionWizardCard.isInitialized) return
        val rootView = binding.root
        if (rootView.width <= 0 || rootView.height <= 0 || missionWizardCard.width <= 0 || missionWizardCard.height <= 0) {
            missionWizardCard.translationX = desiredTranslationX
            missionWizardCard.translationY = desiredTranslationY
            return
        }
        val minLeft = 0f
        val maxLeft = (rootView.width - missionWizardCard.width).toFloat().coerceAtLeast(0f)
        val minTop = 0f
        val maxTop = (rootView.height - missionWizardCard.height).toFloat().coerceAtLeast(0f)

        val desiredLeft = missionWizardCard.left + desiredTranslationX
        val desiredTop = missionWizardCard.top + desiredTranslationY
        val clampedLeft = desiredLeft.coerceIn(minLeft, maxLeft)
        val clampedTop = desiredTop.coerceIn(minTop, maxTop)

        missionWizardCard.translationX = clampedLeft - missionWizardCard.left
        missionWizardCard.translationY = clampedTop - missionWizardCard.top
    }

    private fun isWizardUndoWindowActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        return pendingWizardUndoState != null && nowMs <= pendingWizardUndoExpiresAtMs
    }

    private fun clearWizardUndoWindow() {
        pendingWizardUndoRunnable?.let { binding.root.removeCallbacks(it) }
        pendingWizardUndoRunnable = null
        pendingWizardUndoState = null
        pendingWizardUndoExpiresAtMs = 0L
    }

    private fun armWizardUndoWindow(previousState: RuntimeToolkitTelemetry.MissionSessionState) {
        clearWizardUndoWindow()
        val expiresAt = System.currentTimeMillis() + wizardAutoAdvanceDelayMs
        pendingWizardUndoState = previousState
        pendingWizardUndoExpiresAtMs = expiresAt
        val clearRunnable = Runnable {
            clearWizardUndoWindow()
            updateMissionWizardUi()
        }
        pendingWizardUndoRunnable = clearRunnable
        binding.root.postDelayed(clearRunnable, wizardAutoAdvanceDelayMs)
    }

    private fun clearPendingWizardAutoAdvance() {
        pendingWizardAutoAdvanceRunnable?.let { binding.root.removeCallbacks(it) }
        pendingWizardAutoAdvanceRunnable = null
        pendingWizardAutoAdvanceStepId = null
    }

    private fun holdPendingWizardAutoAdvance(): Boolean {
        if (pendingWizardAutoAdvanceStepId == null) return false
        val heldStep = pendingWizardAutoAdvanceStepId.orEmpty()
        clearPendingWizardAutoAdvance()
        val session = RuntimeToolkitTelemetry.missionSessionState(this)
        RuntimeToolkitTelemetry.logWizardEvent(
            context = this,
            operation = "wizard_auto_advance_held",
            missionId = session.missionId,
            wizardStepId = heldStep,
            saturationState = session.stepStates[heldStep] ?: RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
            phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
            targetSiteId = session.targetSiteId,
        )
        EBToast.showShort(this, getString(R.string.mapper_wizard_overlay_auto_advance_held))
        return true
    }

    private fun scheduleWizardAutoAdvance(stepId: String, reason: String) {
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        val nextStep = RuntimeToolkitMissionWizard.nextStepId(state.missionId, stepId, this)
        if (nextStep == null) return
        clearPendingWizardAutoAdvance()
        val runnable = Runnable {
            pendingWizardAutoAdvanceStepId = null
            pendingWizardAutoAdvanceRunnable = null
            val before = RuntimeToolkitTelemetry.missionSessionState(this)
            val currentSaturation = before.stepStates[stepId] ?: before.saturationState
            if (before.wizardStepId != stepId || currentSaturation != RuntimeToolkitMissionWizard.SATURATION_SATURATED) {
                updateMissionWizardUi()
                return@Runnable
            }
            RuntimeToolkitTelemetry.clearWizardReadyWindow(this, reason = "auto_advance_step_change")
            val after = RuntimeToolkitTelemetry.advanceMissionWizardStep(this)
            RuntimeToolkitTelemetry.logWizardEvent(
                context = this,
                operation = "wizard_auto_advance",
                missionId = after.missionId,
                wizardStepId = after.wizardStepId,
                saturationState = after.saturationState,
                phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
                targetSiteId = after.targetSiteId,
                payload = mapOf(
                    "from_step_id" to stepId,
                    "reason" to reason,
                ),
            )
            RuntimeToolkitTelemetry.logWizardEvent(
                context = this,
                operation = "wizard_step_started",
                missionId = after.missionId,
                wizardStepId = after.wizardStepId,
                saturationState = after.saturationState,
                phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
                targetSiteId = after.targetSiteId,
                payload = mapOf("source" to "auto_advance"),
            )
            val step = RuntimeToolkitMissionWizard.stepById(after.missionId, after.wizardStepId, this)
            if (!step?.phaseId.isNullOrBlank()) {
                RuntimeToolkitTelemetry.logProbePhaseEvent(
                    context = this,
                    phaseId = step?.phaseId ?: "background_noise",
                    transition = "start",
                    payload = mapOf("source" to "auto_advance"),
                )
            }
            armWizardUndoWindow(before)
            updateMissionWizardUi()
        }
        pendingWizardAutoAdvanceStepId = stepId
        pendingWizardAutoAdvanceRunnable = runnable
        binding.root.postDelayed(runnable, wizardAutoAdvanceDelayMs)
        updateMissionWizardUi()
    }

    private fun undoLastWizardAutoAdvance() {
        val undoState = pendingWizardUndoState ?: return
        if (!isWizardUndoWindowActive()) {
            clearWizardUndoWindow()
            updateMissionWizardUi()
            return
        }
        RuntimeToolkitTelemetry.clearWizardReadyWindow(this, reason = "auto_advance_undo")
        val currentState = RuntimeToolkitTelemetry.missionSessionState(this)
        val currentStep = RuntimeToolkitMissionWizard.stepById(
            missionId = currentState.missionId,
            stepId = currentState.wizardStepId,
            context = this,
        )
        val currentPhaseId = currentStep?.phaseId.orEmpty()
        if (currentPhaseId.isNotBlank()) {
            RuntimeToolkitTelemetry.logProbePhaseEvent(
                context = this,
                phaseId = currentPhaseId,
                transition = "stop",
                payload = mapOf("source" to "auto_advance_undo"),
            )
        }
        RuntimeToolkitTelemetry.clearActivePhaseId(this)
        val restoreSaturation = undoState.stepStates[undoState.wizardStepId]
            ?: RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE
        val restored = RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = this,
            stepId = undoState.wizardStepId,
            saturationState = restoreSaturation,
        )
        val restoredStep = RuntimeToolkitMissionWizard.stepById(
            missionId = restored.missionId,
            stepId = restored.wizardStepId,
            context = this,
        )
        val restoredPhaseId = restoredStep?.phaseId.orEmpty()
        if (restoredPhaseId.isNotBlank()) {
            RuntimeToolkitTelemetry.logProbePhaseEvent(
                context = this,
                phaseId = restoredPhaseId,
                transition = "start",
                payload = mapOf("source" to "auto_advance_undo"),
            )
        }
        RuntimeToolkitTelemetry.logWizardEvent(
            context = this,
            operation = "wizard_auto_advance_undo",
            missionId = undoState.missionId,
            wizardStepId = undoState.wizardStepId,
            saturationState = restoreSaturation,
            phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
            targetSiteId = undoState.targetSiteId,
        )
        clearWizardUndoWindow()
        updateMissionWizardUi()
    }

    private fun armCurrentWizardReadyWindow() {
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        if (!RuntimeToolkitTelemetry.isReadyActionStep(state.wizardStepId)) {
            EBToast.showShort(this, getString(R.string.mapper_wizard_overlay_ready_not_supported))
            return
        }
        RuntimeToolkitTelemetry.beginWizardReadyWindow(
            context = this,
            stepId = state.wizardStepId,
            source = "wizard_overlay_ready_button",
        )
        EBToast.showShort(
            this,
            getString(R.string.mapper_wizard_overlay_ready_started, wizardReadyWindowSeconds),
        )
        updateMissionWizardUi()
    }

    private fun isLauncherMainIntent(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_MAIN) return false
        val categories = intent.categories ?: emptySet<String>()
        return categories.contains(Intent.CATEGORY_LAUNCHER)
    }

    private fun showMissionWizardBannerForUrl(url: String) {
        if (!this::missionWizardBanner.isInitialized) {
            pendingMissionBannerUrl = url
            return
        }
        val session = RuntimeToolkitTelemetry.missionSessionState(this)
        if (session.missionId.isNotBlank()) {
            missionWizardBanner.visibility = GONE
            return
        }
        pendingMissionBannerUrl = url
        missionWizardBanner.text = getString(R.string.mapper_wizard_banner_label)
        missionWizardBanner.visibility = VISIBLE
    }

    private fun showMissionLauncherDialog() {
        if (supportFragmentManager.findFragmentByTag(MissionLauncherDialogFragment.TAG) != null) return
        MissionLauncherDialogFragment()
            .show(supportFragmentManager, MissionLauncherDialogFragment.TAG)
    }

    private fun showMissionWizardSetupDialog(missionId: String, seedUrl: String) {
        if (supportFragmentManager.findFragmentByTag(MissionWizardSetupDialogFragment.TAG) != null) return
        MissionWizardSetupDialogFragment
            .newInstance(missionId = missionId, targetUrl = seedUrl)
            .show(supportFragmentManager, MissionWizardSetupDialogFragment.TAG)
    }

    override fun onMissionLauncherSelectMission(missionId: String) {
        showMissionWizardSetupDialog(missionId = missionId, seedUrl = "")
    }

    override fun onMissionLauncherOpenFixtureReplay() {
        showMissionFixtureReplayDialog()
    }

    private fun showMissionFixtureReplayDialog() {
        val status = RuntimeToolkitTelemetry.fixtureReplayStatus(this)
        val canStartReplayMission = RuntimeToolkitMissionWizard.isMissionImplemented(
            RuntimeToolkitMissionWizard.MISSION_REPLAY_BUNDLE,
            this,
        )
        if (supportFragmentManager.findFragmentByTag(MissionFixtureReplayDialogFragment.TAG) != null) return
        MissionFixtureReplayDialogFragment
            .newInstance(
                title = getString(R.string.mapper_mission_fixture_replay),
                body = formatFixtureReplayStatusText(status),
                canStartReplayMission = canStartReplayMission,
            )
            .show(supportFragmentManager, MissionFixtureReplayDialogFragment.TAG)
    }

    private fun formatFixtureReplayStatusText(status: RuntimeToolkitTelemetry.FixtureReplayStatus): String {
        val lines = mutableListOf<String>()
        lines += getString(R.string.mapper_mission_fixture_replay_message)
        lines += ""
        lines += "ready: ${status.ready}"
        lines += "replay_bundle: ${status.replayBundlePresent}"
        lines += "fixture_manifest: ${status.fixtureManifestPresent}"
        lines += "response_index: ${status.responseIndexPresent}"
        lines += "runtime_events: ${status.runtimeEventsPresent}"
        lines += "latest_export: ${status.latestExportPresent}"
        if (status.latestExportPath.isNotBlank()) {
            lines += "latest_export_path: ${status.latestExportPath}"
        }
        return lines.joinToString("\n")
    }

    private fun showMissionExportHistoryDialog() {
        val exports = RuntimeToolkitTelemetry.listExportBundles(this)
        val lines = mutableListOf<String>()
        val bundleLabels = mutableListOf<String>()
        val bundlePaths = mutableListOf<String>()
        if (exports.isEmpty()) {
            lines += getString(R.string.mapper_mission_export_history_empty)
        } else {
            exports.take(10).forEachIndexed { idx, bundle ->
                lines += "${idx + 1}. ${bundle.fileName}"
                lines += "   ${bundle.sizeBytes} bytes | ${bundle.modifiedAtUtc}"
                lines += "   ${bundle.absolutePath}"
                bundleLabels += "${bundle.fileName} (${bundle.modifiedAtUtc})"
                bundlePaths += bundle.absolutePath
            }
            if (exports.size > 10) {
                lines += ""
                lines += "+ ${exports.size - 10} more export bundles"
            }
        }
        val replayEnabled = RuntimeToolkitMissionWizard.isMissionImplemented(
            RuntimeToolkitMissionWizard.MISSION_REPLAY_BUNDLE,
            this,
        )
        if (supportFragmentManager.findFragmentByTag(MissionExportHistoryDialogFragment.TAG) != null) return
        MissionExportHistoryDialogFragment
            .newInstance(
                title = getString(R.string.mapper_mission_export_history_title),
                body = lines.joinToString("\n"),
                replayEnabled = replayEnabled,
                bundleLabels = bundleLabels,
                bundlePaths = bundlePaths,
            )
            .show(supportFragmentManager, MissionExportHistoryDialogFragment.TAG)
    }

    override fun onMissionFixtureReplayOpenExportHistory() {
        showMissionExportHistoryDialog()
    }

    private fun seedReplayMissionFromCurrentContext() {
        val session = RuntimeToolkitTelemetry.missionSessionState(this)
        val seedUrl = session.targetUrl.ifBlank { ebWebView.url.orEmpty() }
        showMissionWizardSetupDialog(
            missionId = RuntimeToolkitMissionWizard.MISSION_REPLAY_BUNDLE,
            seedUrl = seedUrl,
        )
    }

    override fun onMissionFixtureReplayStartReplayMission() {
        seedReplayMissionFromCurrentContext()
    }

    override fun onMissionExportHistoryRunReplay(bundlePath: String) {
        runFixtureReplayFromExport(bundlePath)
    }

    override fun onMissionExportHistoryStartReplayMission() {
        seedReplayMissionFromCurrentContext()
    }

    private fun runFixtureReplayFromExport(bundlePath: String) {
        lifecycleScope.launch {
            val readiness = withContext(Dispatchers.IO) {
                RuntimeToolkitTelemetry.evaluateExportBundleReplayReadiness(bundlePath)
            }
            if (!readiness.ready) {
                RuntimeToolkitTelemetry.logMissionEvent(
                    context = this@BrowserActivity,
                    operation = "fixture_replay_blocked",
                    missionId = RuntimeToolkitTelemetry.missionSessionState(this@BrowserActivity).missionId,
                    wizardStepId = RuntimeToolkitTelemetry.missionSessionState(this@BrowserActivity).wizardStepId,
                    saturationState = RuntimeToolkitTelemetry.missionSessionState(this@BrowserActivity).saturationState,
                    exportReadiness = "BLOCKED",
                    reason = "bundle_readiness_failed",
                    payload = mapOf(
                        "bundle_path" to bundlePath,
                        "missing_required_entries" to readiness.missingRequiredEntries,
                        "warnings" to readiness.warnings,
                    ),
                )
                AlertDialog.Builder(this@BrowserActivity)
                    .setTitle(getString(R.string.mapper_mission_fixture_replay))
                    .setMessage(
                        buildString {
                            append("Fixture replay blocked for selected bundle.\n\n")
                            append("Bundle: ${readiness.bundleFileName}\n")
                            append("Missing entries: ${readiness.missingRequiredEntries.joinToString(", ").ifBlank { "none" }}\n")
                            if (readiness.warnings.isNotEmpty()) {
                                append("Warnings: ${readiness.warnings.joinToString(", ")}")
                            }
                        },
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }

            EBToast.showShort(this@BrowserActivity, "Running fixture replay from export...")
            RuntimeToolkitTelemetry.logMissionEvent(
                context = this@BrowserActivity,
                operation = "fixture_replay_started",
                missionId = RuntimeToolkitTelemetry.missionSessionState(this@BrowserActivity).missionId,
                wizardStepId = RuntimeToolkitTelemetry.missionSessionState(this@BrowserActivity).wizardStepId,
                saturationState = RuntimeToolkitTelemetry.missionSessionState(this@BrowserActivity).saturationState,
                exportReadiness = "IN_PROGRESS",
                reason = "bundle_selected",
                payload = mapOf(
                    "bundle_path" to bundlePath,
                    "runnable_step_count" to readiness.runnableStepCount,
                    "replay_step_count" to readiness.replayStepCount,
                ),
            )

            val result = withContext(Dispatchers.IO) {
                RuntimeToolkitTelemetry.executeFixtureReplayFromExport(
                    context = this@BrowserActivity,
                    bundlePath = bundlePath,
                    maxRequests = 3,
                )
            }
            val exportReadiness = if (result.successCount > 0) "PARTIAL" else "BLOCKED"
            RuntimeToolkitTelemetry.logMissionEvent(
                context = this@BrowserActivity,
                operation = "fixture_replay_finished",
                missionId = RuntimeToolkitTelemetry.missionSessionState(this@BrowserActivity).missionId,
                wizardStepId = RuntimeToolkitTelemetry.missionSessionState(this@BrowserActivity).wizardStepId,
                saturationState = RuntimeToolkitTelemetry.missionSessionState(this@BrowserActivity).saturationState,
                exportReadiness = exportReadiness,
                reason = if (result.successCount > 0) "fixture_replay_success" else "fixture_replay_no_success",
                payload = mapOf(
                    "bundle_path" to bundlePath,
                    "attempted_count" to result.attemptedCount,
                    "success_count" to result.successCount,
                    "failed_count" to result.failedCount,
                    "skipped_count" to result.skippedCount,
                    "report_path" to result.reportPath,
                    "warnings" to result.warnings,
                ),
            )

            val summary = buildString {
                append("Bundle: ${result.bundleFileName}\n")
                append("Transport: ${result.transport}\n")
                append("Attempted: ${result.attemptedCount}\n")
                append("Success: ${result.successCount}\n")
                append("Failed: ${result.failedCount}\n")
                append("Skipped: ${result.skippedCount}\n")
                if (result.reportPath.isNotBlank()) {
                    append("Report: ${result.reportPath}\n")
                }
                if (result.warnings.isNotEmpty()) {
                    append("\nWarnings:\n")
                    result.warnings.forEach { append("- $it\n") }
                }
            }
            AlertDialog.Builder(this@BrowserActivity)
                .setTitle(getString(R.string.mapper_mission_fixture_replay))
                .setMessage(summary.trim())
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onMissionLauncherOpenAdvancedSettings() {
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        val lines = mutableListOf<String>()
        lines += getString(R.string.mapper_mission_advanced_settings_message_header)
        lines += ""
        lines += "mission_id: ${state.missionId.ifBlank { RuntimeToolkitMissionWizard.MISSION_FISHIT_PIPELINE }}"
        lines += "target_site_id: ${state.targetSiteId.ifBlank { "unknown_target" }}"
        lines += "saturation_state: ${state.saturationState}"
        lines += "scope_mode: ${RuntimeToolkitTelemetry.scopeMode(this)}"
        lines += "target_host_family: ${RuntimeToolkitTelemetry.targetHostFamily(this).joinToString(",")}"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mapper_mission_advanced_settings))
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onMissionSetupConfirmed(missionId: String, targetUrl: String) {
        if (!RuntimeToolkitMissionWizard.isMissionImplemented(missionId, this)) {
            EBToast.showShort(this, getString(R.string.mapper_mission_not_available))
            return
        }
        if (targetUrl.isBlank()) {
            EBToast.showShort(this, getString(R.string.mapper_target_url_required))
            showMissionWizardSetupDialog(missionId = missionId, seedUrl = "")
            return
        }
        RuntimeToolkitTelemetry.logMissionEvent(
            context = this,
            operation = "mission_selected",
            missionId = missionId,
            wizardStepId = RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
            exportReadiness = "NOT_READY",
            reason = "setup_confirmed",
        )
        val state = RuntimeToolkitTelemetry.startMissionSession(
            context = this,
            missionId = missionId,
        )
        RuntimeToolkitTelemetry.setCaptureEnabled(this, false)
        updateCaptureToggleUi(false)
        RuntimeToolkitTelemetry.logWizardEvent(
            context = this,
            operation = "wizard_started",
            missionId = state.missionId,
            wizardStepId = state.wizardStepId,
            saturationState = state.saturationState,
            phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
            targetSiteId = state.targetSiteId,
        )
        applyMissionTargetUrl(targetUrl, autoAdvance = true)
        updateMissionWizardUi()
    }

    private fun applyMissionTargetUrl(urlInput: String, autoAdvance: Boolean) {
        val url = urlInput.trim()
        if (url.isBlank()) return
        val normalizedUrl = if (BrowserUnit.isURL(url)) url else "https://$url"
        clearPendingWizardAutoAdvance()
        clearWizardUndoWindow()
        val state = RuntimeToolkitTelemetry.setMissionTarget(this, normalizedUrl)
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = this,
            stepId = RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
        )
        RuntimeToolkitTelemetry.logWizardEvent(
            context = this,
            operation = "wizard_step_completed",
            missionId = state.missionId,
            wizardStepId = RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
            phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
            targetSiteId = state.targetSiteId,
            payload = mapOf("target_url" to normalizedUrl),
        )
        RuntimeToolkitTelemetry.logMissionEvent(
            context = this,
            operation = "mission_config_applied",
            missionId = state.missionId,
            wizardStepId = RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
            exportReadiness = "NOT_READY",
            reason = "target_url_bound",
            payload = mapOf(
                "target_url" to normalizedUrl,
                "target_site_id" to state.targetSiteId,
                "target_host_family" to state.targetHostFamily,
            ),
        )
        RuntimeToolkitTelemetry.startCaptureSession(
            context = this,
            source = "wizard_target_url_input",
        )
        updateCaptureToggleUi(true)
        if (currentAlbumController != null && config.isExternalSearchInSameTab) {
            trackedLoadUrl(normalizedUrl, "wizard_target_url_input")
        } else {
            trackedAddAlbum(normalizedUrl, "wizard_target_url_input")
        }
        if (autoAdvance) {
            scheduleWizardAutoAdvance(
                stepId = RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT,
                reason = "target_url_bound",
            )
        }
        updateMissionWizardUi()
    }

    private fun promptTargetUrlInput() {
        val input = EditText(this).apply {
            hint = getString(R.string.mapper_target_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(RuntimeToolkitTelemetry.missionSessionState(this@BrowserActivity).targetUrl)
        }
        AlertDialog.Builder(this)
            .setTitle("Target URL")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open") { _, _ ->
                applyMissionTargetUrl(input.text?.toString().orEmpty(), autoAdvance = true)
            }
            .show()
    }

    private fun formatWizardStatus(state: RuntimeToolkitTelemetry.MissionSessionState): String {
        val steps = RuntimeToolkitMissionWizard.stepsForMission(state.missionId, this)
        val lines = mutableListOf<String>()
        lines += "Mission: ${state.missionId.ifBlank { "none" }}"
        lines += "Current step: ${state.wizardStepId}"
        lines += "Saturation: ${state.saturationState}"
        lines += "Target site: ${state.targetSiteId.ifBlank { "unknown_target" }}"
        lines += "Target URL: ${state.targetUrl.ifBlank { "-" }}"
        lines += ""
        lines += "Steps:"
        steps.forEach { step ->
            val marker = if (step.stepId == state.wizardStepId) ">" else " "
            val status = state.stepStates[step.stepId] ?: RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE
            val optional = if (step.optional) " (optional)" else ""
            lines += "$marker ${step.stepId}$optional -> $status"
        }
        return lines.joinToString("\n")
    }

    private fun wizardReasonLabel(reason: String): String {
        return when (reason.trim()) {
            "target_url_bound" -> "target URL confirmed"
            "target_url_missing" -> "target URL missing"
            "home_probe_evidence_ok" -> "home evidence captured"
            "missing_home_target_response" -> "home traffic missing"
            "search_probe_saturated" -> "search evidence saturated"
            "search_probe_needs_more_variants" -> "run another search variant"
            "detail_probe_evidence_ok" -> "detail evidence captured"
            "missing_detail_target_response" -> "open at least one detail page"
            "playback_probe_evidence_ok" -> "playback evidence captured"
            "missing_playback_manifest_or_resolver" -> "playback resolver/manifest missing"
            "auth_chain_complete" -> "auth chain complete"
            "auth_chain_incomplete" -> "collect login/validation/refresh"
            "auth_optional_not_collected" -> "auth optional not collected yet"
            "mission_ready_for_export" -> "ready for export"
            "final_validation_incomplete" -> "final validation incomplete"
            "export_gate_blocked" -> "export blocked by fail-closed gate"
            else -> reason.ifBlank { "awaiting evidence" }
        }
    }

    private fun showMissionWizardPanel() {
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        if (state.missionId.isBlank()) {
            showMissionLauncherDialog()
            return
        }
        val toggleOverlayAction = getString(
            if (missionWizardOverlayMinimized) {
                R.string.mapper_wizard_overlay_menu_restore
            } else {
                R.string.mapper_wizard_overlay_menu_minimize
            }
        )
        val actions = arrayOf(
            "Start step",
            "Ready (arm 90s)",
            "Check saturation",
            "Next",
            "Retry",
            "Skip optional",
            "Finish",
            "Export summary",
            "Anchor: Create",
            "Anchor: Label",
            "Anchor: Remove",
            toggleOverlayAction,
            "Close",
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mapper_wizard_status_title))
            .setMessage(formatWizardStatus(state))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> beginCurrentWizardStep()
                    1 -> armCurrentWizardReadyWindow()
                    2 -> checkCurrentWizardStepSaturation(openWizardPanel = true)
                    3 -> moveToNextWizardStep(openWizardPanel = true)
                    4 -> retryCurrentWizardStep()
                    5 -> skipOptionalCurrentWizardStep()
                    6 -> finishMissionWizard()
                    7 -> showMissionExportSummaryDialog()
                    8 -> promptCreateAnchor()
                    9 -> promptLabelAnchor()
                    10 -> promptRemoveAnchor()
                    11 -> setMissionWizardOverlayMinimized(!missionWizardOverlayMinimized)
                    else -> Unit
                }
            }
            .show()
    }

    private fun beginCurrentWizardStep() {
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        val step = RuntimeToolkitMissionWizard.stepById(state.missionId, state.wizardStepId, this)
        if (step == null) {
            EBToast.showShort(this, "Unknown wizard step")
            return
        }
        clearPendingWizardAutoAdvance()
        clearWizardUndoWindow()
        RuntimeToolkitTelemetry.clearWizardReadyWindow(this, reason = "step_started")
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = this,
            stepId = step.stepId,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
        )
        if (!step.phaseId.isNullOrBlank()) {
            RuntimeToolkitTelemetry.logProbePhaseEvent(
                context = this,
                phaseId = step.phaseId,
                transition = "start",
            )
        }
        RuntimeToolkitTelemetry.logWizardEvent(
            context = this,
            operation = "wizard_step_started",
            missionId = state.missionId,
            wizardStepId = step.stepId,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_INCOMPLETE,
            phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
            targetSiteId = state.targetSiteId,
            payload = mapOf("instruction" to step.browserInstruction),
        )
        if (step.stepId == RuntimeToolkitMissionWizard.STEP_TARGET_URL_INPUT) {
            promptTargetUrlInput()
        } else {
            EBToast.showShort(this, step.browserInstruction)
        }
        updateMissionWizardUi()
    }

    private fun checkCurrentWizardStepSaturation(openWizardPanel: Boolean = false) {
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        val result = RuntimeToolkitTelemetry.evaluateMissionStepSaturation(
            context = this,
            stepId = state.wizardStepId,
        )
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = this,
            stepId = state.wizardStepId,
            saturationState = result.state,
        )
        RuntimeToolkitTelemetry.logWizardEvent(
            context = this,
            operation = "wizard_step_saturation_updated",
            missionId = state.missionId,
            wizardStepId = state.wizardStepId,
            saturationState = result.state,
            phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
            targetSiteId = state.targetSiteId,
            payload = result.metrics + mapOf("reason" to result.reason),
        )
        val step = RuntimeToolkitMissionWizard.stepById(state.missionId, state.wizardStepId, this)
        if (result.state == RuntimeToolkitMissionWizard.SATURATION_SATURATED && !step?.phaseId.isNullOrBlank()) {
            val completedPhaseId = RuntimeToolkitTelemetry.activePhaseId(this)
            RuntimeToolkitTelemetry.logProbePhaseEvent(
                context = this,
                phaseId = step?.phaseId ?: "background_noise",
                transition = "stop",
            )
            RuntimeToolkitTelemetry.clearActivePhaseId(this)
            RuntimeToolkitTelemetry.logWizardEvent(
                context = this,
                operation = "wizard_step_completed",
                missionId = state.missionId,
                wizardStepId = state.wizardStepId,
                saturationState = result.state,
                phaseId = completedPhaseId,
                targetSiteId = state.targetSiteId,
                payload = mapOf("reason" to result.reason),
            )
        }
        if (result.state == RuntimeToolkitMissionWizard.SATURATION_SATURATED &&
            state.wizardStepId != RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT
        ) {
            scheduleWizardAutoAdvance(
                stepId = state.wizardStepId,
                reason = result.reason,
            )
        } else {
            clearPendingWizardAutoAdvance()
        }
        updateMissionWizardUi()
        if (openWizardPanel) {
            showMissionWizardPanel()
        }
        EBToast.showShort(this, "${state.wizardStepId}: ${result.state}")
    }

    private fun moveToNextWizardStep(openWizardPanel: Boolean = true) {
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        val persisted = state.stepStates[state.wizardStepId].orEmpty()
        val evaluated = RuntimeToolkitTelemetry.evaluateMissionStepSaturation(
            context = this,
            stepId = state.wizardStepId,
        )
        val current = when {
            persisted == RuntimeToolkitMissionWizard.SATURATION_SATURATED -> persisted
            evaluated.state == RuntimeToolkitMissionWizard.SATURATION_SATURATED -> evaluated.state
            persisted.isNotBlank() -> persisted
            else -> evaluated.state
        }
        if (current != RuntimeToolkitMissionWizard.SATURATION_SATURATED) {
            if (RuntimeToolkitMissionWizard.isOptionalStep(state.missionId, state.wizardStepId, this)) {
                skipOptionalCurrentWizardStep(openWizardPanel = openWizardPanel)
                return
            }
            RuntimeToolkitTelemetry.logWizardEvent(
                context = this,
                operation = "wizard_step_blocked",
                missionId = state.missionId,
                wizardStepId = state.wizardStepId,
                saturationState = RuntimeToolkitMissionWizard.SATURATION_BLOCKED,
                phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
                targetSiteId = state.targetSiteId,
                payload = mapOf("reason" to "current_step_not_saturated"),
            )
            EBToast.showShort(this, "Current step not saturated")
            return
        }
        if (persisted != RuntimeToolkitMissionWizard.SATURATION_SATURATED) {
            RuntimeToolkitTelemetry.setMissionWizardStepState(
                context = this,
                stepId = state.wizardStepId,
                saturationState = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
            )
        }
        clearPendingWizardAutoAdvance()
        clearWizardUndoWindow()
        RuntimeToolkitTelemetry.clearWizardReadyWindow(this, reason = "step_advanced")
        val next = RuntimeToolkitTelemetry.advanceMissionWizardStep(this)
        RuntimeToolkitTelemetry.logWizardEvent(
            context = this,
            operation = "wizard_step_started",
            missionId = next.missionId,
            wizardStepId = next.wizardStepId,
            saturationState = next.saturationState,
            phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
            targetSiteId = next.targetSiteId,
        )
        val nextStep = RuntimeToolkitMissionWizard.stepById(next.missionId, next.wizardStepId, this)
        val nextPhaseId = nextStep?.phaseId.orEmpty()
        if (nextPhaseId.isNotBlank()) {
            RuntimeToolkitTelemetry.logProbePhaseEvent(
                context = this,
                phaseId = nextPhaseId,
                transition = "start",
                payload = mapOf("source" to "manual_next"),
            )
        }
        updateMissionWizardUi()
        if (openWizardPanel) {
            showMissionWizardPanel()
        }
    }

    private fun retryCurrentWizardStep() {
        clearPendingWizardAutoAdvance()
        clearWizardUndoWindow()
        RuntimeToolkitTelemetry.clearWizardReadyWindow(this, reason = "retry_step")
        val state = RuntimeToolkitTelemetry.retryMissionWizardStep(this)
        RuntimeToolkitTelemetry.logWizardEvent(
            context = this,
            operation = "wizard_step_started",
            missionId = state.missionId,
            wizardStepId = state.wizardStepId,
            saturationState = state.saturationState,
            phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
            targetSiteId = state.targetSiteId,
            payload = mapOf("retry" to true),
        )
        updateMissionWizardUi()
        showMissionWizardPanel()
    }

    private fun skipOptionalCurrentWizardStep(openWizardPanel: Boolean = true) {
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        if (!RuntimeToolkitMissionWizard.isOptionalStep(state.missionId, state.wizardStepId, this)) {
            EBToast.showShort(this, "Current step is not optional")
            return
        }
        clearPendingWizardAutoAdvance()
        clearWizardUndoWindow()
        RuntimeToolkitTelemetry.clearWizardReadyWindow(this, reason = "skip_optional")
        RuntimeToolkitTelemetry.logWizardEvent(
            context = this,
            operation = "wizard_step_completed",
            missionId = state.missionId,
            wizardStepId = state.wizardStepId,
            saturationState = RuntimeToolkitMissionWizard.SATURATION_SATURATED,
            phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
            targetSiteId = state.targetSiteId,
            payload = mapOf("skipped_optional" to true),
        )
        RuntimeToolkitTelemetry.skipOptionalMissionWizardStep(this)
        updateMissionWizardUi()
        if (openWizardPanel) {
            showMissionWizardPanel()
        }
    }

    private fun finishMissionWizard() {
        clearPendingWizardAutoAdvance()
        clearWizardUndoWindow()
        RuntimeToolkitTelemetry.clearWizardReadyWindow(this, reason = "wizard_finish")
        val state = RuntimeToolkitTelemetry.missionSessionState(this)
        val result = RuntimeToolkitTelemetry.evaluateMissionStepSaturation(
            context = this,
            stepId = RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT,
        )
        RuntimeToolkitTelemetry.setMissionWizardStepState(
            context = this,
            stepId = RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT,
            saturationState = result.state,
        )
        val finished = RuntimeToolkitTelemetry.finishMissionSession(this, result.state)
        RuntimeToolkitTelemetry.logWizardEvent(
            context = this,
            operation = "wizard_finished",
            missionId = finished.missionId,
            wizardStepId = RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT,
            saturationState = result.state,
            phaseId = RuntimeToolkitTelemetry.activePhaseId(this),
            targetSiteId = finished.targetSiteId,
            payload = result.metrics + mapOf("reason" to result.reason),
        )
        val summaryFile = RuntimeToolkitTelemetry.writeMissionExportSummary(this)
        val summary = RuntimeToolkitTelemetry.buildMissionExportSummary(this, finished.missionId)
        RuntimeToolkitTelemetry.logMissionEvent(
            context = this,
            operation = "export_requested",
            missionId = finished.missionId,
            wizardStepId = RuntimeToolkitMissionWizard.STEP_FINAL_VALIDATION_EXPORT,
            saturationState = result.state,
            exportReadiness = summary.exportReadiness,
            reason = summary.reason,
            payload = mapOf(
                "summary_path" to summaryFile.absolutePath,
                "missing_required_steps" to summary.missingRequiredSteps,
                "missing_required_artifacts" to summary.missingRequiredArtifacts,
            ),
        )
        updateMissionWizardUi()
        if (result.state == RuntimeToolkitMissionWizard.SATURATION_SATURATED) {
            EBToast.showShort(this, "Wizard finished: SATURATED")
        } else {
            EBToast.showShort(this, "Wizard finish needs more evidence: ${result.reason}")
        }
        showMissionExportSummaryDialog()
    }

    private fun formatMissionExportSummaryText(summary: RuntimeToolkitTelemetry.MissionExportSummary): String {
        val lines = mutableListOf<String>()
        lines += "Mission: ${summary.missionId}"
        lines += "Target site: ${summary.targetSiteId}"
        lines += "Target URL: ${summary.targetUrl.ifBlank { "-" }}"
        lines += "Export readiness: ${summary.exportReadiness}"
        lines += "Reason: ${summary.reason}"
        lines += ""
        lines += "Missing required steps:"
        if (summary.missingRequiredSteps.isEmpty()) {
            lines += "- none"
        } else {
            summary.missingRequiredSteps.forEach { lines += "- $it" }
        }
        lines += ""
        lines += "Missing required artifacts:"
        if (summary.missingRequiredArtifacts.isEmpty()) {
            lines += "- none"
        } else {
            summary.missingRequiredArtifacts.forEach { lines += "- $it" }
        }
        lines += ""
        lines += "Warnings:"
        if (summary.warnings.isEmpty()) {
            lines += "- none"
        } else {
            summary.warnings.forEach { lines += "- $it" }
        }
        return lines.joinToString("\n")
    }

    private fun showMissionExportSummaryDialog() {
        val summary = RuntimeToolkitTelemetry.buildMissionExportSummary(this)
        val exportReady = summary.exportReadiness == "READY"
        if (supportFragmentManager.findFragmentByTag(MissionExportSummaryDialogFragment.TAG) != null) return
        MissionExportSummaryDialogFragment
            .newInstance(
                title = getString(R.string.mapper_mission_export_summary_title),
                body = formatMissionExportSummaryText(summary),
                exportReady = exportReady,
            )
            .show(supportFragmentManager, MissionExportSummaryDialogFragment.TAG)
    }

    override fun onMissionExportSummaryRequestExport() {
        promptRuntimeExportDestination()
    }

    private fun promptCreateAnchor() {
        val nameInput = EditText(this).apply {
            id = View.generateViewId()
            hint = "Anchor name"
        }
        val typeInput = EditText(this).apply { hint = "Anchor type (e.g. search_input)" }
        val container = RelativeLayout(this).apply {
            addView(nameInput)
            val typeParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT,
            )
            typeParams.addRule(RelativeLayout.BELOW, nameInput.id)
            typeParams.topMargin = 24
            addView(typeInput, typeParams)
            setPadding(24, 24, 24, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Create Anchor")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val created = RuntimeToolkitTelemetry.createOverlayAnchor(
                    context = this,
                    name = nameInput.text?.toString().orEmpty(),
                    anchorType = typeInput.text?.toString().orEmpty(),
                    url = ebWebView.url.orEmpty(),
                )
                EBToast.showShort(this, "Anchor created: ${created.anchorId.takeLast(8)}")
            }
            .show()
    }

    private fun promptLabelAnchor() {
        val anchors = RuntimeToolkitTelemetry.listOverlayAnchors(this)
        if (anchors.isEmpty()) {
            EBToast.showShort(this, "No anchors available")
            return
        }
        val labels = anchors.map { "${it.name} (${it.anchorType})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Label Anchor")
            .setItems(labels) { _, which ->
                val selected = anchors[which]
                val nameInput = EditText(this).apply {
                    id = View.generateViewId()
                    setText(selected.name)
                }
                val typeInput = EditText(this).apply { setText(selected.anchorType) }
                val container = RelativeLayout(this).apply {
                    addView(nameInput)
                    val typeParams = RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT,
                        RelativeLayout.LayoutParams.WRAP_CONTENT,
                    )
                    typeParams.addRule(RelativeLayout.BELOW, nameInput.id)
                    typeParams.topMargin = 24
                    addView(typeInput, typeParams)
                    setPadding(24, 24, 24, 24)
                }
                AlertDialog.Builder(this)
                    .setTitle("Update Anchor")
                    .setView(container)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save") { _, _ ->
                        RuntimeToolkitTelemetry.labelOverlayAnchor(
                            context = this,
                            anchorId = selected.anchorId,
                            name = nameInput.text?.toString().orEmpty(),
                            anchorType = typeInput.text?.toString().orEmpty(),
                        )
                        EBToast.showShort(this, "Anchor updated")
                    }
                    .show()
            }
            .show()
    }

    private fun promptRemoveAnchor() {
        val anchors = RuntimeToolkitTelemetry.listOverlayAnchors(this)
        if (anchors.isEmpty()) {
            EBToast.showShort(this, "No anchors available")
            return
        }
        val labels = anchors.map { "${it.name} (${it.anchorType})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Remove Anchor")
            .setItems(labels) { _, which ->
                val removed = RuntimeToolkitTelemetry.removeOverlayAnchor(this, anchors[which].anchorId)
                EBToast.showShort(this, if (removed) "Anchor removed" else "Anchor remove failed")
            }
            .show()
    }

    private fun isMapperDebuggableBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val gestureHandler: GestureHandler by lazy { GestureHandler(this) }

    override fun goForward() {
        if (ebWebView.canGoForward()) {
            ebWebView.goForward()
        } else {
            EBToast.show(this, R.string.toast_webview_forward)
        }
    }

    override fun gotoLeftTab() {
        nextAlbumController(false)?.let { showAlbum(it) }
    }

    override fun gotoRightTab() {
        nextAlbumController(true)?.let { showAlbum(it) }
    }

    private val bookmarkViewModel: BookmarkViewModel by viewModels {
        BookmarkViewModelFactory(bookmarkManager)
    }

    private val actionModeMenuViewModel: ActionModeMenuViewModel by viewModels()

    private fun initOverview() {
        overviewDialogController = OverviewDialogController(
            this,
            albumViewModel.albums,
            albumViewModel.focusIndex,
            binding.layoutOverview,
            gotoUrlAction = { url -> updateAlbum(url) },
            addTabAction = { title, url, isForeground -> addAlbum(title, url, isForeground) },
            addIncognitoTabAction = {
                hideOverview()
                addAlbum(getString(R.string.app_name), "", incognito = true)
                focusOnInput()
            },
            splitScreenAction = { url -> toggleSplitScreen(url) },
            addEmptyTabAction = { newATab() }
        )
    }

    override fun openHistoryPage(amount: Int) = overviewDialogController.openHistoryPage(amount)

    override fun openBookmarkPage() =
        BookmarksDialogFragment(
            lifecycleScope,
            bookmarkViewModel,
            gotoUrlAction = { url -> updateAlbum(url) },
            bookmarkIconClickAction = { title, url, isForeground ->
                addAlbum(
                    title,
                    url,
                    isForeground
                )
            },
            splitScreenAction = { url -> toggleSplitScreen(url) },
            syncBookmarksAction = this::handleBookmarkSync,
            linkBookmarksAction = this::linkBookmarkSync
        ).show(supportFragmentManager, "bookmarks dialog")

    private fun handleBookmarkSync(forceUpload: Boolean = false) {
        if (config.bookmarkSyncUrl.isNotEmpty()) backupUnit.handleBookmarkSync(forceUpload)
    }

    private fun linkBookmarkSync() {
        backupUnit.linkBookmarkSync(
            dialogManager,
            createBookmarkFileLauncher,
            openBookmarkFileLauncher
        )
    }

    private val searchPanelFocusRequester = FocusRequester()

    private fun initSearchPanel() {
        binding.mainSearchPanel.apply {
            visibility = INVISIBLE
            setContent {
                MyTheme {
                    ComposedSearchBar(
                        focusRequester = searchPanelFocusRequester,
                        onTextChanged = { (currentAlbumController as EBWebView?)?.findAllAsync(it) },
                        onCloseClick = { hideSearchPanel() },
                        onUpClick = { searchUp(it) },
                        onDownClick = { searchDown(it) },
                    )
                }
            }
        }
    }

    private fun searchUp(text: String) {
        if (text.isEmpty()) {
            EBToast.show(this, getString(R.string.toast_input_empty))
            return
        }
        ViewUnit.hideKeyboard(this)
        (currentAlbumController as EBWebView).findNext(false)
    }

    private fun searchDown(text: String) {
        if (text.isEmpty()) {
            EBToast.show(this, getString(R.string.toast_input_empty))
            return
        }
        ViewUnit.hideKeyboard(this)
        (currentAlbumController as EBWebView).findNext(true)
    }

    override fun showFastToggleDialog() {
        if (!this::ebWebView.isInitialized) return

        FastToggleDialogFragment {
            ebWebView.initPreferences()
            ebWebView.reload()
        }.show(supportFragmentManager, "fast_toggle_dialog")
    }

    override fun addNewTab(url: String) = trackedAddAlbum(url, "intent_send_or_process")

    private fun getUrlMatchedBrowser(url: String): EBWebView? {
        return browserContainer.list().firstOrNull { it.albumUrl == url } as EBWebView?
    }

    private var preloadedWebView: EBWebView? = null

    open fun createebWebView(): EBWebView = EBWebView(this, this).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    @SuppressLint("ClickableViewAccessibility")
    protected fun addAlbum(
        title: String = "",
        url: String = config.favoriteUrl,
        foreground: Boolean = true,
        incognito: Boolean = false,
        enablePreloadWebView: Boolean = true,
    ) {
        val newWebView = (preloadedWebView ?: createebWebView()).apply {
            this.albumTitle = title
            this.incognito = incognito
            setOnTouchListener(createMultiTouchTouchListener(this))
        }

        maybeCreateNewPreloadWebView(enablePreloadWebView, newWebView)

        updateTabPreview(newWebView, url)
        updateWebViewCount()

        loadUrlInWebView(foreground, newWebView, url)

        updateSavedAlbumInfo()

        if (config.adBlock) {
            adFilter.setupWebView(newWebView)
        }
    }

    private fun maybeCreateNewPreloadWebView(
        enablePreloadWebView: Boolean,
        newWebView: EBWebView,
    ) {
        preloadedWebView = null
        if (enablePreloadWebView) {
            newWebView.postDelayed({
                if (preloadedWebView == null) {
                    preloadedWebView = createebWebView()
                }
            }, 2000)
        }
    }

    private fun updateTabPreview(newWebView: EBWebView, url: String) {
        bookmarkManager.findFaviconBy(url)?.getBitmap()?.let {
            newWebView.setAlbumCover(it)
        }

        val album = newWebView.album
        if (currentAlbumController != null) {
            val index = browserContainer.indexOf(currentAlbumController) + 1
            browserContainer.add(newWebView, index)
            albumViewModel.addAlbum(album, index)
        } else {
            browserContainer.add(newWebView)
            albumViewModel.addAlbum(album, browserContainer.size() - 1)
        }
    }

    private fun loadUrlInWebView(foreground: Boolean, webView: EBWebView, url: String) {
        if (!foreground) {
            webView.deactivate()
            if (config.enableWebBkgndLoad) {
                webView.loadUrl(url)
            } else {
                webView.initAlbumUrl = url
            }
        } else {
            showAlbum(webView)
            if (url.isNotEmpty() && url != BrowserUnit.URL_ABOUT_BLANK) {
                webView.loadUrl(url)
            } else if (url == BrowserUnit.URL_ABOUT_BLANK) {
            }
        }
    }

    private fun createMultiTouchTouchListener(ebWebView: EBWebView): MultitouchListener =
        object : MultitouchListener(this@BrowserActivity, ebWebView) {
            private var longPressStartPoint: Point? = null
            override fun onSwipeTop() = gestureHandler.handle(config.multitouchUp)
            override fun onSwipeBottom() = gestureHandler.handle(config.multitouchDown)
            override fun onSwipeRight() = gestureHandler.handle(config.multitouchRight)
            override fun onSwipeLeft() = gestureHandler.handle(config.multitouchLeft)
            override fun onLongPressMove(motionEvent: MotionEvent) {
                super.onLongPressMove(motionEvent)

                // Handle context menu hover selection
                if (config.enableDragUrlToAction && isInLongPressMode && activeContextMenuDialog != null) {
                    activeContextMenuDialog?.updateHoveredItem(motionEvent.rawX, motionEvent.rawY)
                    return
                }

                if (longPressStartPoint == null) {
                    longPressStartPoint = Point(motionEvent.x.toInt(), motionEvent.y.toInt())
                    return
                }
                if (abs(motionEvent.x - (longPressStartPoint?.x ?: 0)) > ViewUnit.dpToPixel(15) ||
                    abs(motionEvent.y - (longPressStartPoint?.y ?: 0)) > ViewUnit.dpToPixel(15)
                ) {
                    actionModeView?.visibility = INVISIBLE
                    longPressStartPoint = null
                    //Log.d("touch", "onLongPress: hide")
                }
            }

            override fun onMoveDone(motionEvent: MotionEvent) {
                // Handle finger lift for context menu
                if (isInLongPressMode && activeContextMenuDialog != null) {
                    activeContextMenuDialog?.onFingerLifted()
                    activeContextMenuDialog = null
                    isInLongPressMode = false
                    return
                }
                //Log.d("touch", "onMoveDone")
            }
        }.apply { lifecycle.addObserver(this) }

    private fun updateSavedAlbumInfo() {
        val albumControllers = browserContainer.list()
        val albumInfoList = albumControllers
            .filter { !it.isTranslatePage }
            .filter { !it.isAIPage }
            .filter { !it.albumUrl.startsWith("data") }
            .filter {
                (it.albumUrl.isNotBlank() && it.albumUrl != BrowserUnit.URL_ABOUT_BLANK) ||
                        it.initAlbumUrl.isNotBlank()
            }
            .map { controller ->
                AlbumInfo(
                    controller.albumTitle,
                    controller.albumUrl.ifBlank { controller.initAlbumUrl },
                )
            }
        config.savedAlbumInfoList = albumInfoList
        config.currentAlbumIndex = browserContainer.indexOf(currentAlbumController)
        // fix if current album is still with null url
        if (albumInfoList.isNotEmpty() && config.currentAlbumIndex >= albumInfoList.size) {
            config.currentAlbumIndex = albumInfoList.size - 1
        }
    }

    private fun updateWebViewCount() {
        val subScript = browserContainer.size()
        val superScript = browserContainer.indexOf(currentAlbumController) + 1
        val countString = ViewUnit.createCountString(superScript, subScript)
        composeToolbarViewController.updateTabCount(countString)
        fabImageViewController.updateTabCount(countString)
    }

    override fun updateAlbum(url: String?) {
        if (url == null) return
        (currentAlbumController as EBWebView).loadUrl(url)
        updateTitle()

        updateSavedAlbumInfo()
    }

    private fun closeTabConfirmation(okAction: () -> Unit) {
        if (!config.confirmTabClose) {
            okAction()
        } else {
            dialogManager.showOkCancelDialog(
                messageResId = R.string.toast_close_tab,
                okAction = okAction,
            )
        }
    }

    override fun removeAlbum(albumController: AlbumController, showHome: Boolean) {
        closeTabConfirmation {
            if (config.isSaveHistoryWhenClose()) {
                addHistory(albumController.albumTitle, albumController.albumUrl)
            }

            albumViewModel.removeAlbum(albumController.album)
            val removeIndex = browserContainer.indexOf(albumController)
            val currentIndex = browserContainer.indexOf(currentAlbumController)
            browserContainer.remove(albumController)

            updateSavedAlbumInfo()
            updateWebViewCount()

            if (browserContainer.isEmpty()) {
                if (!showHome) {
                    finish()
                } else {
                    ebWebView.loadUrl(config.favoriteUrl)
                }
            } else {
                // only refresh album when the delete one is current one
                if (removeIndex == currentIndex) {
                    showAlbum(browserContainer[getNextAlbumIndexAfterRemoval(removeIndex)])
                }
            }
        }
    }

    private fun getNextAlbumIndexAfterRemoval(removeIndex: Int): Int =
        if (config.shouldShowNextAfterRemoveTab) min(browserContainer.size() - 1, removeIndex)
        else max(0, removeIndex - 1)

    private fun updateTitle() {
        if (!this::ebWebView.isInitialized) return

        if (this::ebWebView.isInitialized && ebWebView === currentAlbumController) {
            composeToolbarViewController.updateTitle(ebWebView.title.orEmpty())
        }
    }

    private fun scrollChange() {
        ebWebView.setScrollChangeListener(object : EBWebView.OnScrollChangeListener {
            override fun onScrollChange(scrollY: Int, oldScrollY: Int) {
                ebWebView.updatePageInfo()

                if (::twoPaneController.isInitialized) {
                    twoPaneController.scrollChange(scrollY - oldScrollY)
                }

                if (!config.shouldHideToolbar) return

                val height =
                    floor(x = ebWebView.contentHeight * ebWebView.resources.displayMetrics.density.toDouble()).toInt()
                val webViewHeight = ebWebView.height
                val cutoff =
                    height - webViewHeight - 112 * resources.displayMetrics.density.roundToInt()
                if (scrollY in (oldScrollY + 1)..cutoff) {
                    if (binding.appBar.visibility == VISIBLE) toggleFullscreen()
                }
            }
        })

    }

    override fun updateTitle(title: String?) = updateTitle()

    override fun addHistory(title: String, url: String) {
        lifecycleScope.launch {
            recordDb.addHistory(Record(title, url, System.currentTimeMillis()))
        }
    }

    override fun updateProgress(progress: Int) {
        progressBar.progress = progress

        if (progress < BrowserUnit.PROGRESS_MAX) {
            updateRefresh(true)
            progressBar.visibility = VISIBLE
        } else { // web page loading complete
            updateRefresh(false)
            progressBar.visibility = GONE
            swipeRefreshLayout.isRefreshing = false

            scrollChange()

            updateSavedAlbumInfo()
        }
    }

    override fun focusOnInput() {
        if (binding.appBar.visibility != VISIBLE) {
            shouldRestoreFullscreen = true

            // Temporarily enable window adjustment for keyboard (only when in fullscreen)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
            } else {
                @Suppress("DEPRECATION")
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }

        composeToolbarViewController.hide()

        val textOrUrl = if (ebWebView.url?.startsWith("data:") != true) {
            val url = ebWebView.url.orEmpty()
            TextFieldValue(url, selection = TextRange(0, url.length))
        } else {
            TextFieldValue("")
        }

        inputTextOrUrl.value = textOrUrl
        inputIsWideLayout = ViewUnit.isWideLayout(this)
        inputShouldReverse = !config.isToolbarOnTop
        inputHasCopiedText = getClipboardText().isNotEmpty()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                searchSuggestionViewModel.initSuggestions()
                inputRecordList.value = searchSuggestionViewModel.suggestions.value
            }
        }

        composeToolbarViewController.hide()
        binding.appBar.visibility = INVISIBLE
        binding.contentSeparator.visibility = INVISIBLE
        binding.inputUrl.visibility = VISIBLE
        binding.inputUrl.postDelayed(
            {
                ViewUnit.showKeyboard(this)
                try {
                    inputUrlFocusRequester.requestFocus()
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                }
            }, 200
        )
    }

    private fun getClipboardText(): String =
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()

    private var isRunning = false
    private fun updateRefresh(running: Boolean) {
        if (!isRunning && running) {
            isRunning = true
        } else if (isRunning && !running) {
            isRunning = false
        }
        composeToolbarViewController.updateRefresh(isRunning)
    }

    override fun showFileChooser(filePathCallback: ValueCallback<Array<Uri>>) {
        this.filePathCallback?.onReceiveValue(null)
        this.filePathCallback = filePathCallback
        val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT)
        contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE)
        contentSelectionIntent.type = "*/*"
        val chooserIntent = Intent(Intent.ACTION_CHOOSER)
        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
        fileChooserLauncher.launch(chooserIntent)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null) {
            return
        }
        if (customView != null && callback != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        originalOrientation = requestedOrientation
        fullscreenHolder = ZoomableFrameLayout(this).apply {
            enableZoom = config.zoomInCustomView
            addView(
                customView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

        }
        ViewUnit.invertColor(view, config.hasInvertedColor(ebWebView.url.orEmpty()))

        val decorView = window.decorView as FrameLayout
        decorView.addView(
            fullscreenHolder,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        customView?.keepScreenOn = true
        (currentAlbumController as View?)?.visibility = INVISIBLE
        ViewUnit.setCustomFullscreen(
            window,
            true,
            config.hideStatusbar,
            ViewUnit.isEdgeToEdgeEnabled(resources)
        )
        if (view is FrameLayout) {
            if (view.focusedChild is VideoView) {
                videoView = view.focusedChild as VideoView
                videoView?.setOnErrorListener(VideoCompletionListener())
                videoView?.setOnCompletionListener(VideoCompletionListener())
            }
        }
        customViewCallback = callback
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    override fun onHideCustomView(): Boolean {
        if (customView == null || customViewCallback == null || currentAlbumController == null) {
            return false
        }

        // fix when pressing back, the screen is whole black.
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null

        fullscreenHolder?.visibility = GONE
        customView?.visibility = GONE
        (window.decorView as FrameLayout).removeView(fullscreenHolder)
        customView?.keepScreenOn = false
        (currentAlbumController as View).visibility = VISIBLE
        ViewUnit.setCustomFullscreen(
            window,
            false,
            config.hideStatusbar,
            ViewUnit.isEdgeToEdgeEnabled(resources)
        )
        fullscreenHolder = null
        customView = null

        if (videoView != null) {
            videoView?.visibility = GONE
            videoView?.setOnErrorListener(null)
            videoView?.setOnCompletionListener(null)
            videoView = null
        }
        requestedOrientation = originalOrientation

        return true
    }

    private var previousKeyEvent: KeyEvent? = null
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        return keyHandler.handleKeyEvent(event)
    }

    override fun loadInSecondPane(url: String): Boolean =
        if (config.twoPanelLinkHere &&
            isTwoPaneControllerInitialized() &&
            twoPaneController.isSecondPaneDisplayed()
        ) {
            toggleSplitScreen(url)
            true
        } else {
            false
        }

    private fun confirmAdSiteAddition(url: String) {
        val host = Uri.parse(url).host.orEmpty()
        if (config.adSites.contains(host)) {
            confirmRemoveAdSite(host)
        } else {
            lifecycleScope.launch {
                val domain = TextInputDialog(
                    this@BrowserActivity,
                    "Ad domain to be blocked",
                    "",
                    host,
                ).show().orEmpty()

                if (domain.isNotBlank()) {
                    config.adSites = config.adSites.apply { add(domain) }
                    ebWebView.reload()
                }
            }
        }
    }

    private fun confirmRemoveAdSite(url: String) {
        dialogManager.showOkCancelDialog(
            title = "remove this url from blacklist?",
            okAction = {
                config.adSites = config.adSites.apply { remove(url) }
                ebWebView.reload()
            }
        )
    }

    private var motionEvent: MotionEvent? = null
    private var longPressPoint: Point = Point(0, 0)
    private var activeContextMenuDialog: ContextMenuDialogFragment? = null
    private var isInLongPressMode = false
    override fun onLongPress(message: Message, event: MotionEvent?) {
        if (ebWebView.isSelectingText) return

        motionEvent = event
        longPressPoint = Point(event?.x?.toInt() ?: 0, event?.y?.toInt() ?: 0)
        val rawPoint = event?.toRawPoint() ?: Point(0, 0)

        val url = BrowserUnit.getWebViewLinkUrl(ebWebView, message)
        if (url.isNotBlank()) {
            // case: image or link
            val linkImageUrl = BrowserUnit.getWebViewLinkImageUrl(ebWebView, message)
            BrowserUnit.getWebViewLinkTitle(ebWebView) { linkTitle ->
                val titleText = linkTitle.ifBlank { url }.toString()
                val contextMenuDialog = ContextMenuDialogFragment(
                    url,
                    linkImageUrl.isNotBlank(),
                    config.imageApiKey.isNotBlank(),
                    rawPoint,
                    itemClicked = {
                        this@BrowserActivity.handleContextMenuItem(it, titleText, url, linkImageUrl)
                        activeContextMenuDialog = null
                        isInLongPressMode = false
                    },
                    itemLongClicked = {
                        if (it == ContextMenuItemType.TranslateImage) {
                            translateAllImages(linkImageUrl)
                        }
                        activeContextMenuDialog = null
                        isInLongPressMode = false
                    }
                )
                activeContextMenuDialog = contextMenuDialog
                isInLongPressMode = true
                contextMenuDialog.show(supportFragmentManager, "contextMenu")
            }
        }
    }

    private fun handleContextMenuItem(
        contextMenuItemType: ContextMenuItemType,
        title: String,
        url: String,
        imageUrl: String,
    ) {
        when (contextMenuItemType) {
            ContextMenuItemType.NewTabForeground -> addAlbum(title, url)
            ContextMenuItemType.NewTabBackground -> addAlbum(title, url, false)
            ContextMenuItemType.ShareLink -> {
                if (prepareRecord()) EBToast.show(this, getString(R.string.toast_share_failed))
                else IntentUnit.share(this, title, url)
            }

            ContextMenuItemType.CopyLink -> ShareUtil.copyToClipboard(
                this,
                BrowserUnit.stripUrlQuery(url)
            )

            ContextMenuItemType.SelectText -> ebWebView.post {
                ebWebView.selectLinkText(longPressPoint)
            }

            ContextMenuItemType.OpenWith -> HelperUnit.showBrowserChooser(
                this,
                url,
                getString(R.string.menu_open_with)
            )

            ContextMenuItemType.SaveBookmark -> saveBookmark(url, title)
            ContextMenuItemType.SplitScreen -> toggleSplitScreen(url)
            ContextMenuItemType.AdBlock -> confirmAdSiteAddition(imageUrl)

            ContextMenuItemType.TranslateImage -> translateImage(imageUrl)
            ContextMenuItemType.Tts -> addContentToReadList(url)
            ContextMenuItemType.Summarize -> summarizeLinkContent(url)
            ContextMenuItemType.SaveAs -> {
                if (url.startsWith("data:image")) {
                    saveFile(url)
                } else {
                    if (imageUrl.isNotBlank()) {
                        dialogManager.showSaveFileDialog(url = imageUrl, saveFile = this::saveFile)
                    } else {
                        dialogManager.showSaveFileDialog(url = url, saveFile = this::saveFile)
                    }
                }
            }

            else -> Unit
        }
    }

    private val toBeReadWebView: EBWebView by lazy {
        EBWebView(this, this).apply {
            setOnPageFinishedAction {
                lifecycleScope.launch {
                    val content = toBeReadWebView.getRawText()
                    if (content.isNotEmpty()) {
                        ttsViewModel.readArticle(content)
                    }
                    // remove self
                    if (toBeReadProcessUrlList.isNotEmpty()) {
                        toBeReadProcessUrlList.removeAt(0)
                    }

                    if (toBeReadProcessUrlList.isNotEmpty()) {
                        toBeReadWebView.loadUrl(toBeReadProcessUrlList.removeAt(0))
                    } else {
                        toBeReadWebView.loadUrl("about:blank")
                    }
                }
            }
        }
    }

    private var toBeReadProcessUrlList: MutableList<String> = mutableListOf()
    private fun addContentToReadList(url: String) {
        toBeReadProcessUrlList.add(url)
        if (toBeReadProcessUrlList.size == 1) {
            toBeReadWebView.loadUrl(url)
        }
        EBToast.show(this, R.string.added_to_read_list)
    }

    private fun translateWebView() {
        lifecycleScope.launch {
            val base64String = translationViewModel.translateWebView(
                ebWebView,
                config.sourceLanguage,
                config.translationLanguage,
            )
            if (base64String != null) {
                val translatedImageHtml = HelperUnit.loadAssetFileToString(
                    this@BrowserActivity, "translated_image.html"
                ).replace("%%", base64String)
                if (config.showTranslatedImageToSecondPanel) {
                    maybeInitTwoPaneController()
                    twoPaneController.showSecondPaneWithData(translatedImageHtml)
                } else {
                    addAlbum()
                    ebWebView.isTranslatePage = true
                    ebWebView.loadData(translatedImageHtml, "text/html", "utf-8")
                }
            } else {
                EBToast.show(this@BrowserActivity, "Failed to translate image")
            }
        }
    }

    private fun translateImage(url: String) {
        lifecycleScope.launch {
            translateImageSuspend(url)
        }
    }

    private suspend fun translateImageSuspend(url: String): Boolean {
        val result = translationViewModel.translateImage(
            ebWebView.url.orEmpty(),
            url,
            TranslationLanguage.KO,
            config.translationLanguage,
        )
        if (result != null) {
            val escapedUrl = url.replace("\\", "\\\\").replace("'", "\\'")
            val js = HelperUnit.loadAssetFileToString(
                this@BrowserActivity, "translate_image_overlay.js"
            ).replace("%%IMAGE_URL%%", escapedUrl)
                .replace("%%BASE64_DATA%%", result.renderedImage)
            ebWebView.evaluateJavascript(js, null)
            return result.imageId == "cached"
        }
        return false
    }

    private fun translateAllImages(imageUrl: String) {
        lifecycleScope.launch {
            val escapedUrl = imageUrl.replace("\\", "\\\\").replace("'", "\\'")
            val js = HelperUnit.loadAssetFileToString(
                this@BrowserActivity, "get_remaining_images.js"
            ).replace("%%IMAGE_URL%%", escapedUrl)

            ebWebView.evaluateJavascript(js) { result ->
                val urlsJson = result?.trim('"')?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\") ?: return@evaluateJavascript
                try {
                    val urls = org.json.JSONArray(urlsJson)
                    val imageUrls = mutableListOf<String>()
                    for (i in 0 until urls.length()) {
                        imageUrls.add(urls.getString(i))
                    }
                    if (imageUrls.isEmpty()) return@evaluateJavascript

                    EBToast.show(
                        this@BrowserActivity,
                        "Translating ${imageUrls.size} images..."
                    )
                    lifecycleScope.launch {
                        for ((index, url) in imageUrls.withIndex()) {
                            val wasCached = translateImageSuspend(url)
                            if (index < imageUrls.size - 1 && !wasCached) {
                                val base = config.imageTranslateIntervalSeconds
                                val delayMs = ((base - 2).coerceAtLeast(1)..(base + 2)).random() * 1000L
                                kotlinx.coroutines.delay(delayMs)
                            }
                        }
                    }
                } catch (e: Exception) {
                    EBToast.show(this@BrowserActivity, "Failed to get image list")
                }
            }
        }
    }

    private fun saveFile(url: String, fileName: String = "") {
        // handle data url case
        if (url.startsWith("data:image")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                BrowserUnit.saveImageFromUrl(url, saveImageFilePickerLauncher)
            } else {
                EBToast.show(this, "Not supported dataUrl")
            }
            return
        }

        if (HelperUnit.needGrantStoragePermission(this)) {
            return
        }

        val source = Uri.parse(url)
        val request = Request(source).apply {
            addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
            setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            try {
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            } catch (e: Exception) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                setDestinationUri(Uri.fromFile(java.io.File(downloadsDir, fileName)))
            }
        }

        val dm = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager)
        dm.enqueue(request)
        ViewUnit.hideKeyboard(this)
    }

    @SuppressLint("RestrictedApi")
    private fun showToolbar() {
        if (searchOnSite) return

        showStatusBar()
        fabImageViewController.hide()
        binding.mainSearchPanel.visibility = INVISIBLE
        binding.appBar.visibility = VISIBLE
        binding.contentSeparator.visibility = VISIBLE
        binding.inputUrl.visibility = INVISIBLE
        composeToolbarViewController.show()
        ViewUnit.hideKeyboard(this)
    }

    override fun toggleFullscreen() {
        if (searchOnSite) return

        if (binding.appBar.visibility == VISIBLE) {
            if (config.fabPosition != FabPosition.NotShow) {
                fabImageViewController.show()
            }
            binding.mainSearchPanel.visibility = INVISIBLE
            binding.appBar.visibility = GONE
            binding.contentSeparator.visibility = GONE
            hideStatusBar()
        } else {
            showToolbar()
        }
    }

    private fun hideSearchPanel() {
        if (this::ebWebView.isInitialized) {
            ebWebView.clearMatches()
        }
        searchOnSite = false
        ViewUnit.hideKeyboard(this)
        showToolbar()
    }

    @Suppress("DEPRECATION")
    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(WindowInsets.Type.statusBars())
            if (ViewUnit.isEdgeToEdgeEnabled(resources))
                window.insetsController?.hide(WindowInsets.Type.navigationBars())
            binding.root.setPadding(0, 0, 0, 0)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }


    @Suppress("DEPRECATION")
    private fun showStatusBar() {
        if (config.hideStatusbar) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.statusBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    override fun showSearchPanel() {
        searchOnSite = true
        fabImageViewController.hide()
        binding.mainSearchPanel.visibility = VISIBLE
        binding.mainSearchPanel.post {
            searchPanelFocusRequester.requestFocus()
            ViewUnit.showKeyboard(this)
        }
        binding.appBar.visibility = VISIBLE
        binding.contentSeparator.visibility = VISIBLE
        ViewUnit.showKeyboard(this)
    }

    override fun showSaveEpubDialog() = dialogManager.showSaveEpubDialog { uri ->
        if (uri == null) {
            epubManager.showWriteEpubFilePicker(writeEpubFilePickerLauncher, ebWebView.title ?: "einkbro")
        } else {
            saveEpub(uri)
        }
    }

    protected fun readArticle() {
        lifecycleScope.launch {
            ttsViewModel.readArticle(ebWebView.getRawText())
        }
    }

    private val menuActionHandler: MenuActionHandler by lazy { MenuActionHandler(this) }

    override fun showMenuDialog() =
        MenuDialogFragment(
            ebWebView.url.orEmpty(),
            ttsViewModel.isReading(),
            { menuActionHandler.handle(it, ebWebView) },
            { menuActionHandler.handleLongClick(it) }
        ).show(supportFragmentManager, "menu_dialog")

    override fun showWebArchiveFilePicker() {
        val fileName = "${ebWebView.title}.mht"
        BrowserUnit.createFilePicker(createWebArchivePickerLauncher, fileName)
    }

    override fun showOpenEpubFilePicker() =
        epubManager.showOpenEpubFilePicker(openEpubFilePickerLauncher)

    override fun handleTtsButton() {
        if (ttsViewModel.isReading()) {
            TtsSettingDialogFragment().show(supportFragmentManager, "TtsSettingDialog")
        } else {
            readArticle()
        }
    }

    override fun showTtsLanguageDialog() {
        TtsLanguageDialog(this).show(ttsViewModel.getAvailableLanguages())
    }

    override fun removeAlbum() {
        currentAlbumController?.let { removeAlbum(it) }
    }

    override fun toggleSplitScreen(url: String?) {
        maybeInitTwoPaneController()
        if (twoPaneController.isSecondPaneDisplayed() && url == null) {
            twoPaneController.hideSecondPane()
            splitSearchViewModel.reset()
            return
        }

        twoPaneController.showSecondPaneWithUrl(url ?: ebWebView.url.orEmpty())
    }

    private fun nextAlbumController(next: Boolean): AlbumController? {
        if (browserContainer.size() <= 1) {
            return currentAlbumController
        }

        val list = browserContainer.list()
        var index = list.indexOf(currentAlbumController)
        if (next) {
            index++
            if (index >= list.size) {
                return list.first()
            }
        } else {
            index--
            if (index < 0) {
                return list.last()
            }
        }
        return list[index]
    }

    private fun getFocusedWebView(): EBWebView = when {
        ebWebView.hasFocus() -> ebWebView
        isTwoPaneControllerInitialized() && twoPaneController.getSecondWebView().hasFocus() -> {
            twoPaneController.getSecondWebView()
        }

        else -> ebWebView
    }

    private val json = Json {
        // Configure JSON serializer
        ignoreUnknownKeys = true
        encodeDefaults = false // Don't encode default values to reduce size
        isLenient = true
    }

    // - action mode handling
    override fun onActionModeStarted(mode: ActionMode) {
        val isTextEditMode = ViewUnit.isTextEditMode(this, mode.menu)

        // check isSendingLink
        if (remoteConnViewModel.isSendingTextSearch && !isTextEditMode) {
            mode.hide(1000000)
            mode.menu.clear()
            mode.finish()

            lifecycleScope.launch {
                val keyword = getFocusedWebView().getSelectedText()
                val keywordWithContext = getFocusedWebView().getSelectedTextWithContext()
                val action = config.gptActionList.firstOrNull { it.name == config.remoteQueryActionName }
                val constructedUrlString =
                    if (action != null) {
                        val actionString = json.encodeToString(serializer = ChatGPTActionInfo.serializer(), action)
                        "action|||$keywordWithContext|||$actionString"
                    } else {
                        externalSearchViewModel.generateSearchUrl(keyword)
                    }
                remoteConnViewModel.sendTextSearch(constructedUrlString)
            }
            return
        }

        if (!config.showDefaultActionMenu && !isTextEditMode && isInSplitSearchMode()) {
            mode.hide(1000000)
            mode.menu.clear()

            lifecycleScope.launch {
                toggleSplitScreen(splitSearchViewModel.getUrl(ebWebView.getSelectedText()))
            }

            mode.finish()
            return
        }

        if (!actionModeMenuViewModel.isInActionMode()) {
            actionModeMenuViewModel.updateActionMode(mode)

            if (!config.showDefaultActionMenu && !isTextEditMode) {
                mode.hide(1000000)
                mode.menu.clear()
                mode.finish()

                lifecycleScope.launch {
                    actionModeMenuViewModel.updateSelectedText(HelperUnit.unescapeJava(getFocusedWebView().getSelectedText()))
                    showActionModeView(translationViewModel) {
                        getFocusedWebView().removeTextSelection()
                    }
                }
            }
        }

        if (!config.showDefaultActionMenu && !isTextEditMode) {
            mode.menu.clear()
        }

        super.onActionModeStarted(mode)
    }

    private var actionModeView: View? = null
    private fun showActionModeView(
        translationViewModel: TranslationViewModel,
        clearSelectionAction: () -> Unit,
    ) {
        actionModeMenuViewModel.updateMenuInfos(this, translationViewModel)
        if (actionModeView == null) {
            actionModeView = ComposeView(this).apply {
                setContent {
                    val text by actionModeMenuViewModel.selectedText.collectAsState()
                    MyTheme {
                        ActionModeMenu(
                            actionModeMenuViewModel.menuInfos,
                            actionModeMenuViewModel.showIcons,
                        ) { intent ->
                            if (intent != null) {
                                context.startActivity(intent.apply {
                                    putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                                    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                                })
                            }
                            clearSelectionAction()
                            actionModeMenuViewModel.updateActionMode(null)
                        }
                    }
                }
            }
            actionModeView?.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            actionModeView?.visibility = INVISIBLE
            binding.root.addView(actionModeView)
        }

        actionModeMenuViewModel.show()
    }

    override fun onPause() {
        super.onPause()
        actionModeMenuViewModel.finish()
        if (!config.continueMedia && !isMeetPipCriteria()) {
            if (this::ebWebView.isInitialized) {
                ebWebView.pauseTimers()
            }
        }
    }

    override fun onActionModeFinished(mode: ActionMode?) {
        super.onActionModeFinished(mode)
        mode?.hide(1000000)
        actionModeMenuViewModel.updateActionMode(null)
    }

    // - action mode handling

    companion object {
        private const val K_SHOULD_LOAD_TAB_STATE = "k_should_load_tab_state"
        const val ACTION_READ_ALOUD = "action_read_aloud"
    }
}
