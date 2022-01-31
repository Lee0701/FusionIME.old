package io.github.lee0701.inputmethod.fusion

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.preference.PreferenceManager
import android.view.View
import android.view.Window
import com.android.inputmethod.latin.LatinIME
import com.google.common.base.Optional
import com.google.common.base.Preconditions
import org.mozc.android.inputmethod.japanese.*
import org.mozc.android.inputmethod.japanese.hardwarekeyboard.HardwareKeyboard
import org.mozc.android.inputmethod.japanese.keyboard.Keyboard
import org.mozc.android.inputmethod.japanese.model.SelectionTracker
import org.mozc.android.inputmethod.japanese.model.SymbolCandidateStorage
import org.mozc.android.inputmethod.japanese.model.SymbolMajorCategory
import org.mozc.android.inputmethod.japanese.preference.ClientSidePreference
import org.mozc.android.inputmethod.japanese.preference.PreferenceUtil
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands
import org.mozc.android.inputmethod.japanese.session.SessionExecutor
import org.mozc.android.inputmethod.japanese.util.ImeSwitcherFactory

class MozcInputViewManager(
    service: InputMethodService,
    override val listener: InputViewManager.Listener,
): InputViewManager {

    private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(service)
    private val resources: Resources = service.resources

    private val applicationCompatibility: ApplicationCompatibility = ApplicationCompatibility.getDefaultInstance()
    private val feedbackListener = object: FeedbackManager.FeedbackListener {
        private val vibrator =
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (service.getSystemService(LatinIME.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else service.getSystemService(LatinIME.VIBRATOR_SERVICE) as Vibrator
        private val audioManager = service.getSystemService(LatinIME.AUDIO_SERVICE) as AudioManager
        override fun onVibrate(duration: Long) {
            vibrator.vibrate(duration)
        }
        override fun onSound(soundEffectType: Int, volume: Float) {
            if (soundEffectType != FeedbackManager.FeedbackEvent.NO_SOUND) {
                audioManager.playSoundEffect(soundEffectType, volume)
            }
        }
    }
    private val feedbackManager: FeedbackManager = FeedbackManager(feedbackListener)

    private var propagatedClientSidePreference: ClientSidePreference? = null

    private val eventListener = MozcEventListener()
    private val symbolHistoryStorage = object: SymbolCandidateStorage.SymbolHistoryStorage {
        override fun getAllHistory(majorCategory: SymbolMajorCategory?): MutableList<String> {
            return mutableListOf()
        }
        override fun addHistory(majorCategory: SymbolMajorCategory?, value: String?) {
        }
    }
    private val imeSwitcher = ImeSwitcherFactory.getImeSwitcher(service)
    private val viewManager: ViewManagerInterface =
        DependencyFactory.getDependency(service.applicationContext)
            .createViewManager(service.applicationContext, eventListener, symbolHistoryStorage, imeSwitcher,
                MozcMenuDialogListenerImpl(service, eventListener)
            ).apply {
                onConfigurationChanged(resources.configuration)
            }

    override fun initView(context: Context): View {
        return viewManager.createMozcView(context)
    }

    override fun computeInsets(
        context: Context,
        outInsets: InputMethodService.Insets?,
        window: Window?
    ) {
        viewManager.computeInsets(context, outInsets, window)
    }

    override fun setPreferences(sharedPreferences: SharedPreferences) {
        val clientSidePreference = ClientSidePreference(sharedPreferences, resources, resources.configuration.orientation)
        propagateClientSidePreference(clientSidePreference)
    }

    override fun hideSubInputView(): Boolean {
        return viewManager.hideSubInputView()
    }

    private inner class MozcEventListener: ViewEventListener {
        override fun onConversionCandidateSelected(candidateId: Int, rowIndex: Optional<Int?>?) {
//            sessionExecutor.submitCandidate(candidateId, rowIndex, renderResultCallback)
//            feedbackManager.fireFeedback(FeedbackManager.FeedbackEvent.CANDIDATE_SELECTED)
        }

        override fun onPageUp() {
//            sessionExecutor.pageUp(renderResultCallback)
//            feedbackManager.fireFeedback(FeedbackManager.FeedbackEvent.KEY_DOWN)
        }

        override fun onPageDown() {
//            sessionExecutor.pageDown(renderResultCallback)
//            feedbackManager.fireFeedback(FeedbackManager.FeedbackEvent.KEY_DOWN)
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
//            val inputConnection = currentInputConnection ?: return
//            inputConnection.beginBatchEdit()
//            try {
//                inputConnection.commitText(text, MozcUtil.CURSOR_POSITION_TAIL)
//            } finally {
//                inputConnection.endBatchEdit()
//            }
        }

        override fun onKeyEvent(
            mozcKeyEvent: ProtoCommands.KeyEvent?, keyEvent: KeycodeConverter.KeyEventInterface?,
            keyboardSpecification: Keyboard.KeyboardSpecification?, touchEventList: List<ProtoCommands.Input.TouchEvent>
        ) {
            listener.onKeyEvent(MozcKeyEvent(keyEvent?.keyCode ?: 0, keyEvent?.nativeEvent?.orNull(), keyEvent, mozcKeyEvent, keyboardSpecification, touchEventList))
        }

        override fun onUndo(touchEventList: List<ProtoCommands.Input.TouchEvent?>?) {
//            sessionExecutor.undoOrRewind(touchEventList, renderResultCallback)
        }

        override fun onFireFeedbackEvent(event: FeedbackManager.FeedbackEvent) {
//            feedbackManager.fireFeedback(event)
        }

        override fun onSubmitPreedit() {
//            sessionExecutor.submit(renderResultCallback)
        }

        override fun onExpandSuggestion() {
//            sessionExecutor.expandSuggestion(renderResultCallback)
        }

        override fun onShowMenuDialog(touchEventList: List<ProtoCommands.Input.TouchEvent?>?) {
//            sessionExecutor.touchEventUsageStatsEvent(touchEventList)
        }

        override fun onShowSymbolInputView(touchEventList: List<ProtoCommands.Input.TouchEvent?>?) {
            listener.onKeyEvent(MozcChangeKeyboardSpecificationEvent(Keyboard.KeyboardSpecification.SYMBOL_NUMBER))
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
                listener.onKeyEvent(MozcChangeKeyboardSpecificationEvent(viewManager.keyboardSpecification))
            }
        }

        override fun onHardwareKeyboardCompositionModeChange(mode: HardwareKeyboard.CompositionSwitchMode?) {
            viewManager.switchHardwareKeyboardCompositionMode(mode)
        }

        override fun onActionKey() {
            // false means that the key is for Action and not ENTER.
//            sendEditorAction(false)
        }

        override fun onNarrowModeChanged(newNarrowMode: Boolean) {
//            if (!newNarrowMode) {
//                // Hardware keyboard to software keyboard transition: Submit composition.
//                sessionExecutor.submit(renderResultCallback)
//            }
//            updateImposedConfig()
        }

        override fun onUpdateKeyboardLayoutAdjustment(
            layoutAdjustment: ViewManagerInterface.LayoutAdjustment
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
//            sessionExecutor.sendUsageStatsEvent(ProtoCommands.SessionCommand.UsageStatsEvent.MUSHROOM_SELECTION_DIALOG_OPEN_EVENT)
        }
    }

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

}