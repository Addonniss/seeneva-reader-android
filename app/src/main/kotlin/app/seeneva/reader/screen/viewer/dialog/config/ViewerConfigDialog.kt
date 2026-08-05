/*
 * This file is part of Seeneva Android Reader
 * Copyright (C) 2021 Sergei Solodovnikov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.seeneva.reader.screen.viewer.dialog.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.annotation.FloatRange
import androidx.core.view.forEach
import androidx.lifecycle.lifecycleScope
import app.seeneva.reader.R
import app.seeneva.reader.binding.getValue
import app.seeneva.reader.binding.viewBinding
import app.seeneva.reader.databinding.DialogViewerSettingsBinding
import app.seeneva.reader.di.autoInit
import app.seeneva.reader.di.getValue
import app.seeneva.reader.di.koinLifecycleScope
import app.seeneva.reader.di.requireActivityScope
import app.seeneva.reader.logic.entity.configuration.ViewerConfig
import app.seeneva.reader.logic.entity.configuration.ViewerGestureAction
import app.seeneva.reader.logic.text.Language
import app.seeneva.reader.logic.text.tts.TTS
import app.seeneva.reader.logic.text.tts.TTSErrorResolver
import app.seeneva.reader.presenter.PresenterView
import app.seeneva.reader.screen.list.dialog.BaseDraggableDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.koin.core.scope.KoinScopeComponent
import org.koin.core.scope.inject
import org.tinylog.kotlin.Logger
import java.text.Format
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

interface ViewerConfigView : PresenterView {
    /**
     * Show loading state
     */
    fun onConfigLoading()

    /**
     * Show current viewer config
     * @param config config to show
     */
    fun showConfig(config: ViewerConfig)

    /**
     * @param brightness brightness to show
     */
    fun showBrightness(@FloatRange(from = .0, to = 1.0) brightness: Float)

    /**
     * @param scale bubble scale multiplier to show
     */
    fun showBubbleScale(scale: Float)

    /**
     * @param maxZoom maximum zoom scale to show
     */
    fun showMaxZoom(maxZoom: Float)
}

class ViewerConfigDialog : BaseDraggableDialog(), ViewerConfigView, KoinScopeComponent {
    private val viewBinding by viewBinding(DialogViewerSettingsBinding::bind)

    private val lifecycleScope = koinLifecycleScope { it.linkTo(requireActivityScope()) }

    override val scope by lifecycleScope

    private val presenter by lifecycleScope.autoInit<ViewerConfigPresenter>()

    private val ttsResolver by inject<TTSErrorResolver>()

    private val callback by lazy { scope.getOrNull<Callback>() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.dialog_viewer_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewBinding.keepScreenOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            presenter.onKeepScreenOnChange(isChecked)
        }

        viewBinding.instantInteractionsSwitch.setOnCheckedChangeListener { _, isChecked ->
            presenter.onInstantInteractionsChange(isChecked)
        }

        viewBinding.doubleTapPageNavSwitch.setOnCheckedChangeListener { _, isChecked ->
            presenter.onDoubleTapPageNavChange(isChecked)
        }

        viewBinding.ttsSwitch.setOnClickListener { presenter.onTtsChange(viewBinding.ttsSwitch.isChecked) }

        viewBinding.brightnessSlider.setLabelFormatter(object : LabelFormatter {
            private val formatter: Format =
                NumberFormat.getPercentInstance()
                    .apply { maximumFractionDigits = 1 }

            override fun getFormattedValue(value: Float) =
                formatter.format(value)
        })

        viewBinding.systemBrightnessSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewBinding.brightnessSlider.isEnabled = !isChecked
            presenter.onSystemBrightnessChange(isChecked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewBinding.brightnessSlider.userProgress().collect { presenter.onBrightnessChange(it) }
        }

        viewBinding.bubbleScaleSlider.setLabelFormatter(object : LabelFormatter {
            private val formatter: Format =
                NumberFormat.getPercentInstance()
                    .apply { maximumFractionDigits = 1 }

            override fun getFormattedValue(value: Float) =
                formatter.format(value)
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewBinding.bubbleScaleSlider.userProgress().collect { scale ->
                showBubbleScale(scale)

                presenter.onBubbleScaleChange(scale)
            }
        }

        viewBinding.maxZoomSlider.setLabelFormatter(object : LabelFormatter {
            private val formatter: Format =
                NumberFormat.getNumberInstance()
                    .apply {
                        minimumFractionDigits = 0
                        maximumFractionDigits = 0
                    }

            override fun getFormattedValue(value: Float) =
                "${formatter.format(value)}×"
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewBinding.maxZoomSlider.userProgress().collect { maxZoom ->
                showMaxZoom(maxZoom)

                presenter.onMaxZoomChange(maxZoom)
            }
        }

        //Configurable gestures. Selection events are suppressed until the saved
        //config is applied by [showConfig]
        val gestureActionItems = listOf(
            getString(R.string.viewer_settings_gesture_action_none),
            getString(R.string.viewer_settings_gesture_action_thumbnails),
            getString(R.string.viewer_settings_gesture_action_settings)
        )

        setupGestureSpinner(
            viewBinding.bottomSwipeUpActionSpinner,
            gestureActionItems
        ) { action -> presenter.onBottomSwipeUpActionChange(action) }

        setupGestureSpinner(
            viewBinding.twoFingerTapBottomActionSpinner,
            gestureActionItems
        ) { action -> presenter.onTwoFingerTapBottomActionChange(action) }

        setupGestureSpinner(
            viewBinding.twoFingerTapTopActionSpinner,
            gestureActionItems
        ) { action -> presenter.onTwoFingerTapTopActionChange(action) }

        viewLifecycleOwner.lifecycleScope.launch {
            presenter.changeTtsEvents.collect {
                when (it) {
                    ChangeTtsEvent.Idle -> viewBinding.ttsSwitch.isEnabled = true
                    ChangeTtsEvent.Process -> viewBinding.ttsSwitch.isEnabled = false
                    is ChangeTtsEvent.Result -> {
                        if (it.result != TTS.InitResult.Success) {
                            viewBinding.ttsSwitch.toggle()

                            showTtsStateDialog(it.result)
                        }
                    }
                }
            }
        }
    }

    override fun onConfigLoading() {
        enableView(requireView(), false)

        showBrightness(.0f)
    }

    override fun showConfig(config: ViewerConfig) {
        Logger.debug { "Show new viewer config $config" }

        if (!requireView().isEnabled) {
            enableView(requireView(), true)
        }

        viewBinding.ttsSwitch.isChecked = config.tts

        viewBinding.keepScreenOnSwitch.isChecked = config.keepScreenOn

        viewBinding.instantInteractionsSwitch.isChecked = config.instantViewerInteractions

        viewBinding.doubleTapPageNavSwitch.isChecked = config.doubleTapPageNav

        //Apply saved gesture assignments without saving them back
        suppressGestureSpinnerEvents = true

        viewBinding.bottomSwipeUpActionSpinner.setSelection(config.bottomSwipeUpAction.ordinal)
        viewBinding.twoFingerTapBottomActionSpinner.setSelection(
            config.twoFingerTapBottomAction.ordinal
        )
        viewBinding.twoFingerTapTopActionSpinner.setSelection(config.twoFingerTapTopAction.ordinal)

        suppressGestureSpinnerEvents = false

        showBubbleScale(config.bubbleScale)

        showMaxZoom(config.maxZoom)

        @SuppressLint("Range")
        if (config.systemBrightness) {
            viewBinding.systemBrightnessSwitch.isChecked = true
        } else {
            viewBinding.systemBrightnessSwitch.isChecked = false

            showBrightness(config.brightness)
        }

        callback?.onConfigChanged(config)
    }

    override fun showBrightness(@FloatRange(from = .0, to = 1.0) brightness: Float) {
        Logger.debug { "Required brightness is $brightness" }

        viewBinding.brightnessSlider.also { slider ->
            slider.value = brightness.coerceIn(slider.valueFrom, slider.valueTo).also {
                Logger.debug { "Actual brightness is $it" }
            }
        }
    }

    override fun showBubbleScale(scale: Float) {
        viewBinding.bubbleScaleSlider.value = scale.coerceIn(
            viewBinding.bubbleScaleSlider.valueFrom,
            viewBinding.bubbleScaleSlider.valueTo
        )

        viewBinding.bubbleScaleLabel.text =
            getString(R.string.viewer_settings_bubble_scale_value, (scale * 100).roundToInt())
    }

    override fun showMaxZoom(maxZoom: Float) {
        viewBinding.maxZoomSlider.value = maxZoom.coerceIn(
            viewBinding.maxZoomSlider.valueFrom,
            viewBinding.maxZoomSlider.valueTo
        )

        viewBinding.maxZoomLabel.text = getString(
            R.string.viewer_settings_max_zoom_value,
            String.format(Locale.ROOT, "%.0f×", maxZoom)
        )
    }

    /**
     * True while the saved config is being applied to the gesture spinners.
     * Spinner selection events are ignored while it is set.
     */
    private var suppressGestureSpinnerEvents = true

    private fun setupGestureSpinner(
        spinner: Spinner,
        items: List<String>,
        onChange: (ViewerGestureAction) -> Unit
    ) {
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            items
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!suppressGestureSpinnerEvents) {
                    onChange(ViewerGestureAction.values()[position])
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun enableView(view: View, enable: Boolean = true) {
        view.isEnabled = enable

        if (view is ViewGroup) {
            view.forEach { enableView(it, enable) }
        }
    }

    private fun showTtsStateDialog(state: TTS.InitResult) {
        val message = when (state) {
            TTS.InitResult.Success -> return
            TTS.InitResult.EngineNotInstalled -> {
                getString(
                    R.string.viewer_tts_error_not_installed,
                    Language.English.locale.displayName
                )
            }
            TTS.InitResult.LanguageNotSupported -> {
                getString(R.string.viewer_tts_error_language, Language.English.locale.displayName)
            }
        }

        val button = if (ttsResolver.canResolve(state)) {
            getString(R.string.install) to DialogInterface.OnClickListener { _, _ ->
                ttsResolver.resolve(state)
            }
        } else {
            getString(R.string.all_ok) to null
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.viewer_tts_error_title)
            .setMessage(message)
            .setPositiveButton(button.first, button.second)
            .setNegativeButton(R.string.all_cancel, null)
            .show()
    }

    private fun Slider.userProgress(): Flow<Float> =
        callbackFlow {
            val changeListener = Slider.OnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    trySend(value)
                }
            }

            addOnChangeListener(changeListener)

            awaitClose { removeOnChangeListener(changeListener) }
        }.conflate().debounce(50)

    interface Callback {
        fun onConfigChanged(config: ViewerConfig)
    }

    companion object {
        fun newInstance() =
            ViewerConfigDialog()
    }
}