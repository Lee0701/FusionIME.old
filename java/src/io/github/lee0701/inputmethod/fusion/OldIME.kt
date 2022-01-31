package io.github.lee0701.inputmethod.fusion

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.res.Configuration
import android.media.AudioManager
import android.os.*
import android.preference.PreferenceManager
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.UnderlineSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.android.inputmethod.latin.LatinIME
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Optional
import com.google.common.base.Preconditions
import com.google.protobuf.ByteString
import org.mozc.android.inputmethod.japanese.*
import org.mozc.android.inputmethod.japanese.FeedbackManager.FeedbackEvent
import org.mozc.android.inputmethod.japanese.FeedbackManager.FeedbackListener
import org.mozc.android.inputmethod.japanese.KeycodeConverter.KeyEventInterface
import org.mozc.android.inputmethod.japanese.ViewManagerInterface.LayoutAdjustment
import org.mozc.android.inputmethod.japanese.emoji.EmojiProviderType
import org.mozc.android.inputmethod.japanese.emoji.EmojiUtil
import org.mozc.android.inputmethod.japanese.hardwarekeyboard.HardwareKeyboard.CompositionSwitchMode
import org.mozc.android.inputmethod.japanese.keyboard.Keyboard.KeyboardSpecification
import org.mozc.android.inputmethod.japanese.model.SelectionTracker
import org.mozc.android.inputmethod.japanese.model.SymbolCandidateStorage.SymbolHistoryStorage
import org.mozc.android.inputmethod.japanese.model.SymbolMajorCategory
import org.mozc.android.inputmethod.japanese.mushroom.MushroomResultProxy
import org.mozc.android.inputmethod.japanese.preference.ClientSidePreference
import org.mozc.android.inputmethod.japanese.preference.PreferenceUtil
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidates
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.*
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context.InputFieldType
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input.TouchEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand.UsageStatsEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoConfig
import org.mozc.android.inputmethod.japanese.protobuf.ProtoConfig.Config.SelectionShortcut
import org.mozc.android.inputmethod.japanese.protobuf.ProtoConfig.Config.SessionKeymap
import org.mozc.android.inputmethod.japanese.session.SessionExecutor
import org.mozc.android.inputmethod.japanese.session.SessionExecutor.EvaluationCallback
import org.mozc.android.inputmethod.japanese.session.SessionExecutor.getInstanceInitializedIfNecessary
import org.mozc.android.inputmethod.japanese.session.SessionHandlerFactory
import org.mozc.android.inputmethod.japanese.util.ImeSwitcherFactory
import org.mozc.android.inputmethod.japanese.util.LauncherIconManagerFactory
import java.util.*
import kotlin.collections.ArrayList

class OldIME: LatinIME() {

    private var mode: ImeMode = ImeMode.LATIN

    private lateinit var viewManager: ViewManagerInterface
    lateinit var feedbackManager: FeedbackManager
    private lateinit var sessionExecutor: SessionExecutor
    private lateinit var symbolHistoryStorage: SymbolHistoryStorage
    private val selectionTracker = SelectionTracker()
    private var applicationCompatibility = ApplicationCompatibility.getDefaultInstance()

    // A receiver to accept a notification via intents.
    val configurationChangedHandler: Handler = Handler(Looper.getMainLooper(), ConfigurationChangeCallback())

    // Handler to process SYNC_DATA command for storing history data.
    var sendSyncDataCommandHandler: Handler = SendSyncDataCommandHandler()

    // Handler to process SYNC_DATA command for storing history data.
    private val memoryTrimmingHandler: Handler = MemoryTrimmingHandler()


    // A handler for onSharedPreferenceChanged().
    // Note: the handler is needed to be held by the service not to be GC'ed.
    val sharedPreferenceChangeListener: OnSharedPreferenceChangeListener =
        SharedPreferenceChangeAdapter()


    private lateinit var sharedPreferences: SharedPreferences
    private var propagatedClientSidePreference: ClientSidePreference? = null

    private val renderResultCallback = RenderResultCallback()
    private val sendKeyToApplicationCallback = SendKeyToApplicationCallback()

    private val isDebugBuild: Boolean by lazy { MozcUtil.isDebug(this) }
    private var inputBound: Boolean = false

    private var currentKeyboardSpecification = KeyboardSpecification.TWELVE_KEY_TOGGLE_KANA
    private var originalWindowAnimationResourceId = Optional.absent<Int>()

    override fun onCreate() {
        super.onCreate()

        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        this.sessionExecutor = getInstanceInitializedIfNecessary(SessionHandlerFactory(Optional.of(sharedPreferences)), this)
        this.symbolHistoryStorage = SymbolHistoryStorageImpl(sessionExecutor)

        val eventListener = MozcEventListener()
        prepareOnce(eventListener, symbolHistoryStorage, sharedPreferences)
        prepareEveryTime(sharedPreferences, resources.configuration)

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        setMode(imm.currentInputMethodSubtype)
    }

    override fun onDestroy() {
        feedbackManager.release()
        sessionExecutor.syncData()

        // Following listeners/handlers have reference to the service.
        // To free the service instance, remove the listeners/handlers.

        // Following listeners/handlers have reference to the service.
        // To free the service instance, remove the listeners/handlers.
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
        sendSyncDataCommandHandler.removeMessages(SendSyncDataCommandHandlerCompanion.WHAT)
        memoryTrimmingHandler.removeMessages(MemoryTrimmingHandlerCompanion.WHAT)

        super.onDestroy()
    }

    private fun setMode(subtype: InputMethodSubtype) {
        val locale = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) subtype.languageTag else subtype.locale
        if(locale == "ja_JP" || locale == "ja-JP") {
            mode = ImeMode.MOZC
        } else {
            mode = ImeMode.LATIN
        }
    }

    override fun onCurrentInputMethodSubtypeChanged(subtype: InputMethodSubtype?) {
        if(subtype != null) {
            setMode(subtype)
        }
        if(mode == ImeMode.LATIN) super.onCurrentInputMethodSubtypeChanged(subtype)
        setInputView(onCreateInputView())
    }

    override fun setInputView(view: View?) {
        super.setInputView(view)
        viewManager.updateGlobeButtonEnabled()
        viewManager.updateMicrophoneButtonEnabled()
    }

    override fun onCreateInputView(): View {
        val superInputView = super.onCreateInputView()
        if(mode == ImeMode.MOZC) return viewManager.createMozcView(this)
        return superInputView
    }

    /**
     * Prepares something which should be done only once.
     */
    private fun prepareOnce(
        eventListener: ViewEventListener,
        symbolHistoryStorage: SymbolHistoryStorage,
        sharedPreferences: SharedPreferences?
    ) {
        prepareOnceMozc(eventListener, symbolHistoryStorage, sharedPreferences)
    }

    private fun prepareOnceMozc(
        eventListener: ViewEventListener,
        symbolHistoryStorage: SymbolHistoryStorage,
        sharedPreferences: SharedPreferences?
    ) {
        val context = applicationContext
        val forwardIntent = ApplicationInitializerFactory.createInstance(this).initialize(
            MozcUtil.isSystemApplication(context),
            MozcUtil.isDevChannel(context),
            DependencyFactory.getDependency(applicationContext).isWelcomeActivityPreferrable,
            MozcUtil.getAbiIndependentVersionCode(context),
            LauncherIconManagerFactory.getDefaultInstance(),
            PreferenceUtil.getDefaultPreferenceManagerStatic()
        )
        if (forwardIntent.isPresent) {
            startActivity(forwardIntent.get())
        }

        // Setup FeedbackManager.
        val vibrator =
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else getSystemService(VIBRATOR_SERVICE) as Vibrator
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        feedbackManager = FeedbackManager(RealFeedbackListener(vibrator, audioManager))

        val imeSwitcher = ImeSwitcherFactory.getImeSwitcher(this)
        viewManager = DependencyFactory.getDependency(applicationContext)
            .createViewManager(applicationContext, eventListener, symbolHistoryStorage, imeSwitcher,
                MozcMenuDialogListenerImpl(this, eventListener))

        // Set a callback for preference changing.
        sharedPreferences?.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
    }

    /**
     * Prepares something which should be done every time when the session is newly created.
     */
    private fun prepareEveryTime(sharedPreferences: SharedPreferences?, deviceConfiguration: Configuration) {
        if(mode == ImeMode.MOZC) prepareEveryTimeMozc(sharedPreferences, deviceConfiguration)
    }

    private fun prepareEveryTimeMozc(sharedPreferences: SharedPreferences?, deviceConfiguration: Configuration) {
        val isLogging = (sharedPreferences != null
                && sharedPreferences.getBoolean(
            PREF_TWEAK_LOGGING_PROTOCOL_BUFFERS,
            false
        ))
        // Force to initialize here.
        sessionExecutor.reset(
            SessionHandlerFactory(Optional.fromNullable(sharedPreferences)), this
        )
        sessionExecutor.setLogging(isLogging)
        updateImposedConfig()
        viewManager.onConfigurationChanged(resources.configuration)
        // Make sure that the server and the client have the same keyboard specification.
        // User preference's keyboard will be set after this step.
        changeKeyboardSpecificationAndSendKey(
            null, null, currentKeyboardSpecification, deviceConfiguration, emptyList()
        )
        if (sharedPreferences != null) {
            propagateClientSidePreference(
                ClientSidePreference(
                    sharedPreferences, resources, deviceConfiguration.orientation
                )
            )
            // TODO(hidehiko): here we just set the config based on preferences. When we start
            //   to support sync on Android, we need to revisit the config related design.
            sessionExecutor.config = ConfigUtil.toConfig(sharedPreferences)
            sessionExecutor.preferenceUsageStatsEvent(sharedPreferences, resources)
        }
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        if(mode == ImeMode.MOZC) {
            val attribute = editorInfo ?: return

            applicationCompatibility = ApplicationCompatibility.getInstance(attribute)

            // Update full screen mode, because the application may be changed.

            // Update full screen mode, because the application may be changed.
            viewManager.isFullscreenMode = (applicationCompatibility.isFullScreenModeSupported
                    && propagatedClientSidePreference != null && propagatedClientSidePreference!!.isFullscreenMode)

            // Some applications, e.g. gmail or maps, send onStartInput with restarting = true, when a user
            // rotates a device. In such cases, we don't want to update caret positions, nor reset
            // the context basically. However, some other applications, such as one with a webview widget
            // like a browser, send onStartInput with restarting = true, too. Unfortunately,
            // there seems no way to figure out which one causes this invocation.
            // So, as a point of compromise, we reset the context every time here. Also, we'll send
            // finishComposingText as well, in case the new attached field has already had composing text
            // (we hit such a situation on webview, too).
            // See also onConfigurationChanged for caret position handling on gmail-like applications'
            // device rotation events.

            // Some applications, e.g. gmail or maps, send onStartInput with restarting = true, when a user
            // rotates a device. In such cases, we don't want to update caret positions, nor reset
            // the context basically. However, some other applications, such as one with a webview widget
            // like a browser, send onStartInput with restarting = true, too. Unfortunately,
            // there seems no way to figure out which one causes this invocation.
            // So, as a point of compromise, we reset the context every time here. Also, we'll send
            // finishComposingText as well, in case the new attached field has already had composing text
            // (we hit such a situation on webview, too).
            // See also onConfigurationChanged for caret position handling on gmail-like applications'
            // device rotation events.
            resetContext()
            val connection = currentInputConnection
            if (connection != null) {
                connection.finishComposingText()
                maybeCommitMushroomResult(attribute, connection)
            }

            // Send the connected field's attributes to the mozc server.

            // Send the connected field's attributes to the mozc server.
            sessionExecutor.switchInputFieldType(getInputFieldType(attribute))
            sessionExecutor.updateRequest(
                EmojiUtil.createEmojiRequest(
                    Build.VERSION.SDK_INT,
                    if (propagatedClientSidePreference != null && EmojiUtil.isCarrierEmojiAllowed(
                            attribute
                        )
                    ) propagatedClientSidePreference!!.emojiProviderType else EmojiProviderType.NONE
                ), emptyList()
            )
            selectionTracker.onStartInput(
                attribute.initialSelStart, attribute.initialSelEnd, isWebEditText(attribute)
            )
        } else {
            super.onStartInput(editorInfo, restarting)
        }
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        if(mode == ImeMode.MOZC) {
            val attribute = editorInfo ?: return
            viewManager.onStartInputView(attribute)
            viewManager.setTextForActionButton(getTextForImeAction(attribute.imeOptions))
            viewManager.setEditorInfo(attribute)
            // updateXxxxxButtonEnabled cannot be placed in onStartInput because
            // the view might be created after onStartInput with *reset* status.
            // updateXxxxxButtonEnabled cannot be placed in onStartInput because
            // the view might be created after onStartInput with *reset* status.
            viewManager.updateGlobeButtonEnabled()
            viewManager.updateMicrophoneButtonEnabled()

            // Should reset the window animation since the order of onStartInputView() / onFinishInput() is
            // not stable.
            resetWindowAnimation()
            // Mode indicator is available and narrow frame is NOT available on Lollipop or later.
            // In this case, we temporary disable window animation to show the mode indicator correctly.
            if (Build.VERSION.SDK_INT >= 21 && viewManager.isNarrowMode) {
                val window = window.window
                val animationId = window!!.attributes.windowAnimations
                if (animationId != 0) {
                    originalWindowAnimationResourceId = Optional.of(animationId)
                    window.setWindowAnimations(0)
                }
            }
        } else {
            super.onStartInputView(editorInfo, restarting)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        // Omit rendering because the input view will soon disappear.
        resetContext()
        selectionTracker.onFinishInput()
        applicationCompatibility = ApplicationCompatibility.getDefaultInstance()
        resetWindowAnimation()

        super.onFinishInput()
    }

    override fun onConfigurationChanged(conf: Configuration) {
        super.onConfigurationChanged(conf)

        val inputConnection = currentInputConnection
        if (inputConnection != null) {
            if (inputBound) {
                inputConnection.finishComposingText()
            }
            val selectionStart = selectionTracker.lastSelectionStart
            val selectionEnd = selectionTracker.lastSelectionEnd
            if (selectionStart >= 0 && selectionEnd >= 0) {
                // We need to keep the last caret position, but it will be soon overwritten in
                // onStartInput. Theoretically, we should prohibit the overwriting, but unfortunately
                // there is no good way to figure out whether the invocation of onStartInput is caused by
                // configuration change, or not. Thus, instead, we'll make an event to invoke
                // onUpdateSelectionInternal with an expected position after the onStartInput invocation,
                // so that it will again overwrite the caret position.
                // Note that, if a user rotates the device with holding preedit text, it will be committed
                // by finishComposingText above, and onUpdateSelection will be invoked from the framework.
                // Invoke onUpdateSelectionInternal twice with same arguments should be safe in this
                // situation.
                configurationChangedHandler.sendMessage(
                    configurationChangedHandler.obtainMessage(0, selectionStart, selectionEnd)
                )
            }
        }
        resetContext()
        selectionTracker.onConfigurationChanged()

        sessionExecutor.updateRequest(
            MozcUtil.getRequestBuilder(resources, currentKeyboardSpecification, conf).build(),
            emptyList()
        )

        // NOTE : This method is not called at the time when the service is started.
        // Based on newConfig, client side preferences should be sent
        // because they change based on device config.
        propagateClientSidePreference(
            ClientSidePreference(
                Preconditions.checkNotNull(PreferenceManager.getDefaultSharedPreferences(this)),
                resources, conf.orientation
            )
        )
        viewManager.onConfigurationChanged(conf)
    }

    override fun onBindInput() {
        super.onBindInput()
        inputBound = true
    }

    override fun onUnbindInput() {
        inputBound = false
        super.onUnbindInput()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        showStatusIcon()
        // Remove memory trimming message.
        // Remove memory trimming message.
        memoryTrimmingHandler.removeMessages(MemoryTrimmingHandlerCompanion.WHAT)
        // Ensure keyboard's request.
        // The session might be deleted by trimMemory caused by onWindowHidden.
        // Note that this logic must be placed *after* removing the messages in memoryTrimmingHandler.
        // Otherwise the session might be unexpectedly deleted and newly re-created one will be used
        // without appropriate request which is sent below.
        // Ensure keyboard's request.
        // The session might be deleted by trimMemory caused by onWindowHidden.
        // Note that this logic must be placed *after* removing the messages in memoryTrimmingHandler.
        // Otherwise the session might be unexpectedly deleted and newly re-created one will be used
        // without appropriate request which is sent below.
        changeKeyboardSpecificationAndSendKey(
            null, null, currentKeyboardSpecification, resources.configuration, emptyList()
        )
    }

    override fun onWindowHidden() {

        // "Hiding IME's window" is very similar to "Turning off IME" for PC.
        // Thus
        // - Committing composing text.
        // - Removing all pending messages.
        // - Resetting Mozc server
        // are needed.

        // "Hiding IME's window" is very similar to "Turning off IME" for PC.
        // Thus
        // - Committing composing text.
        // - Removing all pending messages.
        // - Resetting Mozc server
        // are needed.
        sessionExecutor.removePendingEvaluations()

        resetContext()
        selectionTracker.onWindowHidden()
        viewManager.reset()
        hideStatusIcon()
        // MemoryTrimmingHandler.DURATION_MS from now, memory trimming will be done.
        // If the window is shown before MemoryTrimmingHandler.DURATION_MS,
        // the message posted here will be removed.
        // MemoryTrimmingHandler.DURATION_MS from now, memory trimming will be done.
        // If the window is shown before MemoryTrimmingHandler.DURATION_MS,
        // the message posted here will be removed.
        memoryTrimmingHandler.removeMessages(MemoryTrimmingHandlerCompanion.WHAT)
        memoryTrimmingHandler.sendEmptyMessageDelayed(
            MemoryTrimmingHandlerCompanion.WHAT,
            MemoryTrimmingHandlerCompanion.DURATION_MS.toLong()
        )

        super.onWindowHidden()
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        super.onEvaluateFullscreenMode()
        return false
    }

    override fun onComputeInsets(outInsets: Insets?) {
        if(mode == ImeMode.MOZC) viewManager.computeInsets(applicationContext, outInsets, window.window)
        else super.onComputeInsets(outInsets)
    }

    private fun resetContext() {
        sessionExecutor.resetContext()
        viewManager.reset()
    }

    private fun resetWindowAnimation() {
        if (originalWindowAnimationResourceId.isPresent()) {
            val window = window.window
            window!!.setWindowAnimations(originalWindowAnimationResourceId.get())
            originalWindowAnimationResourceId = Optional.absent<Int>()
        }
    }

    /**
     * Propagates the preferences which affect client-side.
     *
     * If the previous parameter (this.clientSidePreference) is null,
     * all the fields in the latest parameter are propagated.
     * If not, only differences are propagated.
     *
     * After the execution, `this.propagatedClientSidePreference` is updated.
     *
     * @param newPreference the ClientSidePreference to be propagated
     */
    @VisibleForTesting
    fun propagateClientSidePreference(newPreference: ClientSidePreference?) {
        // TODO(matsuzakit): Receive a Config to reflect the current device configuration.
        if (newPreference == null) {
            MozcLog.e("newPreference must be non-null. No update is performed.")
            return
        }
        val oldPreference: ClientSidePreference? = propagatedClientSidePreference
        if (oldPreference == null
            || oldPreference.isHapticFeedbackEnabled != newPreference.isHapticFeedbackEnabled
        ) {
            feedbackManager.setHapticFeedbackEnabled(newPreference.isHapticFeedbackEnabled)
        }
        if (oldPreference == null
            || oldPreference.hapticFeedbackDuration != newPreference.hapticFeedbackDuration
        ) {
            feedbackManager.setHapticFeedbackDuration(newPreference.hapticFeedbackDuration)
        }
        if (oldPreference == null
            || oldPreference.isSoundFeedbackEnabled != newPreference.isSoundFeedbackEnabled
        ) {
            feedbackManager.setSoundFeedbackEnabled(newPreference.isSoundFeedbackEnabled)
        }
        if (oldPreference == null
            || oldPreference.soundFeedbackVolume != newPreference.soundFeedbackVolume
        ) {
            // The default value is 0.4f. In order to set the 50 to the default value, divide the
            // preference value by 125f heuristically.
            feedbackManager.setSoundFeedbackVolume(newPreference.soundFeedbackVolume / 125f)
        }
        if (oldPreference == null
            || oldPreference.isPopupFeedbackEnabled != newPreference.isPopupFeedbackEnabled
        ) {
            viewManager.isPopupEnabled = newPreference.isPopupFeedbackEnabled
        }
        if (oldPreference == null
            || oldPreference.keyboardLayout != newPreference.keyboardLayout
        ) {
            viewManager.setKeyboardLayout(newPreference.keyboardLayout)
        }
        if (oldPreference == null
            || oldPreference.inputStyle != newPreference.inputStyle
        ) {
            viewManager.setInputStyle(newPreference.inputStyle)
        }
        if (oldPreference == null
            || oldPreference.isQwertyLayoutForAlphabet != newPreference.isQwertyLayoutForAlphabet
        ) {
            viewManager.setQwertyLayoutForAlphabet(newPreference.isQwertyLayoutForAlphabet)
        }
        if (oldPreference == null
            || oldPreference.isFullscreenMode != newPreference.isFullscreenMode
        ) {
            viewManager.isFullscreenMode =
                applicationCompatibility.isFullScreenModeSupported && newPreference.isFullscreenMode
        }
        if (oldPreference == null
            || oldPreference.flickSensitivity != newPreference.flickSensitivity
        ) {
            viewManager.flickSensitivity = newPreference.flickSensitivity
        }
        if (oldPreference == null
            || oldPreference.emojiProviderType != newPreference.emojiProviderType
        ) {
            viewManager.emojiProviderType = newPreference.emojiProviderType
        }
        if (oldPreference == null
            || oldPreference.hardwareKeyMap != newPreference.hardwareKeyMap
        ) {
            viewManager.hardwareKeyMap = newPreference.hardwareKeyMap
        }
        if (oldPreference == null
            || oldPreference.skinType != newPreference.skinType
        ) {
            viewManager.skin = newPreference.skinType.getSkin(resources)
        }
        if (oldPreference == null
            || oldPreference.isMicrophoneButtonEnabled != newPreference.isMicrophoneButtonEnabled
        ) {
            viewManager.isMicrophoneButtonEnabledByPreference =
                newPreference.isMicrophoneButtonEnabled
        }
        if (oldPreference == null
            || oldPreference.layoutAdjustment != newPreference.layoutAdjustment
        ) {
            viewManager.layoutAdjustment = newPreference.layoutAdjustment
        }
        if (oldPreference == null
            || oldPreference.keyboardHeightRatio != newPreference.keyboardHeightRatio
        ) {
            viewManager.keyboardHeightRatio = newPreference.keyboardHeightRatio
        }
        propagatedClientSidePreference = newPreference
    }

    /**
     * Updates InputConnection.
     *
     * @param command Output message. Rendering is based on this parameter.
     * @param keyEvent Trigger event for this calling. When direct input is
     * needed, this event is sent to InputConnection.
     */
    @VisibleForTesting
    fun renderInputConnection(command: Command, keyEvent: KeyEventInterface?) {
        Preconditions.checkNotNull(command)
        val inputConnection = currentInputConnection ?: return
        val output = command.output
        if (!output.hasConsumed() || !output.consumed) {
            maybeCommitText(output, inputConnection)
            sendKeyEvent(keyEvent)
            return
        }

        // Meta key may invoke a command for Mozc server like SWITCH_INPUT_MODE session command. In this
        // case, the command is consumed by Mozc server and the application cannot get the key event.
        // To avoid such situation, we should send the key event back to application. b/13238551
        // The command itself is consumed by Mozc server, so we should NOT put a return statement here.
        if (keyEvent != null && keyEvent.nativeEvent.isPresent
            && KeycodeConverter.isMetaKey(keyEvent.nativeEvent.get())
        ) {
            sendKeyEvent(keyEvent)
        }

        // Here the key is consumed by the Mozc server.
        inputConnection.beginBatchEdit()
        try {
            maybeDeleteSurroundingText(output, inputConnection)
            maybeCommitText(output, inputConnection)
            setComposingText(command, inputConnection)
            maybeSetSelection(output, inputConnection)
            selectionTracker.onRender(
                if (output.hasDeletionRange()) output.deletionRange else null,
                if (output.hasResult()) output.result.value else null,
                if (output.hasPreedit()) output.preedit else null
            )
        } finally {
            inputConnection.endBatchEdit()
        }
    }

    private fun createKeyEvent(
        original: KeyEvent, eventTime: Long, action: Int, repeatCount: Int
    ): KeyEvent {
        return KeyEvent(
            original.downTime, eventTime, action, original.keyCode,
            repeatCount, original.metaState, original.deviceId, original.scanCode,
            original.flags
        )
    }

    /**
     * Sends the `KeyEvent`, which is not consumed by the mozc server.
     */
    @VisibleForTesting
    fun sendKeyEvent(keyEvent: KeyEventInterface?) {
        if (keyEvent == null) {
            return
        }
        val keyCode = keyEvent.keyCode
        // Some keys have a potential to be consumed from mozc client.
        if (maybeProcessBackKey(keyCode) || maybeProcessActionKey(keyCode)) {
            // The key event is consumed.
            return
        }

        // Following code is to fallback to target activity.
        val nativeKeyEvent = keyEvent.nativeEvent
        val inputConnection = currentInputConnection
        if (nativeKeyEvent.isPresent && inputConnection != null) {
            // Meta keys are from this.onKeyDown/Up so fallback each time.
            if (KeycodeConverter.isMetaKey(nativeKeyEvent.get())) {
                inputConnection.sendKeyEvent(
                    createKeyEvent(
                        nativeKeyEvent.get(), MozcUtil.getUptimeMillis(),
                        nativeKeyEvent.get().action, nativeKeyEvent.get().repeatCount
                    )
                )
                return
            }

            // Other keys are from this.onKeyDown so create dummy Down/Up events.
            inputConnection.sendKeyEvent(
                createKeyEvent(
                    nativeKeyEvent.get(), MozcUtil.getUptimeMillis(), KeyEvent.ACTION_DOWN, 0
                )
            )
            inputConnection.sendKeyEvent(
                createKeyEvent(
                    nativeKeyEvent.get(), MozcUtil.getUptimeMillis(), KeyEvent.ACTION_UP, 0
                )
            )
            return
        }

        // Otherwise, just delegates the key event to the connected application.
        // However space key needs special treatment because it is expected to produce space character
        // instead of sending ACTION_DOWN/UP pair.
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            inputConnection!!.commitText(" ", 0)
        } else {
            sendDownUpKeyEvents(keyCode)
        }
    }

    /**
     * @return true if the key event is consumed
     */
    private fun maybeProcessBackKey(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK || !isInputViewShown) {
            return false
        }

        // Special handling for back key event, to close the software keyboard or its subview.
        // First, try to hide the subview, such as the symbol input view or the cursor view.
        // If neither is shown, hideSubInputView would fail, then hide the whole software keyboard.
        if (!viewManager.hideSubInputView()) {
            requestHideSelf(0)
        }
        return true
    }

    private fun maybeProcessActionKey(keyCode: Int): Boolean {
        // Handle the event iff the enter is pressed.
        return if (keyCode != KeyEvent.KEYCODE_ENTER || !isInputViewShown) {
            false
        } else sendEditorAction(true)
    }

    /**
     * Sends editor action to `InputConnection`.
     *
     *
     * The difference from [InputMethodService.sendDefaultEditorAction] is
     * that if custom action label is specified `EditorInfo#actionId` is sent instead.
     */
    private fun sendEditorAction(fromEnterKey: Boolean): Boolean {
        // If custom action label is specified (=non-null), special action id is also specified.
        // If there is no IME_FLAG_NO_ENTER_ACTION option, we should send the id to the InputConnection.
        val editorInfo = currentInputEditorInfo
        if (editorInfo != null && editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 && editorInfo.actionLabel != null) {
            val inputConnection = currentInputConnection
            if (inputConnection != null) {
                inputConnection.performEditorAction(editorInfo.actionId)
                return true
            }
        }
        // No custom action label is specified. Fall back to default EditorAction.
        return sendDefaultEditorAction(fromEnterKey)
    }

    private fun maybeDeleteSurroundingText(output: Output, inputConnection: InputConnection) {
        if (!output.hasDeletionRange()) {
            return
        }
        val range = output.deletionRange
        val leftRange = -range.offset
        val rightRange = range.length - leftRange
        if (leftRange < 0 || rightRange < 0) {
            // If the range does not include the current position, do nothing
            // because Android's API does not expect such situation.
            MozcLog.w("Deletion range has unsupported parameters: $range")
            return
        }
        if (!inputConnection.deleteSurroundingText(leftRange, rightRange)) {
            MozcLog.e("Failed to delete surrounding text.")
        }
    }

    private fun maybeCommitText(output: Output, inputConnection: InputConnection) {
        if (!output.hasResult()) {
            return
        }
        val outputText = output.result.value
        if (outputText == "") {
            // Do nothing for an empty result string.
            return
        }
        var position = MozcUtil.CURSOR_POSITION_TAIL
        if (output.result.hasCursorOffset()) {
            if (output.result.cursorOffset
                == -outputText.codePointCount(0, outputText.length)
            ) {
                position = MozcUtil.CURSOR_POSITION_HEAD
            } else {
                MozcLog.e("Unsupported position: " + output.result.toString())
            }
        }
        if (!inputConnection.commitText(outputText, position)) {
            MozcLog.e("Failed to commit text.")
        }
    }

    private fun setComposingText(command: Command, inputConnection: InputConnection) {
        Preconditions.checkNotNull(command)
        Preconditions.checkNotNull(inputConnection)
        val output = command.output
        if (!output.hasPreedit()) {
            // If preedit field is empty, we should clear composing text in the InputConnection
            // because Mozc server asks us to do so.
            // But there is special situation in Android.
            // On onWindowShown, SWITCH_INPUT_MODE command is sent as a step of initialization.
            // In this case we reach here with empty preedit.
            // As described above we should clear the composing text but if we do so
            // texts in selection range (e.g., URL in OmniBox) is always cleared.
            // To avoid from this issue, we don't clear the composing text if the input
            // is SWITCH_INPUT_MODE.
            val input = command.input
            if (input.type != Input.CommandType.SEND_COMMAND
                || input.command.type != SessionCommand.CommandType.SWITCH_INPUT_MODE
            ) {
                if (!inputConnection.setComposingText("", 0)) {
                    MozcLog.e("Failed to set composing text.")
                }
            }
            return
        }

        // Builds preedit expression.
        val preedit = output.preedit
        val builder = SpannableStringBuilder()
        for (segment in preedit.segmentList) {
            builder.append(segment.value)
        }

        // Set underline for all the preedit text.
        builder.setSpan(
            SPAN_UNDERLINE,
            0,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Draw cursor if in composition mode.
        val cursor = preedit.cursor
        val spanFlags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING
        if (output.hasAllCandidateWords()
            && output.allCandidateWords.hasCategory()
            && output.allCandidateWords.category == ProtoCandidates.Category.CONVERSION
        ) {
            var offsetInString = 0
            for (segment in preedit.segmentList) {
                val length = segment.value.length
                builder.setSpan(
                    if (segment.hasAnnotation() && segment.annotation == Preedit.Segment.Annotation.HIGHLIGHT) SPAN_CONVERT_HIGHLIGHT else CharacterStyle::class.java.cast(
                        BackgroundColorSpan(CONVERT_NORMAL_COLOR)
                    ),
                    offsetInString, offsetInString + length, spanFlags
                )
                offsetInString += length
            }
        } else {
            // We cannot show system cursor inside preedit here.
            // Instead we change text style before the preedit's cursor.
            val cursorOffsetInString = builder.toString().offsetByCodePoints(0, cursor)
            if (cursor != builder.length) {
                builder.setSpan(
                    SPAN_PARTIAL_SUGGESTION_COLOR,
                    cursorOffsetInString,
                    builder.length,
                    spanFlags
                )
            }
            if (cursor > 0) {
                builder.setSpan(
                    SPAN_BEFORE_CURSOR,
                    0,
                    cursorOffsetInString,
                    spanFlags
                )
            }
        }

        // System cursor will be moved to the tail of preedit.
        // It triggers onUpdateSelection again.
        val cursorPosition = if (cursor > 0) MozcUtil.CURSOR_POSITION_TAIL else 0
        if (!inputConnection.setComposingText(builder, cursorPosition)) {
            MozcLog.e("Failed to set composing text.")
        }
    }

    private fun maybeSetSelection(output: Output, inputConnection: InputConnection) {
        if (!output.hasPreedit()) {
            return
        }
        val preedit = output.preedit
        val cursor = preedit.cursor
        if (cursor == 0 || cursor == getPreeditLength(preedit)) {
            // The cursor is at the beginning/ending of the preedit. So we don't anything about the
            // caret setting.
            return
        }
        var caretPosition: Int = selectionTracker.preeditStartPosition
        if (output.hasDeletionRange()) {
            caretPosition += output.deletionRange.offset
        }
        if (output.hasResult()) {
            caretPosition += output.result.value.length
        }
        if (output.hasPreedit()) {
            caretPosition += output.preedit.cursor
        }
        if (!inputConnection.setSelection(caretPosition, caretPosition)) {
            MozcLog.e("Failed to set selection.")
        }
    }

    /**
     * Hook to support mushroom protocol. If there is pending Mushroom result for the connecting
     * field, commit it. Then, (regardless of whether there exists pending result,) clears
     * all remaining pending result.
     */
    private fun maybeCommitMushroomResult(attribute: EditorInfo, connection: InputConnection?) {
        if (connection == null) {
            return
        }
        val resultProxy = MushroomResultProxy.getInstance()
        var result: String?
        synchronized(resultProxy) {
            // We need to obtain the result.
            result = resultProxy.getReplaceKey(attribute.fieldId)
        }
        if (result != null) {
            // Found the pending mushroom application result to the connecting field. Commit it.
            connection.commitText(result, MozcUtil.CURSOR_POSITION_TAIL)
            // And clear the proxy.
            // Previous implementation cleared the proxy even when the replace result is NOT found.
            // This caused incompatible mushroom issue because the activity transition gets sometimes
            // like following:
            //   Mushroom activity -> Intermediate activity -> Original application activity
            // In this case the intermediate activity unexpectedly consumed the result so nothing
            // was committed to the application activity.
            // To fix this issue the proxy is cleared when:
            // - The result is committed. OR
            // - Mushroom activity is launched.
            // NOTE: In the worst case, result data might remain in the proxy.
            synchronized(resultProxy) { resultProxy.clear() }
        }
    }

    private fun getPreeditLength(preedit: Preedit): Int {
        var result = 0
        for (i in 0 until preedit.segmentCount) {
            result += preedit.getSegment(i).valueLength
        }
        return result
    }

    private inner class RenderResultCallback : EvaluationCallback {
        override fun onCompleted(
            command: Optional<Command>,
            triggeringKeyEvent: Optional<KeyEventInterface>
        ) {
            Preconditions.checkArgument(Preconditions.checkNotNull(command).isPresent)
            Preconditions.checkNotNull(triggeringKeyEvent)
            if (command.get().input.command.type
                != SessionCommand.CommandType.EXPAND_SUGGESTION
            ) {
                // For expanding suggestions, we don't need to update our rendering result.
                renderInputConnection(command.get(), triggeringKeyEvent.orNull())
            }
            // Transit to narrow mode if required (e.g., Typed 'a' key from h/w keyboard).
            viewManager.maybeTransitToNarrowMode(command.get(), triggeringKeyEvent.orNull())
            viewManager.render(command.get())
        }
    }

    /**
     * Callback to send key event to a application.
     */
    @VisibleForTesting
    internal inner class SendKeyToApplicationCallback : EvaluationCallback {
        override fun onCompleted(
            command: Optional<Command>,
            triggeringKeyEvent: Optional<KeyEventInterface>
        ) {
            Preconditions.checkArgument(!Preconditions.checkNotNull(command).isPresent)
            sendKeyEvent(triggeringKeyEvent.orNull())
        }
    }

    /**
     * Callback to send key event to view layer.
     */
    private inner class SendKeyToViewCallback : EvaluationCallback {
        override fun onCompleted(
            command: Optional<Command>, triggeringKeyEvent: Optional<KeyEventInterface>
        ) {
            Preconditions.checkArgument(!Preconditions.checkNotNull(command).isPresent)
            Preconditions.checkArgument(Preconditions.checkNotNull(triggeringKeyEvent).isPresent)
            viewManager.consumeKeyOnViewSynchronously(triggeringKeyEvent.get().nativeEvent.orNull())
        }
    }

    /**
     * Callback to invoke onUpdateSelectionInternal with delay for onConfigurationChanged.
     * See onConfigurationChanged for the details.
     */
    private inner class ConfigurationChangeCallback : Handler.Callback {
        override fun handleMessage(msg: Message): Boolean {
            val selectionStart = msg.arg1
            val selectionEnd = msg.arg2
            onUpdateSelectionInternal(
                selectionStart,
                selectionEnd,
                selectionStart,
                selectionEnd,
                -1,
                -1
            )
            return true
        }
    }

    /**
     * Sends imposed config to the Mozc server.
     *
     * Some config items should be mobile ones.
     * For example, "selection shortcut" should be disabled on software keyboard
     * regardless of stored config if there is no hardware keyboard.
     */
    private fun updateImposedConfig() {
        // TODO(hsumita): Respect Config.SelectionShortcut.
        val shortcutMode =
            if (viewManager != null && viewManager.isFloatingCandidateMode) SelectionShortcut.SHORTCUT_123456789 else SelectionShortcut.NO_SHORTCUT

        // TODO(matsuzakit): deviceConfig should be used to set following config items.
        sessionExecutor.setImposedConfig(
            ProtoConfig.Config.newBuilder()
                .setSessionKeymap(SessionKeymap.MOBILE)
                .setSelectionShortcut(shortcutMode)
                .setUseEmojiConversion(true)
                .build()
        )
    }

    @VisibleForTesting
    fun onUpdateSelectionInternal(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        MozcLog.d("start MozcService#onUpdateSelectionInternal " + System.nanoTime())
        if (isDebugBuild) {
            MozcLog.d(
                "selection updated: [" + oldSelStart + ":" + oldSelEnd + "] "
                        + "to: [" + newSelStart + ":" + newSelEnd + "] "
                        + "candidates: [" + candidatesStart + ":" + candidatesEnd + "]"
            )
        }
        val updateStatus = selectionTracker.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
            applicationCompatibility.isIgnoringMoveToTail()
        )
        if (isDebugBuild) {
            MozcLog.d(selectionTracker.toString())
        }
        when (updateStatus) {
            SelectionTracker.DO_NOTHING -> {}
            SelectionTracker.RESET_CONTEXT -> {
                sessionExecutor.resetContext()

                // Commit the current composing text (preedit text), in case we hit an unknown state.
                // Keeping the composing text sometimes makes it impossible for users to input characters,
                // because it can cause consecutive mis-understanding of caret positions.
                // We do this iff the keyboard is shown, because some other application may edit
                // composition string, such as Google Translate.
                if (isInputViewShown && inputBound) {
                    val inputConnection = currentInputConnection
                    inputConnection?.finishComposingText()
                }

                // Rendering default Command causes hiding candidate window,
                // and re-showing the keyboard view.
                viewManager.render(Command.getDefaultInstance())
            }
            else -> {
                // Otherwise, the updateStatus is the position of the cursor to be moved.
                if (updateStatus < 0) {
                    throw AssertionError("Unknown update status: $updateStatus")
                }
                sessionExecutor.moveCursor(updateStatus, renderResultCallback)
            }
        }
        MozcLog.d("end MozcService#onUpdateSelectionInternal " + System.nanoTime())
    }

    /**
     * Sends mozcKeyEvent and/or Request to mozc server.
     *
     * This skips to send request if the given keyboard specification is same as before.
     */
    @VisibleForTesting
    fun sendKeyWithKeyboardSpecification(
        mozcKeyEvent: ProtoCommands.KeyEvent?, event: KeyEventInterface?,
        keyboardSpecification: KeyboardSpecification?, configuration: Configuration,
        touchEventList: List<TouchEvent>
    ) {
        if (keyboardSpecification != null && currentKeyboardSpecification != keyboardSpecification) {
            // Submit composition on the transition from software KB to hardware KB by key event.
            // This is done only when mozcKeyEvent is non-null (== the key event is a printable
            // character) in order to avoid clearing pre-selected characters by meta keys.
            if (!currentKeyboardSpecification.isHardwareKeyboard
                && keyboardSpecification.isHardwareKeyboard
                && mozcKeyEvent != null
            ) {
                sessionExecutor.submit(renderResultCallback)
            }
            changeKeyboardSpecificationAndSendKey(
                mozcKeyEvent, event, keyboardSpecification, configuration, touchEventList
            )
            updateStatusIcon()
        } else if (mozcKeyEvent != null) {
            // Send mozcKeyEvent as usual.
            sessionExecutor.sendKey(mozcKeyEvent, event, touchEventList, renderResultCallback)
        } else if (event != null) {
            // Send event back to the application to handle key events which cannot be converted into Mozc
            // key event (e.g. Shift) correctly.
            sessionExecutor.sendKeyEvent(event, sendKeyToApplicationCallback)
        }
    }

    /**
     * Sends Request for changing keyboard setting to mozc server and sends key.
     */
    private fun changeKeyboardSpecificationAndSendKey(
        mozcKeyEvent: ProtoCommands.KeyEvent?, event: KeyEventInterface?,
        keyboardSpecification: KeyboardSpecification, configuration: Configuration,
        touchEventList: List<TouchEvent>
    ) {
        // Send Request to change composition table.
        sessionExecutor.updateRequest(
            MozcUtil.getRequestBuilder(resources, keyboardSpecification, configuration).build(),
            touchEventList
        )
        if (mozcKeyEvent == null) {
            // Change composition mode.
            sessionExecutor.switchInputMode(
                Optional.fromNullable(event), keyboardSpecification.compositionMode,
                renderResultCallback
            )
        } else {
            // Send key with composition mode change.
            sessionExecutor.sendKey(
                ProtoCommands.KeyEvent.newBuilder(mozcKeyEvent)
                    .setMode(keyboardSpecification.compositionMode).build(),
                event, touchEventList, renderResultCallback
            )
        }
        currentKeyboardSpecification = keyboardSpecification
    }

    /**
     * Shows/Hides status icon according to the input view status.
     */
    private fun updateStatusIcon() {
        if (isInputViewShown) {
            showStatusIcon()
        } else {
            hideStatusIcon()
        }
    }

    /**
     * Shows the status icon basing on the current keyboard spec.
     */
    private fun showStatusIcon() {
        when (currentKeyboardSpecification.getCompositionMode()) {
            CompositionMode.HIRAGANA -> showStatusIcon(R.drawable.status_icon_hiragana)
            else -> showStatusIcon(R.drawable.status_icon_alphabet)
        }
    }

    /**
     * @return true if connected view is WebEditText (or the application pretends it)
     */
    private fun isWebEditText(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) {
            return false
        }
        if (applicationCompatibility.isPretendingWebEditText()) {
            return true
        }

        // TODO(hidehiko): Refine the heuristic to check isWebEditText related stuff.
        MozcLog.d("inputType: " + editorInfo.inputType)
        val variation = editorInfo.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
    }

    /** Adapter implementation of the symbol history manipulation.  */
    internal class SymbolHistoryStorageImpl(private val sessionExecutor: SessionExecutor) :
        SymbolHistoryStorage {
        companion object {
            var STORAGE_TYPE_MAP: Map<SymbolMajorCategory, GenericStorageEntry.StorageType>? = null

            init {
                val map: MutableMap<SymbolMajorCategory, GenericStorageEntry.StorageType> = EnumMap(
                    SymbolMajorCategory::class.java
                )
                map[SymbolMajorCategory.SYMBOL] = GenericStorageEntry.StorageType.SYMBOL_HISTORY
                map[SymbolMajorCategory.EMOTICON] = GenericStorageEntry.StorageType.EMOTICON_HISTORY
                map[SymbolMajorCategory.EMOJI] = GenericStorageEntry.StorageType.EMOJI_HISTORY
                STORAGE_TYPE_MAP = Collections.unmodifiableMap(map)
            }
        }

        override fun getAllHistory(majorCategory: SymbolMajorCategory): List<String> {
            val historyList = sessionExecutor.readAllFromStorage(
                STORAGE_TYPE_MAP!![majorCategory]
            )
            val result: MutableList<String> = ArrayList(historyList.size)
            for (value in historyList) {
                result.add(MozcUtil.utf8CStyleByteStringToString(value))
            }
            return result
        }

        override fun addHistory(majorCategory: SymbolMajorCategory, value: String) {
            Preconditions.checkNotNull(majorCategory)
            Preconditions.checkNotNull(value)
            sessionExecutor.insertToStorage(
                STORAGE_TYPE_MAP!![majorCategory],
                value, listOf(ByteString.copyFromUtf8(value))
            )
        }
    }

    private inner class MozcEventListener: ViewEventListener {
        override fun onConversionCandidateSelected(candidateId: Int, rowIndex: Optional<Int?>?) {
            sessionExecutor.submitCandidate(candidateId, rowIndex, renderResultCallback)
            feedbackManager.fireFeedback(FeedbackEvent.CANDIDATE_SELECTED)
        }

        override fun onPageUp() {
            sessionExecutor.pageUp(renderResultCallback)
            feedbackManager.fireFeedback(FeedbackEvent.KEY_DOWN)
        }

        override fun onPageDown() {
            sessionExecutor.pageDown(renderResultCallback)
            feedbackManager.fireFeedback(FeedbackEvent.KEY_DOWN)
        }

        override fun onSymbolCandidateSelected(
            majorCategory: SymbolMajorCategory, candidate: String,
            updateHistory: Boolean
        ) {
            Preconditions.checkNotNull(majorCategory)
            Preconditions.checkNotNull(candidate)

            // Directly commit the text.
            commitText(candidate)
            if (updateHistory) {
                symbolHistoryStorage.addHistory(majorCategory, candidate)
            }
//            feedbackManager.fireFeedback(FeedbackEvent.CANDIDATE_SELECTED)
        }

        open fun commitText(text: String) {
            val inputConnection = currentInputConnection ?: return
            inputConnection.beginBatchEdit()
            try {
                inputConnection.commitText(text, MozcUtil.CURSOR_POSITION_TAIL)
            } finally {
                inputConnection.endBatchEdit()
            }
        }

        override fun onKeyEvent(
            mozcKeyEvent: ProtoCommands.KeyEvent?, keyEvent: KeyEventInterface?,
            keyboardSpecification: KeyboardSpecification?, touchEventList: List<TouchEvent>
        ) {
            if (mozcKeyEvent == null && keyboardSpecification == null) {
                // We don't send a key event to Mozc native layer since {@code mozcKeyEvent} is null, and we
                // don't need to update the keyboard specification since {@code keyboardSpecification} is
                // also null.
                if (keyEvent == null) {
                    // Send a usage information to Mozc native layer.
                    sessionExecutor.touchEventUsageStatsEvent(touchEventList)
                } else {
                    // Send a key event (which is generated by Mozc in the usual case) to application.
                    Preconditions.checkArgument(touchEventList.isEmpty())
                    sessionExecutor.sendKeyEvent(keyEvent, sendKeyToApplicationCallback)
                }
                return
            }
            sendKeyWithKeyboardSpecification(
                mozcKeyEvent, keyEvent,
                keyboardSpecification, resources.configuration,
                touchEventList
            )
        }

        override fun onUndo(touchEventList: List<TouchEvent?>?) {
            sessionExecutor.undoOrRewind(touchEventList, renderResultCallback)
        }

        override fun onFireFeedbackEvent(event: FeedbackEvent) {
//            feedbackManager.fireFeedback(event)
            if (event == FeedbackEvent.INPUTVIEW_EXPAND) {
                sessionExecutor.sendUsageStatsEvent(UsageStatsEvent.KEYBOARD_EXPAND_EVENT)
            } else if (event == FeedbackEvent.INPUTVIEW_FOLD) {
                sessionExecutor.sendUsageStatsEvent(UsageStatsEvent.KEYBOARD_FOLD_EVENT)
            }
        }

        override fun onSubmitPreedit() {
            sessionExecutor.submit(renderResultCallback)
        }

        override fun onExpandSuggestion() {
            sessionExecutor.expandSuggestion(renderResultCallback)
        }

        override fun onShowMenuDialog(touchEventList: List<TouchEvent?>?) {
            sessionExecutor.touchEventUsageStatsEvent(touchEventList)
        }

        override fun onShowSymbolInputView(touchEventList: List<TouchEvent?>?) {
            changeKeyboardSpecificationAndSendKey(
                null,
                null,
                KeyboardSpecification.SYMBOL_NUMBER,
                resources.configuration,
                emptyList<TouchEvent>()
            )
            viewManager.onShowSymbolInputView()
        }

        override fun onCloseSymbolInputView() {
            viewManager.onCloseSymbolInputView()
            // This callback is called in two ways: one is from touch event on symbol input view.
            // The other is from onKeyDown event by hardware keyboard.  ViewManager.isNarrowMode()
            // is abused to distinguish these two triggers where its true value indicates that
            // onCloseSymbolInputView() is called on hardware keyboard event.  In the case of hardware
            // keyboard event, keyboard specification has been already updated so we shouldn't update it.
            if (!viewManager.isNarrowMode()) {
                changeKeyboardSpecificationAndSendKey(
                    null,
                    null,
                    viewManager.getKeyboardSpecification(),
                    resources.configuration,
                    emptyList<TouchEvent>()
                )
            }
        }

        override fun onHardwareKeyboardCompositionModeChange(mode: CompositionSwitchMode?) {
            viewManager.switchHardwareKeyboardCompositionMode(mode)
        }

        override fun onActionKey() {
            // false means that the key is for Action and not ENTER.
            sendEditorAction(false)
        }

        override fun onNarrowModeChanged(newNarrowMode: Boolean) {
            if (!newNarrowMode) {
                // Hardware keyboard to software keyboard transition: Submit composition.
                sessionExecutor.submit(renderResultCallback)
            }
            updateImposedConfig()
        }

        override fun onUpdateKeyboardLayoutAdjustment(
            layoutAdjustment: LayoutAdjustment
        ) {
            Preconditions.checkNotNull(layoutAdjustment)
            val configuration: Configuration = resources.configuration
            val isLandscapeKeyboardSettingActive = PreferenceUtil.isLandscapeKeyboardSettingActive(
                sharedPreferences, configuration.orientation
            )
            val key = if (isLandscapeKeyboardSettingActive) {
                PreferenceUtil.PREF_LANDSCAPE_LAYOUT_ADJUSTMENT_KEY
            } else {
                PreferenceUtil.PREF_PORTRAIT_LAYOUT_ADJUSTMENT_KEY
            }
            sharedPreferences.edit()
                .putString(key, layoutAdjustment.toString())
                .apply()
        }

        override fun onShowMushroomSelectionDialog() {
            sessionExecutor.sendUsageStatsEvent(UsageStatsEvent.MUSHROOM_SELECTION_DIALOG_OPEN_EVENT)
        }
    }

    /**
     * A call-back to catch all the change on any preferences.
     */
    private inner class SharedPreferenceChangeAdapter : OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
            if (isDebugBuild) {
                MozcLog.d("onSharedPreferenceChanged : $key")
            }
            if (key.startsWith(PREF_TWEAK_PREFIX)) {
                // If the key belongs to PREF_TWEAK group, re-create SessionHandler and view.
                prepareEveryTime(sharedPreferences, resources.configuration)
                setInputView(onCreateInputView())
                return
            }
            propagateClientSidePreference(
                ClientSidePreference(
                    sharedPreferences, getResources(), resources.configuration.orientation
                )
            )
            sessionExecutor.config = ConfigUtil.toConfig(sharedPreferences)
            sessionExecutor.preferenceUsageStatsEvent(sharedPreferences, getResources())
        }
    }

    private class RealFeedbackListener constructor(
        vibrator: Vibrator?,
        audioManager: AudioManager?
    ) :
        FeedbackListener {
        private val vibrator: Vibrator?
        private val audioManager: AudioManager?
        override fun onVibrate(duration: Long) {
            if (duration < 0) {
                MozcLog.w("duration must be >= 0 but $duration")
                return
            }
            vibrator?.vibrate(duration)
        }

        override fun onSound(soundEffectType: Int, volume: Float) {
            if (audioManager != null && soundEffectType != FeedbackEvent.NO_SOUND) {
                audioManager.playSoundEffect(soundEffectType, volume)
            }
        }

        init {
            if (vibrator == null) {
                MozcLog.w("vibrator must be non-null. Vibration is disabled.")
            }
            this.vibrator = vibrator
            if (audioManager == null) {
                MozcLog.w("audioManager must be non-null. Sound feedback is disabled.")
            }
            this.audioManager = audioManager
        }
    }

    /**
     * We need to send SYNC_DATA command periodically. This class handles it.
     */
    @SuppressLint("HandlerLeak")
    private inner class SendSyncDataCommandHandler : Handler() {
        override fun handleMessage(msg: Message) {
            sessionExecutor.syncData()
            sendEmptyMessageDelayed(0, SendSyncDataCommandHandlerCompanion.SYNC_DATA_COMMAND_PERIOD.toLong())
        }
    }

    object SendSyncDataCommandHandlerCompanion {
        /**
         * "what" value of message. Always use this.
         */
        const val WHAT = 0

        /**
         * The current period of sending SYNC_DATA is 15 mins (as same as desktop version).
         */
        const val SYNC_DATA_COMMAND_PERIOD = 15 * 60 * 1000
    }

    /**
     * To trim memory, a message is handled to invoke trimMemory method
     * 10 seconds after hiding window.
     *
     * This class handles callback operation.
     * Posting and removing messages should be done in appropriate point.
     */
    @SuppressLint("HandlerLeak")
    private inner class MemoryTrimmingHandler : Handler() {
        override fun handleMessage(msg: Message) {
            trimMemory()
            // Other messages in the queue are removed as they will do the same thing
            // and will affect nothing.
            removeMessages(MemoryTrimmingHandlerCompanion.WHAT)
        }
    }

    object MemoryTrimmingHandlerCompanion {
        /**
         * "what" value of message. Always use this.
         */
        const val WHAT = 0

        /**
         * Duration after hiding window in milliseconds.
         */
        const val DURATION_MS = 10 * 1000
    }

    private fun trimMemory() {
        // We must guarantee the contract of MemoryManageable#trimMemory.
        if (!isInputViewShown) {
            MozcLog.d("Trimming memory")
            sessionExecutor.deleteSession()
            viewManager.trimMemory()
        }
    }

    companion object {

        // Keys for tweak preferences.
        private const val PREF_TWEAK_PREFIX = "pref_tweak_"
        private const val PREF_TWEAK_LOGGING_PROTOCOL_BUFFERS =
            "pref_tweak_logging_protocol_buffers"

        // Focused segment's attribute.
        @VisibleForTesting
        val SPAN_CONVERT_HIGHLIGHT: CharacterStyle = BackgroundColorSpan(0x66EF3566)

        // Background color span for non-focused conversion segment.
        // We don't create a static CharacterStyle instance since there are multiple segments at the same
        // time. Otherwise, segments except for the last one cannot have style.
        @VisibleForTesting
        val CONVERT_NORMAL_COLOR = 0x19EF3566

        // Cursor position.
        // Note that InputConnection seems not to be able to show cursor. This is a workaround.
        @VisibleForTesting
        val SPAN_BEFORE_CURSOR: CharacterStyle = BackgroundColorSpan(0x664DB6AC)

        // Background color span for partial conversion.
        @VisibleForTesting
        val SPAN_PARTIAL_SUGGESTION_COLOR: CharacterStyle = BackgroundColorSpan(0x194DB6AC)

        // Underline.
        @VisibleForTesting
        val SPAN_UNDERLINE: CharacterStyle = UnderlineSpan()

        fun getInputFieldType(attribute: EditorInfo): InputFieldType {
            val inputType = attribute.inputType
            if (MozcUtil.isPasswordField(inputType)) {
                return InputFieldType.PASSWORD
            }
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            if (inputClass == InputType.TYPE_CLASS_PHONE) {
                return InputFieldType.TEL
            }
            return if (inputClass == InputType.TYPE_CLASS_NUMBER) {
                InputFieldType.NUMBER
            } else InputFieldType.NORMAL
        }

    }

}