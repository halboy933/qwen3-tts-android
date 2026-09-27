from pathlib import Path
p=Path('app/src/main/java/com/qwen/tts/android/MainActivity.kt'); s=p.read_text(encoding='utf-8')
def once(old,new,label):
 global s
 if new in s: return
 if old not in s: raise SystemExit('PATCH ERROR: '+label)
 s=s.replace(old,new,1)
once('import com.qwen.tts.android.data.VoiceRecorder\n','import com.qwen.tts.android.data.VoiceRecorder\nimport com.qwen.tts.android.data.AudioImporter\n','import')
anchor='    fun cancelVoiceRecording() {\n        recorder.cancel()\n    }\n'
fn='''    fun importVoiceReference(uri: Uri, name: String) {
        if (_uiState.value.busy || recorderState.value.isRecording) return
        val trimmedName = name.trim().ifBlank { "Voice ${voices.value.size + 1}" }
        viewModelScope.launch {
            startBusy(status = "Importing voice reference")
            val created = runCatching { withContext(Dispatchers.IO) {
                val native = ensureEngineLoaded(selectedVariant())
                if (native.getModelCapabilities()?.supportsCloning == false) error("The loaded model does not expose a speaker encoder.")
                val voiceId = "voice-${System.currentTimeMillis()}"; val targetDir = File(voiceDir, voiceId).apply { mkdirs() }
                val reference = File(targetDir, "reference.wav"); val imported = AudioImporter.importToWav(getApplication(), uri, reference)
                if (imported.durationMillis < 3000L) { targetDir.deleteRecursively(); error("Reference is too short. Use at least 3 seconds.") }
                val embedding = File(targetDir, "speaker.json")
                if (!native.extractSpeakerEmbedding(reference.absolutePath, embedding.absolutePath)) { targetDir.deleteRecursively(); error(native.getLastError() ?: "Speaker embedding extraction failed") }
                VoiceProfileEntity(voiceId=voiceId,name=makeUniqueVoiceName(trimmedName),referenceWavPath=reference.absolutePath,speakerEmbeddingPath=embedding.absolutePath,durationMillis=imported.durationMillis)
            }}
            created.fold(onSuccess={voice->dao.insertVoice(voice);stopBusyTicker();_uiState.update{it.copy(busy=false,selectedVoiceId=voice.voiceId,status="Voice imported",error=null)}},onFailure={e->stopBusyTicker();_uiState.update{it.copy(busy=false,status="Voice import failed",error=e.message ?: "Voice import failed")}})
        }
    }

'''
once(anchor,fn+anchor,'viewmodel')
ui='    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->\n        hasPermission = granted\n        if (granted) viewModel.startVoiceRecording()\n    }\n'
once(ui,ui+'    val importAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->\n        if (uri != null) { viewModel.importVoiceReference(uri, voiceName); voiceName = "" }\n    }\n','picker')
marker='                if (state.supportsCloning == false) {\n'
button='''                OutlinedButton(onClick = { importAudioLauncher.launch(arrayOf("audio/*")) }, enabled = !state.busy && !recorderState.isRecording, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import audio")
                }
'''
once(marker,button+marker,'button')
p.write_text(s,encoding='utf-8'); print('PATCH OK')
