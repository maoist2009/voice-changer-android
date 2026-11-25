package org.voice.changer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.voice.changer.MainViewModel
import org.voice.changer.engine.Backend
import kotlin.math.roundToInt

/** Beatrice page: the CPU backend, its model and its own tunables. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BeatriceScreen(vm: MainViewModel, onNavigate: (Page) -> Unit) {
    val state by vm.state.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    AppScaffold(
        title = "Beatrice",
        page = Page.Beatrice,
        onNavigate = onNavigate,
        subtitle = "rc.0 · CPU",
    ) {
        ModelCard(
            state = state,
            backend = Backend.BEATRICE,
            onImport = { folderPicker.launch(null) },
            onLoad = { vm.setBackend(Backend.BEATRICE) },
        )

        if (state.beatriceVoices.isNotEmpty()) {
            SectionCard(title = "Voice") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.beatriceVoices.forEachIndexed { i, name ->
                        FilterChip(
                            selected = state.beatriceSelected == i,
                            onClick = { vm.selectBeatriceVoice(i) },
                            label = { Text(name) },
                            leadingIcon = if (state.beatriceSelected == i) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else null,
                        )
                    }
                }
                Hint(
                    if (state.isRvc) "The selected voice applies when you switch to Beatrice."
                    else "Switching voices is a parameter change: the model stays loaded."
                )
            }
        }

        if (state.ready && !state.isRvc) {
            BeatriceParams(state, vm)
            LatencyCard(state, vm)
        }

        PipelineCard(
                stages = listOf(
                    "pitch_estimator" to "f0 contour",
                    "phone_extractor" to "content features",
                    "waveform_generator" to "waveform",
                    "speaker_embeddings" to "voice identity",
                    "embedding_setter" to "voice switch",
                ),
                note = "The rc.0 weights are rebuilt into ONNX graphs and run on ONNX " +
                    "Runtime, all on the CPU: no NPU, no network.",
            )

        state.error?.let { message -> ErrorCard(message) { vm.clearMessage() } }
    }
}

/**
 * The one Beatrice control that changes the graph rather than its output: the
 * frames per inference, which is both the interval between emissions and the
 * look-ahead the delay is made of.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LatencyCard(state: MainViewModel.UiState, vm: MainViewModel) {
    SectionCard(title = "Latency") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                12 to "Smooth · 120 ms",
                8 to "Balanced · 80 ms",
                4 to "Low latency · 40 ms",
            ).forEach { (frames, label) ->
                FilterChip(
                    selected = state.chunkFrames == frames,
                    onClick = { vm.setChunkFrames(frames) },
                    label = { Text(label) },
                )
            }
        }
        Hint(
            "Block size: how often the engine runs and how much audio it emits, " +
                "10 ms per frame. It also sets the look-ahead (262 / 182 / 102 ms), " +
                "which is what the delay is made of, and the CPU per second of " +
                "audio. The graphs are rebuilt from the same weights, so the voice " +
                "does not change. A conversion that is already running keeps the " +
                "size it started with; the new one applies from the next start.",
        )
    }
}

@Composable
private fun BeatriceParams(state: MainViewModel.UiState, vm: MainViewModel) {    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Voice controls",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ParamSlider("Pitch", "%+.0f st".format(state.pitch), state.pitch, -24f..24f, 47) { vm.setPitch(it) }
            ParamSlider("Formant", "%+.1f".format(state.formant), state.formant, -2f..2f, 7) { vm.setFormant(it) }
            ParamSlider("Intonation", "%.2f".format(state.intonation), state.intonation, 0f..2f, 19) { vm.setIntonation(it) }
            ParamSlider("Pitch correction", "%.2f".format(state.correction), state.correction, 0f..1f, 19) { vm.setCorrection(it) }
            ParamSlider("VQ neighbours", "${state.vq}", state.vq.toFloat(), 0f..8f, 7) { vm.setVq(it.roundToInt()) }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            ParamSlider(
                "Noise gate",
                if (state.noiseGate >= 0f) "off" else "%.0f dB".format(state.noiseGate),
                state.noiseGate,
                -70f..0f,
                69,
            ) { vm.setNoiseGate(it) }
            Hint(
                "Input below the threshold never reaches the model, so a quiet " +
                    "room is not synthesised into the target voice. 0 dB is off.",
            )

            ParamSlider(
                "Output level",
                "%.0f%%".format(state.output * 100),
                state.output,
                0.2f..2f,
                17,
            ) { vm.setOutput(it) }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Hint(
                "Pitch and formant are the two controls worth reaching for first; " +
                    "VQ trades clarity for a closer match to the target speaker.",
            )
        }
    }
}
