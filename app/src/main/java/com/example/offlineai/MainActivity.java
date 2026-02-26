    package com.example.offlineai;

    import android.Manifest;
    import android.app.Activity;
    import android.content.BroadcastReceiver;
    import android.content.Context;
    import android.content.Intent;
    import android.content.IntentFilter;
    import android.content.pm.PackageManager;
    import android.media.AudioAttributes;
    import android.media.AudioManager;
    import android.media.ToneGenerator;
    import android.net.Uri;
    import android.os.Build;
    import android.os.Bundle;
    import android.os.Handler;
    import android.os.Looper;
    import android.speech.tts.TextToSpeech;
    import android.speech.tts.UtteranceProgressListener;
    import android.util.Log;
    import android.webkit.JavascriptInterface;
    import android.webkit.ValueCallback;
    import android.webkit.WebChromeClient;
    import android.webkit.WebSettings;
    import android.webkit.WebView;
    import android.webkit.WebViewClient;
    import android.widget.Toast;

    import androidx.annotation.Keep;
    import androidx.annotation.NonNull;
    import androidx.annotation.Nullable;
    import androidx.appcompat.app.AppCompatActivity;
    import androidx.core.app.ActivityCompat;
    import androidx.core.content.ContextCompat;

    import com.google.mediapipe.tasks.genai.llminference.LlmInference;
    import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession;

    import com.whispertflite.asr.Recorder;
    import com.whispertflite.asr.Whisper;
    import com.whispertflite.utils.WaveUtil;

    import org.json.JSONArray;
    import org.json.JSONObject;

    import java.io.BufferedReader;
    import java.io.File;
    import java.io.FileOutputStream;
    import java.io.InputStream;
    import java.io.InputStreamReader;
    import java.net.HttpURLConnection;
    import java.net.URL;
    import java.util.*;
    import java.util.concurrent.CountDownLatch;
    import java.util.concurrent.TimeUnit;
    import java.util.concurrent.atomic.AtomicBoolean;


    public class MainActivity extends AppCompatActivity implements LicenseManager.LicenseCallback {

        private static final String TAG = "OfflineAI";
        private static final int PERM_REQUEST_MIC = 777;
        private static final int REQ_FILE_CHOOSER = 1001;

        private static final String DEFAULT_LLM_URL = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task";
        private static final String WHISPER_MODEL_URL = "https://model12323.s3.ap-south-1.amazonaws.com/whisper-base.en.tflite";

        // License enforcement
        private LicenseManager licenseManager;
        private volatile boolean licenseValid = false;
        private volatile boolean licenseCheckInProgress = false;

        private WebView web;
        private TextToSpeech tts;
        private ToneGenerator toneGen;
        private volatile boolean callActive = false;

        private volatile boolean sttReady = false;
        private volatile boolean llmReady = false;
        private volatile String currentMode = "voice";
        private volatile boolean sttActive = false;
        private volatile boolean ttsSpeaking = false;
        private volatile boolean inConversation = false;
        private volatile int studyGapToken = 0;
        private volatile int normalGapToken = 0;

        private volatile boolean ttsReady = false;
        private boolean ttsInitializing = false;
        private String pendingTtsText;
        private volatile boolean isRepeating = false;

        private float speechRate = 1.0f;

        private Whisper whisper;
        private Recorder recorder;
        private File dataDir;
        private final Handler ui = new Handler(Looper.getMainLooper());
        private GoogleLlmManager llmManager;
        private ValueCallback<Uri[]> filePathCallback;

        private volatile boolean responsePaused = false;

        // App Settings
        private boolean allowQuestionRepeat = false;
        private boolean autoPause = false;
        private int pauseGap = 5;
        private int wordsGap = 6;
        private String pendingUtteranceId = null;
        private Bundle pendingUtteranceParams = null;

        private String lastGeneratedResponse = "";
        private List<String> responseChunks = new ArrayList<>();
        private int currentChunkIndex = 0;

        // Study Mode State
        private volatile boolean studyModeActive = false;
        private volatile StudyModeSession studyModeSession;
        private static final int STUDY_MODE_WAIT_SECONDS = 4;

        // Study Mode Timeout
        private Handler studyModeTimeoutHandler = new Handler(Looper.getMainLooper());

        // TTS callback types for study mode
        private static final String CALLBACK_SEGMENT = "segment";
        private static final String CALLBACK_NOTE = "note";
        private static final String CALLBACK_CONTENT = "content";

        /* ===================== STUDY MODE CLASSES ===================== */

        public static class StudySegment {
            public String id;
            public String name;
            public List<StudyNote> notes;

            public StudySegment(String id, String name) {
                this.id = id;
                this.name = name;
                this.notes = new ArrayList<>();
            }

            public JSONObject toJson() {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("id", id);
                    obj.put("name", name);

                    JSONArray notesArray = new JSONArray();
                    for (StudyNote note : notes) {
                        notesArray.put(note.toJson());
                    }
                    obj.put("notes", notesArray);
                    return obj;
                } catch (Exception e) {
                    return new JSONObject();
                }
            }
        }
        public static class StudyNote {
            public String id;
            public String name;
            public String content;

            public StudyNote(String id, String name, String content) {
                this.id = id;
                this.name = name;
                this.content = content;
            }

            public JSONObject toJson() {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("id", id);
                    obj.put("name", name);
                    obj.put("content", content);
                    return obj;
                } catch (Exception e) {
                    return new JSONObject();
                }
            }
        }

        public class StudyModeSession {
            private List<StudySegment> segments;
            private int currentSegmentIndex = 0;
            private int currentNoteIndex = 0;
            private boolean inSegmentSelection = true;
            private boolean inNoteSelection = false;
            private boolean readingNoteContent = false;
            private void debugStudyModeState() {
                Log.i(TAG, "=== STUDY MODE DEBUG ===");
                Log.i(TAG, "Active: " + studyModeActive);
                Log.i(TAG, "Session: " + (studyModeSession != null));
                Log.i(TAG, "In Conversation: " + inConversation);
                Log.i(TAG, "TTS Speaking: " + ttsSpeaking);
                Log.i(TAG, "STT Active: " + sttActive);

                if (studyModeSession != null) {
                    Log.i(TAG, "Current Segment: " + studyModeSession.currentSegmentIndex);
                    Log.i(TAG, "Current Note: " + studyModeSession.currentNoteIndex);
                    Log.i(TAG, "In Segment Selection: " + studyModeSession.inSegmentSelection);
                    Log.i(TAG, "In Note Selection: " + studyModeSession.inNoteSelection);
                    Log.i(TAG, "Reading Content: " + studyModeSession.readingNoteContent);
                }
            }
            public StudyModeSession(List<StudySegment> segments) {
                this.segments = segments;
            }

            public void start() {
                Log.i(TAG, "StudyModeSession.start() called with " + segments.size() + " segments");

                // 🔑 MUST be set FIRST
                studyModeActive = true;
                inConversation = true;

                // 🔒 Stop wake word immediately
                WakeWordService.pauseListening(MainActivity.this);

                MainActivity.this.debugAudioState("StudyModeSession.start");
                debugStudyModeState();

                // Debug: Check if segments exist
                for (int i = 0; i < segments.size(); i++) {
                    StudySegment seg = segments.get(i);
                    Log.i(TAG, "Segment " + i + ": " + seg.name + " with " + seg.notes.size() + " notes");
                }

                // Reset session state
                currentSegmentIndex = 0;
                currentNoteIndex = 0;
                inSegmentSelection = true;
                inNoteSelection = false;
                readingNoteContent = false;

                ui.post(() -> {
                    String startMessage = "Study mode activated. Let's begin.";
                    Log.i(TAG, "Speaking start message: " + startMessage);

                    // 🔥 EMERGENCY FIX: Use regular speak instead of speakForStudyMode for testing
                    Log.i(TAG, "TEST: Using regular speak() instead of speakForStudyMode");
                    MainActivity.this.speak(startMessage);

                    // 🛟 AGGRESSIVE FAILSAFE - Go directly to first segment after 1.5 seconds
                    ui.postDelayed(() -> {
                        if (studyModeActive && studyModeSession == this &&
                                inSegmentSelection && currentSegmentIndex == 0) {

                            Log.w(TAG, "AGGRESSIVE Failsafe: Skipping start message, going to first segment");
                            nextSegment();
                        }
                    }, 1500); // Only 1.5 seconds wait
                });
            }



            private void nextSegment() {
                Log.i(TAG, "nextSegment called, index: " + currentSegmentIndex + ", total: " + segments.size());

                if (currentSegmentIndex >= segments.size()) {
                    Log.i(TAG, "All segments completed, ending study mode");
                    endStudyMode();
                    return;
                }

                StudySegment segment = segments.get(currentSegmentIndex);
                inSegmentSelection = true;
                inNoteSelection = false;
                readingNoteContent = false;

                Log.i(TAG, "Presenting segment: " + segment.name + " with " + segment.notes.size() + " notes");

                ui.post(() -> {
                    String segmentMessage = "Segment: " + segment.name + ". Do you want to study this segment? Say yes or no.";
                    Log.i(TAG, "Speaking: " + segmentMessage);

                    // 🔥 Use MainActivity instance directly
                    MainActivity.this.speakForStudyMode(segmentMessage, CALLBACK_SEGMENT, null);
                });
            }

            private void startNoteSelection(StudySegment segment) {
                Log.i(TAG, "startNoteSelection called, note index: " + currentNoteIndex + ", total notes: " + segment.notes.size());

                if (currentNoteIndex >= segment.notes.size()) {
                    // Finished all notes in this segment
                    Log.i(TAG, "Finished all notes in segment: " + segment.name);
                    currentSegmentIndex++;
                    inNoteSelection = false;
                    nextSegment();
                    return;
                }
                StudyNote note = segment.notes.get(currentNoteIndex);
                readingNoteContent = false;

                Log.i(TAG, "Presenting note: " + note.name);

                ui.post(() -> {
                    String noteMessage = "Note: " + note.name + ". Do you want to study this note? Say yes or no.";
                    Log.i(TAG, "Speaking: " + noteMessage);

                    // Use the callback version for study mode
                    MainActivity.this.speakForStudyMode(noteMessage, CALLBACK_NOTE, note.id);
                });
            }
            private void readNoteContent(StudyNote note) {
                Log.i(TAG, "Reading note content: " + note.name);
                Log.i(TAG, "AutoPause setting: " + MainActivity.this.autoPause);

                studyModeSession.readingNoteContent = true;

                ui.post(() -> {
                    // Use the enhanced speakForStudyMode which now supports autoPause
                    MainActivity.this.speakForStudyMode(note.content, CALLBACK_CONTENT, note.id);
                });
            }

            private void listenForSegmentResponse(StudySegment segment) {
                Log.i(TAG, "Listening for response on segment: " + segment.name);

                // Add beep after question is asked
                ui.postDelayed(() -> {
                    beepSingle(); // 🎯 ADD THIS BEEP

                    ui.postDelayed(() -> {
                        MainActivity.this.listenForCommand(STUDY_MODE_WAIT_SECONDS * 1000, new MainActivity.CommandCallback() {
                            @Override
                            public void onResult(String result) {
                                String response = result.toLowerCase().trim();
                                Log.i(TAG, "Segment response received: " + response);

                                // 🔥 FIX: Add beep to acknowledge user response
                                MainActivity.this.beepSingle();

                                ui.post(() -> {
                                    boolean wantsToStudy = response.contains("yes") || response.contains("yeah") ||
                                            response.contains("sure") || response.contains("okay");

                                    if (wantsToStudy && !segment.notes.isEmpty()) {
                                        Log.i(TAG, "User wants to study segment: " + segment.name);
                                        currentNoteIndex = 0;
                                        inSegmentSelection = false;
                                        inNoteSelection = true;
                                        startNoteSelection(segment);
                                    } else if (response.contains("no") || response.contains("skip") || response.trim().isEmpty()) {
                                        Log.i(TAG, "User skipping segment: " + segment.name);
                                        // Move to next segment
                                        currentSegmentIndex++;
                                        nextSegment();
                                    } else {
                                        // Didn't understand, repeat the question
                                        MainActivity.this.speakForStudyMode(
                                                "I didn't understand. Do you want to study this segment? Say yes or no.",
                                                CALLBACK_SEGMENT,
                                                null
                                        );
                                    }
                                });
                            }
                        });
                    }, 300);
                }, 500); // Wait after the question
            }

            private void listenForNoteResponse(StudySegment segment, StudyNote note) {
                Log.i(TAG, "Listening for response on note: " + note.name);

                // Add beep after question is asked
                ui.postDelayed(() -> {
                    beepSingle(); // 🎯 ADD THIS BEEP

                    ui.postDelayed(() -> {
                        MainActivity.this.listenForCommand(STUDY_MODE_WAIT_SECONDS * 1000, new MainActivity.CommandCallback() {
                            @Override
                            public void onResult(String result) {
                                String response = result.toLowerCase().trim();
                                Log.i(TAG, "Note response received: " + response);

                                // 🔥 FIX: Add beep to acknowledge user response
                                MainActivity.this.beepSingle();

                                boolean wantsToStudy = response.contains("yes");

                                if (wantsToStudy && !note.content.isEmpty()) {
                                    Log.i(TAG, "User wants to study note: " + note.name);
                                    readingNoteContent = true;
                                    readNoteContent(note);
                                } else {
                                    Log.i(TAG, "User skipping note: " + note.name);
                                    // Move to next note
                                    currentNoteIndex++;
                                    startNoteSelection(segment);
                                }
                            }
                        });
                    }, 300);
                }, 500);
            }
        }

        // Study Mode Timeout Methods
        private void startStudyModeTimeout() {
            studyModeTimeoutHandler.removeCallbacksAndMessages(null);
            studyModeTimeoutHandler.postDelayed(() -> {
                if (studyModeActive) {
                    Log.w(TAG, "Study mode timeout - resetting");
                    endStudyMode();
                    speak("Study mode timed out. Returning to normal mode.");
                }
            }, 60000); // 60 second timeout
        }

        private void resetStudyModeTimeout() {
            studyModeTimeoutHandler.removeCallbacksAndMessages(null);
            if (studyModeActive) {
                startStudyModeTimeout();
            }
        }

        public void endStudyMode() {
            ui.post(() -> {
                Log.i(TAG, "Ending study mode");
                studyModeActive = false;
                studyModeSession = null;
                inConversation = false;

                speak("Study mode completed. Returning to normal mode.");

                studyModeTimeoutHandler.removeCallbacksAndMessages(null);

                WakeWordService.resumeWakeMode(MainActivity.this);
            });
        }


        private volatile long lastCommandTime = 0;
        private static final long COMMAND_COOLDOWN_MS = 1000; // 1 second
        private String lastProcessedCommand = "";
            /* ===================== RECEIVERS ===================== */
            private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String cmd = intent.getStringExtra("cmd");
                    if (cmd == null) return;

                    Log.i(TAG, "🎯 COMMAND RECEIVED: '" + cmd +
                            "', StudyModeActive: " + studyModeActive +
                            ", TTS Speaking: " + ttsSpeaking +
                            ", Response Paused: " + responsePaused +
                            ", In Conversation: " + inConversation);

                    String lowerCmd = cmd.toLowerCase().trim();
                    long now = System.currentTimeMillis();
                    if (now - lastCommandTime < COMMAND_COOLDOWN_MS &&
                            lastProcessedCommand.equals(lowerCmd)) {
                        Log.i(TAG, "Skipping duplicate command: " + lowerCmd);
                        return;
                    }

                    // Skip if we're already handling this state
                    if (responsePaused && lowerCmd.contains("pause")) {
                        Log.i(TAG, "Already paused, ignoring duplicate pause");
                        return; // 🚫 CRITICAL: Don't speak!
                    }

                    lastCommandTime = now;
                    lastProcessedCommand = lowerCmd;
                    // Check for study mode command
                    if (lowerCmd.contains("study mode")) {
                        if (!studyModeActive) {
                            Log.i(TAG, "Study mode command received, activating");
                            inConversation = true;
                            WakeWordService.lockForTTS(MainActivity.this);

                            // Fetch segments from JS and start study mode
                            ui.postDelayed(() -> {
                                web.evaluateJavascript("window.getStudySegments()", value -> {
                                    try {
                                        Log.i(TAG, "Raw JSON from JS: " + value);

                                        // Handle null or undefined
                                        if (value == null || value.equals("null") || value.equals("undefined")) {
                                            Log.e(TAG, "No study segments returned from JS");
                                            ui.post(() -> {
                                                speak("No study materials found. Please add segments and notes first.");
                                                studyModeActive = false;
                                                inConversation = false;
                                                WakeWordService.resumeWakeMode(MainActivity.this);
                                            });
                                            return;
                                        }

                                        // Remove JSON escaping
                                        String cleanValue = value;
                                        if (cleanValue.startsWith("\"") && cleanValue.endsWith("\"")) {
                                            cleanValue = cleanValue.substring(1, cleanValue.length() - 1);
                                            cleanValue = cleanValue.replace("\\\"", "\"");
                                        }

                                        Log.i(TAG, "Cleaned JSON: " + cleanValue);

                                        JSONArray segmentsArray = new JSONArray(cleanValue);
                                        List<StudySegment> segments = new ArrayList<>();

                                        for (int i = 0; i < segmentsArray.length(); i++) {
                                            JSONObject segObj = segmentsArray.getJSONObject(i);
                                            StudySegment segment = new StudySegment(
                                                    segObj.getString("id"),
                                                    segObj.getString("name")
                                            );

                                            JSONArray notesArray = segObj.getJSONArray("notes");
                                            for (int j = 0; j < notesArray.length(); j++) {
                                                JSONObject noteObj = notesArray.getJSONObject(j);
                                                segment.notes.add(new StudyNote(
                                                        noteObj.getString("id"),
                                                        noteObj.getString("name"),
                                                        noteObj.getString("content")
                                                ));
                                            }
                                            segments.add(segment);
                                        }

                                        Log.i(TAG, "Loaded " + segments.size() + " segments with total notes: " +
                                                segments.stream().mapToInt(s -> s.notes.size()).sum());

                                        if (!segments.isEmpty()) {
                                            studyModeSession = new StudyModeSession(segments);
                                            startStudyModeTimeout(); // Start timeout
                                            ui.postDelayed(() -> {
                                                Log.i(TAG, "Starting study mode session");
                                                studyModeSession.start();
                                            }, 1000);
                                        } else {
                                            ui.post(() -> {
                                                speak("No study materials found. Please add segments and notes first.");
                                                studyModeActive = false;
                                                inConversation = false;
                                                WakeWordService.resumeWakeMode(MainActivity.this);
                                            });
                                        }
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error parsing study segments", e);
                                        e.printStackTrace();
                                        ui.post(() -> {
                                            speak("Error loading study materials. Please try again.");
                                            studyModeActive = false;
                                            inConversation = false;
                                            WakeWordService.resumeWakeMode(MainActivity.this);
                                        });
                                    }
                                });
                            }, 1500); // Wait for TTS to finish before loading
                        } else {
                            Log.i(TAG, "Study mode already active, ignoring command");
                        }
                        return;
                    }

                    // 🔥 FIXED: Handle ALL commands during study mode with partial matching
                    if (studyModeActive) {
                        Log.i(TAG, "Passing command to study mode handler: " + lowerCmd);
                        handleStudyModeCommand(lowerCmd);
                        return;
                    }

                    // Handle other commands only if not in study mode
                    // 🔥 FIXED: Use partial matching for general mode commands too
                    /* ===================== PAUSE/RESUME COMMANDS ===================== */
                    if (lowerCmd.contains("pause")) {
                        Log.i(TAG, "General mode pause command - TTS Speaking: " + ttsSpeaking +
                                ", In Conversation: " + inConversation +
                                ", Response Paused: " + responsePaused);

                        // Beep first to acknowledge command
                        beepSingle();

                        if ((ttsSpeaking || inConversation) && !responsePaused){
                            responsePaused = true;
                            safeStopTTS();
                            WakeWordService.resumeCommandMode(MainActivity.this);
                            Log.i(TAG, "General mode paused");

                            // 🔥 FIX: Don't speak anything - just pause silently
                            // Don't set up listening - let the gap listening handle next commands
                        } else if (responsePaused) {
                            // Already paused - don't do anything
                            Log.i(TAG, "Already paused");
                        } else {
                            // Nothing is playing - don't do anything
                            Log.i(TAG, "Nothing is playing to pause");
                        }
                        return;
                    } else if (lowerCmd.contains("resume") || lowerCmd.contains("continue")) {
                        if (responsePaused && !ttsSpeaking) {
                            responsePaused = false;
                            // 🔥 FIX: Don't speak - just resume silently
                            Log.i(TAG, "✅ Resuming from pause");
                            if (autoPause && currentChunkIndex < responseChunks.size() && !lastGeneratedResponse.isEmpty()) {
                                Log.i(TAG, "Resuming with autoPause from chunk: " + currentChunkIndex);
                                playNextChunk();
                            } else if (!lastGeneratedResponse.isEmpty()) {
                                Log.i(TAG, "Resuming full response");
                                restartResponse();
                            }
                        } else {
                            Log.i(TAG, "Not paused");
                        }
                        return;
                    } else if (lowerCmd.contains("repeat")) {
                        if (!lastGeneratedResponse.isEmpty() && inConversation) {
                            isRepeating = true;
                            responsePaused = false;
                            safeStopTTS();
                            // 🔥 FIX: Don't speak - just repeat silently
                            Log.i(TAG, "✅ Repeating");
                            if (autoPause && currentChunkIndex > 0) {
                                currentChunkIndex = Math.max(0, currentChunkIndex - 1);
                                playNextChunk();
                            } else {
                                repeatLastSentences();
                            }
                        } else {
                            Log.i(TAG, "Nothing to repeat");
                        }
                        return;
                    } else if (lowerCmd.contains("restart")) {
                        if (!lastGeneratedResponse.isEmpty() && inConversation) {
                            responsePaused = false;
                            // 🔥 FIX: Don't speak - just restart silently
                            Log.i(TAG, "✅ Restarting");
                            restartResponse();
                        } else {
                            Log.i(TAG, "Nothing to restart");
                        }
                        return;
                    } else if (lowerCmd.contains("new") || lowerCmd.contains("question")) {
                        responsePaused = false;
                        safeStopTTS();
                        currentChunkIndex = 0;
                        responseChunks.clear();
                        // 🔥 FIX: Don't speak - just start STT silently
                        Log.i(TAG, "✅ New question mode");
                        beepSingle();
                        startSTTFromWake();
                        return;
                    } else if (lowerCmd.contains("stop") || lowerCmd.contains("exit") || lowerCmd.contains("end")) {
                        // Handle stop/exit command
                        if (inConversation || ttsSpeaking) {
                            speak("Stopping conversation.");
                            resetConversationState();
                            WakeWordService.resumeWakeMode(MainActivity.this);
                            Log.i(TAG, "Conversation stopped");
                        } else {
                            speak("Not in a conversation.");
                        }
                        return;
                    } else {
                        Log.i(TAG, "Unknown general mode command: " + lowerCmd);
                        // Don't speak for unknown commands to avoid interrupting
                    }
                }
            };
    //    };

        // 🔥 ADD: Helper method to get current note ID
        private String getCurrentNoteId() {
            if (studyModeSession != null &&
                    studyModeSession.currentSegmentIndex < studyModeSession.segments.size()) {

                StudySegment seg = studyModeSession.segments.get(studyModeSession.currentSegmentIndex);
                if (studyModeSession.currentNoteIndex < seg.notes.size()) {
                    return seg.notes.get(studyModeSession.currentNoteIndex).id;
                }
            }
            return null;
        }
        private volatile boolean awaitingPausedCommand = false;

        // 🔥 FIXED: Updated handleStudyModeCommand with partial matching and better logic
        private void handleStudyModeCommand(String lowerCmd) {
            Log.i(TAG, "🎯 STUDY MODE COMMAND: '" + lowerCmd +
                    "', Reading Content: " + (studyModeSession != null && studyModeSession.readingNoteContent) +
                    ", TTS Speaking: " + ttsSpeaking +
                    ", Response Paused: " + responsePaused);

            // 🔒 Ignore commands spoken by TTS itself - allow during gaps!
            // But if we're in a gap (ttsSpeaking = false), allow commands to execute
            // Only skip if TTS is actively speaking
            if (ttsSpeaking && !responsePaused) {
                Log.i(TAG, "Ignoring command during TTS - not paused yet");
                return;
            }

            // 🔥 FIX: Don't call handleMagicWordCommand for study mode!
            // Study mode has its own command handlers below
            /* ===================== PAUSE ===================== */
            if (lowerCmd.contains("pause")) {
                Log.i(TAG, "🎯 PAUSE COMMAND in study mode - TTS Speaking: " + ttsSpeaking +
                        ", Reading Content: " + (studyModeSession != null && studyModeSession.readingNoteContent));

                // Check if we're actually playing something
                boolean isPlayingSomething = ttsSpeaking ||
                        (studyModeSession != null && studyModeSession.readingNoteContent);

                if (!responsePaused && isPlayingSomething) {
                    try {
                        responsePaused = true;
                        awaitingPausedCommand = true;

                        // Hard stop everything
                        safeStopTTS();
                        sttActive = false;

                        // 🔥 FIX: Reset timeout on user interaction
                        resetStudyModeTimeout();

                        // 🔥 FIX: Add beep for command acknowledgment
                        beepSingle();

                        // 🔥 FIX: Don't speak "Paused." - just pause silently
                        Log.i(TAG, "✅ Study mode PAUSED");

                        // Listen immediately for next command (resume, repeat, etc.)
                        listenForPausedCommandOnce();
                    } catch (Exception e) {
                        Log.e(TAG, "Error in pause command", e);
                        responsePaused = false;
                    }
                } else if (responsePaused) {
                    // Already paused - don't do anything
                    Log.i(TAG, "Already paused");
                } else {
                    // Nothing to pause
                    Log.i(TAG, "Nothing is playing to pause");
                }
                return;
            }

            /* ===================== RESUME / CONTINUE ===================== */
            if (lowerCmd.contains("continue") || lowerCmd.contains("resume")) {
                // 🔥 FIX: Only resume if actually paused
                if (responsePaused && !responseChunks.isEmpty()) {
                    try {
                        responsePaused = false;
                        awaitingPausedCommand = false;

                        // 🔥 FIX: Reset timeout on user interaction
                        resetStudyModeTimeout();

                        // 🔥 FIX: Add beep for command acknowledgment
                        beepSingle();

                        // Continue to next chunk (don't repeat, continue forward)
                        final String noteId = getCurrentNoteId();

                        // 🔥 FIX: Don't speak - just resume silently and immediately
                        Log.i(TAG, "✅ Study mode RESUMING");
                        playStudyNextChunk(noteId);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in resume command", e);
                        responsePaused = false;
                    }
                } else {
                    Log.i(TAG, "Cannot resume - not paused or no content to play");
                }
                return;
            }

            /* ===================== REPEAT ===================== */
            if (lowerCmd.contains("repeat")) {
                // 🔥 FIX: Repeat ONLY works when paused! Not during active playback
                // This prevents repeat from being called during the gap and continuing playback
                if (responsePaused && !responseChunks.isEmpty()) {

                    try {
                        // 🔥 FIX: MUST set responsePaused = false to allow playStudyNextChunk() to execute!
                        // Otherwise playStudyNextChunk() will return early without playing
                        responsePaused = false;
                        awaitingPausedCommand = false;

                        // 🔥 FIX: Reset timeout on user interaction
                        resetStudyModeTimeout();

                        // 🔥 FIX: Go back 1 chunk to replay the last completed chunk
                        // currentChunkIndex is already at the next chunk, so -1 goes back to the one we just played
                        currentChunkIndex = Math.max(0, currentChunkIndex - 1);
                        final String noteId = getCurrentNoteId();

                        // 🔥 FIX: Add beep for command acknowledgment
                        beepSingle();

                        // 🔥 FIX: Don't speak - just repeat silently and immediately
                        Log.i(TAG, "✅ Study mode REPEATING (responsePaused=" + responsePaused + ")");
                        playStudyNextChunk(noteId);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in repeat command", e);
                        responsePaused = false;
                    }
                } else {
                    Log.i(TAG, "Nothing to repeat - not paused");
                }
                return;
            }

            /* ===================== SKIP / NEXT ===================== */
            if (lowerCmd.contains("skip") || lowerCmd.contains("next")) {
                awaitingPausedCommand = false;
                responsePaused = false;
                safeStopTTS();

                if (studyModeSession != null) {
                    if (studyModeSession.readingNoteContent) {
                        // 🔥 FIX: Don't speak - just skip silently
                        Log.i(TAG, "✅ Study mode SKIPPING");
                        studyModeSession.currentNoteIndex++;
                    }

                    ui.postDelayed(() -> {
                        if (studyModeActive && studyModeSession != null) {
                            if (studyModeSession.currentNoteIndex <
                                    studyModeSession.segments.get(studyModeSession.currentSegmentIndex).notes.size()) {
                                studyModeSession.startNoteSelection(
                                        studyModeSession.segments.get(studyModeSession.currentSegmentIndex)
                                );
                            } else {
                                studyModeSession.currentSegmentIndex++;
                                studyModeSession.inNoteSelection = false;
                                studyModeSession.nextSegment();
                            }
                        }
                    }, 300);
                }
                return;
            }

            /* ===================== RESTART ===================== */
            if (lowerCmd.contains("restart")) {
                if (studyModeSession != null && studyModeSession.readingNoteContent) {
                    awaitingPausedCommand = false;
                    responsePaused = false;
                    currentChunkIndex = 0;

                    final String noteId = getCurrentNoteId();
                    // 🔥 FIX: Don't speak - just restart silently and immediately
                    Log.i(TAG, "✅ Study mode RESTARTING");
                    playStudyNextChunk(noteId);
                } else {
                    Log.i(TAG, "Nothing to restart");
                }
                return;
            }

            /* ===================== BACK ===================== */
            if (lowerCmd.contains("back") || lowerCmd.contains("previous")) {
                awaitingPausedCommand = false;
                responsePaused = false;
                safeStopTTS();

                if (studyModeSession != null) {
                    if (studyModeSession.readingNoteContent) {
                        studyModeSession.readingNoteContent = false;
                        // 🔥 FIX: Don't speak - just go back silently
                        Log.i(TAG, "✅ Study mode GOING BACK");

                        ui.postDelayed(() ->
                                studyModeSession.startNoteSelection(
                                        studyModeSession.segments.get(studyModeSession.currentSegmentIndex)
                                ), 300);
                    }
                }
                return;
            }

            /* ===================== NEW QUESTION ===================== */
            if (lowerCmd.contains("new") || lowerCmd.contains("question")) {
                awaitingPausedCommand = false;
                responsePaused = false;
                safeStopTTS();
                currentChunkIndex = 0;
                responseChunks.clear();

                // 🔥 FIX: Exit study mode and ask new question
                Log.i(TAG, "✅ Study mode NEW QUESTION - exiting study mode");
                endStudyMode();

                // After exiting study mode, start listening for new question
                ui.postDelayed(() -> {
                    beepSingle();
                    startSTTFromWake();
                }, 300);
                return;
            }

            /* ===================== EXIT STUDY MODE ===================== */
            if ((lowerCmd.contains("exit") || lowerCmd.contains("stop") || lowerCmd.contains("end"))
                    && lowerCmd.contains("study")) {
                awaitingPausedCommand = false;
                // 🔥 FIX: Don't speak - just exit silently
                Log.i(TAG, "✅ Study mode EXITING");
                endStudyMode();
                return;
            }

            /* ===================== UNKNOWN ===================== */
            speak("I didn't understand. You can say resume, repeat, skip, restart, back, or exit.");
        }


        // 🔥 NEW: Method to handle study mode commands

        private void listenForPausedCommandOnce() {
            if (!responsePaused || !inConversation) return;

            // Cancel any existing listeners
            ui.removeCallbacksAndMessages(null);

            Log.i(TAG, "🎧 Listening ONCE for paused command (5s timeout)");

            listenForCommand(5000, result -> {
                sttActive = false;

                if (result == null || result.trim().isEmpty()) {
                    Log.i(TAG, "No command heard after pause");
                    // Don't listen again - user needs to say wake word
                    return;
                }

                String cmd = result.toLowerCase().trim();
                Log.i(TAG, "🎙 Command after pause: " + cmd);

                // Process the command
                handleResumeCommand(cmd);
            });
        }
        private void testTTS() {
            Log.i(TAG, "Testing TTS...");
            if (tts != null && ttsReady) {
                Log.i(TAG, "TTS is ready, testing speech");
                tts.speak("Testing TTS. Can you hear me?", TextToSpeech.QUEUE_FLUSH, null, "test");
            } else {
                Log.e(TAG, "TTS is not ready - Ready: " + ttsReady + ", TTS object: " + (tts != null));
            }
        }

        private void initializeAudio() {
            // Initialize Bluetooth audio if permissions granted
            bluetoothAudioManager.initialize();

            // Continue with existing initialization - LLM init is handled by enforceLicenseAndInitLLM()
            // Only initialize Whisper here (STT doesn't require license)
            new Thread(() -> {
                copyAssetFlat("models/filters_vocab_en.bin", "filters_vocab_en.bin");
                File whisperModel = new File(dataDir, "whisper-base.en.tflite");
                if (whisperModel.exists()) {
                    ui.post(() -> initWhisper());
                }
            }).start();
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                               @NonNull int[] grantResults) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);

            if (permissionManager.handlePermissionResult(requestCode, permissions, grantResults)) {
                initializeAudio();
            } else {
                Toast.makeText(this, "Some permissions were denied. App may not work fully.", Toast.LENGTH_LONG).show();
            }
        }
        private BluetoothAudioManager bluetoothAudioManager;
        private PermissionManager permissionManager;
        /* ===================== LIFECYCLE ===================== */
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Log.i(TAG, "onCreate called");

            dataDir = getExternalFilesDir(null);
            toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            // Initialize permission manager
            permissionManager = new PermissionManager(this);

            // Initialize Bluetooth audio manager
            bluetoothAudioManager = new BluetoothAudioManager(this);

            // Initialize license manager FIRST
            licenseManager = new LicenseManager(this);
            licenseManager.setCallback(this);

            setupWebView();
            setupTTS();
            if (permissionManager.checkAndRequestPermissions()) {
                initializeAudio();
            } else {
                requestMicPermission(); // For backward compatibility
            }

            // Enforce license check before LLM initialization
            llmManager = new GoogleLlmManager(this);
            enforceLicenseAndInitLLM();

            new Thread(() -> {
                copyAssetFlat("models/filters_vocab_en.bin", "filters_vocab_en.bin");
                File whisperModel = new File(dataDir, "whisper-base.en.tflite");
                if (whisperModel.exists()) {
                    ui.post(() -> initWhisper());
                }
            }).start();
        }

        /* ===================== LICENSE ENFORCEMENT ===================== */

        /**
         * Enforce license check and initialize LLM only if valid
         */
        private void enforceLicenseAndInitLLM() {
            Log.i(TAG, "Enforcing license check before LLM init...");

            // Check if we need online revalidation
            if (licenseManager.needsOnlineRevalidation()) {
                Log.w(TAG, "Online revalidation required");
                licenseValid = false;
                licenseCheckInProgress = true;

                // Notify UI that license check is needed
                ui.post(() -> {
                    notifyLicenseStatus(false, "Online validation required. Please connect to internet.");
                });
                return;
            }

            // Perform local license enforcement
            if (licenseManager.enforceLicenseCheck()) {
                licenseValid = true;
                Log.i(TAG, "License valid - initializing LLM");
                initializeLLMIfLicensed();
            } else {
                licenseValid = false;
                Log.w(TAG, "License invalid - blocking LLM");
                ui.post(() -> {
                    notifyLicenseStatus(false, "Subscription required. Please renew.");
                });
            }
        }

        /**
         * Initialize LLM only if license is valid
         */
        private void initializeLLMIfLicensed() {
            if (!licenseValid) {
                Log.w(TAG, "Cannot initialize LLM - license invalid");
                return;
            }

            File modelFile = new File(getExternalFilesDir(null), "model.task");
            if (modelFile.exists() && modelFile.length() > 1000000) {
                new Thread(() -> initLlm(modelFile.getAbsolutePath())).start();
            } else {
                new Thread(() -> downloadModelAndInit(DEFAULT_LLM_URL)).start();
            }
        }

        /**
         * Validate license with email - called from UI
         */
        public void validateLicenseWithEmail(String email) {
            Log.i(TAG, "Validating license for: " + email);
            licenseCheckInProgress = true;
            licenseManager.validateLicenseFromServer(email);
        }

        /**
         * Notify JS about license status
         */
        private void notifyLicenseStatus(boolean valid, String message) {
            String jsCall = String.format(
                "if(window.onLicenseStatus) window.onLicenseStatus(%b, '%s')",
                valid, message.replace("'", "\\'")
            );
            web.evaluateJavascript(jsCall, null);
        }

        // LicenseManager.LicenseCallback implementation
        @Override
        public void onLicenseValid() {
            Log.i(TAG, "License callback: VALID");
            licenseValid = true;
            licenseCheckInProgress = false;

            ui.post(() -> {
                notifyLicenseStatus(true, "License valid");
                initializeLLMIfLicensed();
            });
        }

        @Override
        public void onLicenseInvalid(String reason) {
            Log.w(TAG, "License callback: INVALID - " + reason);
            licenseValid = false;
            licenseCheckInProgress = false;

            ui.post(() -> {
                notifyLicenseStatus(false, reason);
            });
        }

        @Override
        public void onLicenseCheckInProgress() {
            Log.i(TAG, "License callback: CHECK IN PROGRESS");
            licenseCheckInProgress = true;

            ui.post(() -> {
                notifyLicenseStatus(false, "Validating license...");
            });
        }

        @Override
        public void onNetworkRequired() {
            Log.w(TAG, "License callback: NETWORK REQUIRED");
            licenseValid = false;
            licenseCheckInProgress = false;

            ui.post(() -> {
                notifyLicenseStatus(false, "Please connect to internet to validate license.");
            });
        }

        @Override
        public void onLicenseExpired() {
            Log.w(TAG, "License callback: LICENSE EXPIRED - auto-blocking access");
            licenseValid = false;
            licenseCheckInProgress = false;
            llmReady = false;

            ui.post(() -> {
                // Stop any active call/conversation
                if (callActive) {
                    callActive = false;
                    safeStopTTS();
                    resetConversationState();
                }

                // End study mode if active
                if (studyModeActive) {
                    endStudyMode();
                }

                // Notify UI via JavaScript
                notifyLicenseStatus(false, "Your subscription has expired. Please renew to continue.");
                notifyStatus(); // Update LLM ready status

                // Call specific JS callback for expiry
                web.evaluateJavascript("if(window.onLicenseExpired) window.onLicenseExpired()", null);

                // Show toast
                Toast.makeText(MainActivity.this, "Subscription expired. Please renew.", Toast.LENGTH_LONG).show();
            });
        }

        /**
         * Check if license allows LLM operations
         */
        public boolean isLicenseValidForLLM() {
            return licenseValid && licenseManager != null && licenseManager.isLicenseValid();
        }

        // 🔥 FIXED: Updated wake receiver to allow commands during conversation
        private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Block wake if license invalid
                if (!licenseValid) {
                    Log.w(TAG, "Wake blocked - license invalid");
                    speak("Subscription required. Please renew to use voice features.");
                    return;
                }

                if (studyModeActive && responsePaused) {
                    Log.i(TAG, "🚫 Ignoring broadcast command while paused");
                    return;
                }

                Log.i(TAG, "Wake receiver triggered - TTS Speaking: " + ttsSpeaking +
                        ", STT Active: " + sttActive +
                        ", In Conversation: " + inConversation);

                // 🔥 FIX: Only block if TTS is actually speaking or STT is active
                if (ttsSpeaking) {
                    Log.i(TAG, "Not starting STT - TTS speaking or STT active");
                    return;
                }
                if (sttActive) {
                    Log.i(TAG, "Not starting STT - STT already active");
                    return;
                }

                // 🔥 FIX: Allow wake word even during conversation (for interruptions)
                toneGen.stopTone();
                beepSingle();

                ui.post(() -> Toast.makeText(MainActivity.this, "Listening…", Toast.LENGTH_SHORT).show());
                startSTTFromWake();
            }
        };

        @Override
        protected void onResume() {
            super.onResume();
            Log.i(TAG, "onResume called");

            // Start license expiry monitoring
            if (licenseManager != null) {
                licenseManager.startExpiryMonitoring();

                // Check if license expired while app was in background
                if (licenseManager.isLicenseExpiredNow()) {
                    Log.w(TAG, "License expired while app was in background");
                    licenseValid = false;
                    ui.post(() -> notifyLicenseStatus(false, "Your subscription has expired. Please renew."));
                }
                // Re-check license on resume (enforce 3-day validation)
                else if (licenseManager.needsOnlineRevalidation()) {
                    Log.w(TAG, "License revalidation needed on resume");
                    licenseValid = false;
                    ui.post(() -> notifyLicenseStatus(false, "Please validate your license."));
                }
            }

            IntentFilter filter = new IntentFilter(WakeWordService.ACTION_WAKE);
            IntentFilter cmdFilter = new IntentFilter(WakeWordService.ACTION_COMMAND);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                registerReceiver(commandReceiver, cmdFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(wakeReceiver, filter);
                registerReceiver(commandReceiver, cmdFilter);
            }
        }

        @Override
        protected void onPause() {
            Log.i(TAG, "onPause called");

            // Stop license expiry monitoring when app goes to background
            if (licenseManager != null) {
                licenseManager.stopExpiryMonitoring();
            }

            try { unregisterReceiver(wakeReceiver); } catch (Exception ignored) {}
            try { unregisterReceiver(commandReceiver); } catch (Exception ignored) {}
            super.onPause();
        }
        private void resetConversationState() {
            sttActive = false;
            ttsSpeaking = false;
            inConversation = false;
            responsePaused = false;
            isRepeating = false;
            studyModeActive = false;
            studyModeSession = null;
            currentChunkIndex = 0;
            responseChunks.clear();
            lastGeneratedResponse = "";
        }
        private void armStudyModeCommandListening(int token) {
            if (!studyModeActive) return;

            Log.i(TAG, "🎧 Arming STT for study mode commands (token=" + token + ")");

            listenForCommand(pauseGap * 1000, result -> {
                if (result == null || result.trim().isEmpty()) return;
                if (studyGapToken != token) return; // ❗ stale STT result

                String cmd = result.toLowerCase().trim();
                Log.i(TAG, "🎙 Study command detected during gap: " + cmd);

                // 🔥 CANCEL scheduled next chunk
                studyGapToken++;


            });
        }


        /* ===================== WEBVIEW ===================== */
        private void setupWebView() {
            Log.i(TAG, "Setting up WebView");
            web = new WebView(this);
            WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(true);
            ws.setAllowContentAccess(true);
            ws.setMediaPlaybackRequiresUserGesture(false);

            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    Log.i(TAG, "WebView page finished loading");
                    notifyStatus();
                }
            });

            web.setWebChromeClient(new WebChromeClient() {
                // Removed file chooser override
            });

            web.addJavascriptInterface(new JSBridge(), "AndroidBridge");
            web.loadUrl("file:///android_asset/web/index.html");
            setContentView(web);
        }

        @Override
        protected void onActivityResult(int req, int res, @Nullable Intent data) {
            super.onActivityResult(req, res, data);
            if (req == REQ_FILE_CHOOSER && filePathCallback != null) {
                Uri[] results = (res == Activity.RESULT_OK && data != null) ? WebChromeClient.FileChooserParams.parseResult(res, data) : null;
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }

        /* ===================== JS BRIDGE ===================== */
        @Keep
        public class JSBridge {
            @JavascriptInterface
            public boolean isReady() { return sttReady && llmReady; }
            @JavascriptInterface
            public void testPauseResume() {
                ui.post(() -> {
                    Log.i(TAG, "=== PAUSE/RESUME TEST ===");

                    if (inConversation && autoPause) {
                        speak("Testing pause/resume. Say 'pause' then 'resume'.");
                        // Simulate being in a gap
                        armNormalModeGapListening();
                    } else {
                        speak("Start a conversation with autoPause first.");
                    }
                });
            }
            @JavascriptInterface
            public void pauseListening() { WakeWordService.pauseListening(MainActivity.this); }
            @JavascriptInterface
            public void toggleAudioSource() {
                ui.post(() -> {
                    if (bluetoothAudioManager != null) {
                        if (bluetoothAudioManager.isBluetoothHeadsetConnected()) {
                            if (bluetoothAudioManager.isBluetoothScoActive()) {
                                bluetoothAudioManager.switchToPhone();
                                speak("Switched to phone microphone");
                            } else {
                                bluetoothAudioManager.switchToBluetooth();
                                speak("Switched to Bluetooth");
                            }
                        } else {
                            speak("No Bluetooth headset connected");
                        }
                    }
                });
            }

            @JavascriptInterface
            public String getAudioStatus() {
                if (bluetoothAudioManager == null) {
                    return "Audio manager not initialized";
                }

                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                String status = "Mode: " + am.getMode() +
                        ", Bluetooth Connected: " + bluetoothAudioManager.isBluetoothHeadsetConnected() +
                        ", Bluetooth Active: " + bluetoothAudioManager.isBluetoothScoActive() +
                        ", Speakerphone: " + am.isSpeakerphoneOn();

                return status;
            }

            @JavascriptInterface
            public void forceBluetoothAudio() {
                ui.post(() -> {
                    if (bluetoothAudioManager != null) {
                        bluetoothAudioManager.switchToBluetooth();
                        speak("Forcing Bluetooth audio mode");
                    }
                });
            }

            @JavascriptInterface
            public void forcePhoneAudio() {
                ui.post(() -> {
                    if (bluetoothAudioManager != null) {
                        bluetoothAudioManager.switchToPhone();
                        speak("Forcing phone audio mode");
                    }
                });
            }
            @JavascriptInterface
            public void resumeListening() { WakeWordService.resumeWakeMode(MainActivity.this); }
            @JavascriptInterface
            public void testStudyModeAutoPause() {
                ui.post(() -> {
                    Log.i(TAG, "Testing study mode autoPause - Current setting: " + autoPause);
                    Toast.makeText(MainActivity.this,
                            "Study Mode AutoPause: " + (autoPause ? "ON" : "OFF") +
                                    "\nWords per chunk: " + wordsGap +
                                    "\nPause gap: " + pauseGap + "s",
                            Toast.LENGTH_LONG).show();
                });
            }
            @JavascriptInterface
            public void setSpeechRate(float rate) {
                speechRate = rate;
                ui.post(() -> { if (tts != null) tts.setSpeechRate(speechRate); });
            }

            @JavascriptInterface
            public void startCall() {
                if (callActive) return;
                callActive = true;

                MainActivity.this.resetConversationState();
                startWakeService();
                WakeWordService.resumeWakeMode(MainActivity.this);
            }

            @JavascriptInterface
            public void endCall() {
                if (!callActive) return;
                callActive = false;

                safeStopTTS();
                MainActivity.this.resetConversationState();

                WakeWordService.pauseListening(MainActivity.this);
                stopWakeService();
            }

            @JavascriptInterface
            public void forceEndCallFromJS() {
                ui.post(() -> {
                    if (callActive) {
                        callActive = false;
                        WakeWordService.pauseListening(MainActivity.this);
                        stopWakeService();
                        safeStopTTS();
                        Toast.makeText(MainActivity.this,
                                "Free limit reached. Come back tomorrow or upgrade.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }



            private void stopWakeService() {
                Intent i = new Intent(MainActivity.this, WakeWordService.class);
                MainActivity.this.stopService(i);
            }

            @JavascriptInterface
            public void updateSettings(String json) {
                try {
                    JSONObject obj = new JSONObject(json);
                    allowQuestionRepeat = obj.optBoolean("allowQuestionRepeat", false);
                    autoPause = obj.optBoolean("autoPause", false);
                    pauseGap = obj.optInt("pauseGap", 5);
                    wordsGap = obj.optInt("wordsGap", 6);
                    speechRate = (float) obj.optDouble("talkingSpeed", 1.0);
                    ui.post(() -> { if (tts != null) tts.setSpeechRate(speechRate); });
                    Log.i(TAG, "Settings updated: AutoPause=" + autoPause);
                } catch (Exception e) { Log.e(TAG, "Settings update fail", e); }
            }

            @JavascriptInterface
            public void setMode(String mode) {
                currentMode = "voice";
            }

            @JavascriptInterface
            public boolean isModelDownloaded() {
                File f = new File(getExternalFilesDir(null), "model.task");
                return f.exists() && f.length() > 1000000;
            }

            /* ===================== LICENSE MANAGEMENT JS BRIDGE ===================== */

            @JavascriptInterface
            public boolean isLicenseValid() {
                return licenseValid && licenseManager != null && licenseManager.isLicenseValid();
            }

            @JavascriptInterface
            public void validateLicense(String email) {
                Log.i(TAG, "JS requested license validation for: " + email);
                ui.post(() -> validateLicenseWithEmail(email));
            }

            @JavascriptInterface
            public String getDeviceId() {
                if (licenseManager != null) {
                    return licenseManager.getDeviceId();
                }
                return "";
            }

            @JavascriptInterface
            public String getLicenseEmail() {
                if (licenseManager != null) {
                    return licenseManager.getUserEmail() != null ? licenseManager.getUserEmail() : "";
                }
                return "";
            }

            @JavascriptInterface
            public long getLicenseExpiryTime() {
                if (licenseManager != null) {
                    return licenseManager.getLicenseExpiryTime();
                }
                return 0;
            }

            @JavascriptInterface
            public long getRemainingLicenseTime() {
                if (licenseManager != null) {
                    return licenseManager.getRemainingLicenseTime();
                }
                return 0;
            }

            @JavascriptInterface
            public long getRemainingOfflineTime() {
                if (licenseManager != null) {
                    return licenseManager.getRemainingOfflineTime();
                }
                return 0;
            }

            @JavascriptInterface
            public boolean needsOnlineValidation() {
                if (licenseManager != null) {
                    return licenseManager.needsOnlineRevalidation();
                }
                return true;
            }

            @JavascriptInterface
            public void clearLicense() {
                Log.i(TAG, "JS requested license clear (logout)");
                ui.post(() -> {
                    if (licenseManager != null) {
                        licenseManager.clearLicenseData();
                        licenseValid = false;
                        llmReady = false;
                        notifyStatus();
                        // Don't show any message - let the auth screen handle it
                    }
                });
            }

            @JavascriptInterface
            public void setLicenseEmail(String email) {
                Log.i(TAG, "JS setting license email: " + email);
                if (licenseManager != null) {
                    licenseManager.setUserEmail(email);
                }
            }

            @JavascriptInterface
            public String getLicenseStatus() {
                try {
                    JSONObject status = new JSONObject();
                    status.put("valid", licenseValid);
                    status.put("deviceId", licenseManager != null ? licenseManager.getDeviceId() : "");
                    status.put("email", licenseManager != null && licenseManager.getUserEmail() != null ? licenseManager.getUserEmail() : "");
                    status.put("expiryTime", licenseManager != null ? licenseManager.getLicenseExpiryTime() : 0);
                    status.put("remainingLicenseTime", licenseManager != null ? licenseManager.getRemainingLicenseTime() : 0);
                    status.put("remainingOfflineTime", licenseManager != null ? licenseManager.getRemainingOfflineTime() : 0);
                    status.put("needsOnlineValidation", licenseManager != null ? licenseManager.needsOnlineRevalidation() : true);
                    return status.toString();
                } catch (Exception e) {
                    Log.e(TAG, "Error getting license status", e);
                    return "{}";
                }
            }

            @JavascriptInterface
            public boolean isSTTDownloaded() {
                File model = new File(dataDir, "whisper-base.en.tflite");
                File vocab = new File(dataDir, "filters_vocab_en.bin");
                return model.exists() && model.length() > 1_000_000
                        && vocab.exists() && vocab.length() > 10_000;
            }

            @JavascriptInterface
            public void startModelDownload() {
                File f = new File(getExternalFilesDir(null), "model.task");
                if (f.exists() && f.length() > 1_000_000) {
                    Log.i(TAG, "LLM already downloaded, skipping");
                    ui.post(() ->
                            web.evaluateJavascript(
                                    "if(window.onModelDownloaded) window.onModelDownloaded()", null
                            )
                    );
                    return;
                }
                new Thread(() -> downloadModelAndInit(DEFAULT_LLM_URL)).start();
            }

            @JavascriptInterface
            public void startSTTDownload() {
                new Thread(() -> {
                    try {
                        File modelFile = new File(dataDir, "whisper-base.en.tflite");
                        File vocabFile = new File(dataDir, "filters_vocab_en.bin");

                        if (modelFile.exists() && vocabFile.exists()) {
                            ui.post(() -> {
                                initWhisper();
                                web.evaluateJavascript(
                                        "if(window.onSTTDownloadDone) window.onSTTDownloadDone()", null
                                );
                            });
                        } else {
                            downloadFileWithProgress(
                                    WHISPER_MODEL_URL,
                                    modelFile,
                                    "window.onSTTDownloadProgress"
                            );

                            ui.post(() -> {
                                initWhisper();
                                web.evaluateJavascript(
                                        "if(window.onSTTDownloadDone) window.onSTTDownloadDone()", null
                                );
                            });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "STT download error", e);
                    }
                }).start();
            }
            private void handleStudySegmentsFromJs(String value) {
                try {
                    Log.i(TAG, "Raw JSON from JS: " + value);

                    if (value == null || value.equals("null") || value.equals("undefined")) {
                        speak("No study materials found.");
                        WakeWordService.resumeWakeMode(MainActivity.this);
                        return;
                    }

                    String cleanValue = value;
                    if (cleanValue.startsWith("\"") && cleanValue.endsWith("\"")) {
                        cleanValue = cleanValue.substring(1, cleanValue.length() - 1)
                                .replace("\\\"", "\"");
                    }

                    JSONArray segmentsArray = new JSONArray(cleanValue);
                    List<StudySegment> segments = new ArrayList<>();

                    for (int i = 0; i < segmentsArray.length(); i++) {
                        JSONObject segObj = segmentsArray.getJSONObject(i);
                        StudySegment segment = new StudySegment(
                                segObj.getString("id"),
                                segObj.getString("name")
                        );

                        JSONArray notesArray = segObj.getJSONArray("notes");
                        for (int j = 0; j < notesArray.length(); j++) {
                            JSONObject noteObj = notesArray.getJSONObject(j);
                            segment.notes.add(new StudyNote(
                                    noteObj.getString("id"),
                                    noteObj.getString("name"),
                                    noteObj.getString("content")
                            ));
                        }
                        segments.add(segment);
                    }

                    if (!segments.isEmpty()) {
                        studyModeSession = new StudyModeSession(segments);
                        studyModeSession.start();
                    } else {
                        speak("No study materials found.");
                        WakeWordService.resumeWakeMode(MainActivity.this);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error parsing study segments", e);
                    speak("Error loading study materials.");
                    WakeWordService.resumeWakeMode(MainActivity.this);
                }
            }


            @JavascriptInterface
            public String askWithSpeech() {
                if (!sttReady) return "STT not ready";
                final CountDownLatch latch = new CountDownLatch(1);
                final String[] resultArr = new String[1];
                System.err.println("Before thread");
                Log.i(TAG, "Before thread");

                new Thread(() -> {
                    try {
                        inConversation = true;
                        WakeWordService.pauseListening(MainActivity.this);
                        if (recorder == null) recorder = new Recorder(MainActivity.this);
                        recorder.setFilePath(new File(dataDir, WaveUtil.RECORDING_FILE).getAbsolutePath());
                        sttActive = true;
                        beepSingle();
                        recorder.start();
                        Thread.sleep(5000);
                        recorder.stop();
                        beepDouble();

                        final CountDownLatch whisperLatch = new CountDownLatch(1);
                        whisper.setListener(new Whisper.WhisperListener() {
                            @Override public void onUpdateReceived(String m) {}
                            @Override public void onResultReceived(String r) {
                                resultArr[0] = r;
                                whisperLatch.countDown();
                            }
                        });

                        whisper.setFilePath(new File(dataDir, WaveUtil.RECORDING_FILE).getAbsolutePath());
                        whisper.setAction(Whisper.ACTION_TRANSCRIBE);
                        whisper.start();
                        whisperLatch.await(30, TimeUnit.SECONDS);
                        System.out.println("fuck");

                        if (resultArr[0] != null && !resultArr[0].isEmpty()) {
                            System.out.println("fuck");
                            String userText = resultArr[0].trim();
                            Log.i(TAG, "Study mode command received, before");

                            // Check if user said "study mode"
                            if (userText.toLowerCase().contains("study mode")) {
                                resultArr[0] = "Switching to study mode";

                                // 🔒 HARD LOCK AUDIO & STOP STT
                                sttActive = false;
                                WakeWordService.pauseListening(MainActivity.this);


                                ui.post(() -> {
                                    Log.i(TAG, "Directly starting study mode (no broadcast)");

                                    if (!studyModeActive) {
                                        inConversation = true;


                                        startStudyModeTimeout();

                                        web.evaluateJavascript("window.getStudySegments()", value -> {
                                            handleStudySegmentsFromJs(value);
                                        });
                                    }
                                });


                                return; // ⬅️ DO NOT FALL THROUGH
                            }



                            if (allowQuestionRepeat) {
                                ui.post(() -> {
                                    String msg = "You said: " + userText + ". Shall I continue?";
                                    speakConfirmation(msg, userText);
                                });
                            } else {
                                handleConfirmedQuestion(userText);
                            }
                        } else {
                            resultArr[0] = "I didn't catch that.";
                            speak(resultArr[0]);
                        }
                    } catch (Exception e) { resultArr[0] = "Speech error"; }
                    finally { sttActive = false; latch.countDown(); }
                }).start();

                try { latch.await(40, TimeUnit.SECONDS); } catch (Exception ignored) {}
                return resultArr[0];
            }
        }

        /* ===================== AI FLOW LOGIC ===================== */
        private void speakConfirmation(String msg, String originalQuestion) {
            Log.i(TAG, "speakConfirmation called: " + msg);

            // FIRST: Beep to indicate listening is starting
            beepSingle();

            ui.postDelayed(() -> {
                // Create a temporary listener just for confirmation
                // We'll use a flag to track if we're in confirmation mode
                final boolean[] confirmationHandled = {false};

                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        Log.i(TAG, "Confirmation started: " + utteranceId);
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        if ("conf".equals(utteranceId) && !confirmationHandled[0]) {
                            confirmationHandled[0] = true;
                            Log.i(TAG, "Confirmation finished, setting up to listen for response");

                            ui.post(() -> {
                                // Beep to indicate we're now listening for response
                                beepSingle();

                                listenForCommand(5000, result -> {
                                    String cmd = result != null ? result.toLowerCase().trim() : "";
                                    Log.i(TAG, "Confirmation response: '" + cmd + "'");

                                    ui.post(() -> {
                                        // Beep to acknowledge we heard something
                                        beepDouble();

                                        ui.postDelayed(() -> {
                                            if (cmd.contains("yes") || cmd.contains("yeah") ||
                                                    cmd.contains("continue") || cmd.contains("go ahead") ||
                                                    cmd.contains("yes.") || cmd.contains("yes?")) {

                                                Log.i(TAG, "✅ User confirmed YES, handling question");
                                                // Reset TTS listener back to main one
                                                setupMainTTSListener();
                                                handleConfirmedQuestion(originalQuestion);
                                            } else {
                                                Log.i(TAG, "User cancelled or unclear response");
                                                speak("Okay, please ask your question again.");
                                                inConversation = false;
                                                // Reset TTS listener back to main one
                                                setupMainTTSListener();
                                                WakeWordService.resumeWakeMode(MainActivity.this);
                                            }
                                        }, 300);
                                    });
                                });
                            });
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        Log.e(TAG, "Confirmation error: " + utteranceId);
                        // Reset TTS listener back to main one on error
                        setupMainTTSListener();
                    }
                });

                // Now speak the confirmation
                int result = tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "conf");
                Log.i(TAG, "Confirmation speak result: " + result);

            }, 300);
        }
        private void setupMainTTSListener() {
            if (tts == null) return;

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    ttsSpeaking = true;
                    Log.d(TAG, "Main TTS started utterance: " + id);

                    if (id.startsWith("study_")) {
                        Log.i(TAG, "Study mode TTS started: " + id);
                    }
                }

                @Override
                public void onDone(String id) {
                    ttsSpeaking = false;
                    Log.d(TAG, "Main TTS done utterance: " + id);

                    // Skip "conf" utterances - they're handled by speakConfirmation
                    if ("conf".equals(id)) {
                        Log.i(TAG, "Confirmation utterance done - skipping in main listener");
                        return;
                    }

                    if (id.equals("utt") && responsePaused) {
                        Log.i(TAG, "✅ 'Paused.' message finished, setting up command listening");
                        ui.postDelayed(() -> listenForSingleCommandAfterPause(), 300);
                        return;
                    }

                    if (responsePaused) {
                        Log.i(TAG, "Paused — skipping gap STT completely");
                        return;
                    }

                    // 🔥 IMPORTANT: DON'T release audio focus during gaps if we're in conversation
                    if (!studyModeActive && !inConversation) {
                        releaseTtsAudioFocus();
                    }

                    if ("repeat".equals(id)) {
                        isRepeating = false;
                        beepSingle();
                        WakeWordService.unlockAfterTTS(MainActivity.this, true);
                        return;
                    }

                    if (isRepeating) {
                        return;
                    }

                    // Handle study mode chunks with autoPause
                    if (id.startsWith("study_content_") && id.contains("_chunk_") && autoPause && !responsePaused) {
                        Log.i(TAG, "Study mode chunk completed: " + id);

                        String[] parts = id.split("_");
                        String noteId = parts.length > 2 ? parts[2] : null;

                        // Check if we're at the end
                        if (currentChunkIndex >= responseChunks.size()) {
                            Log.i(TAG, "Finished all study content chunks for note: " + noteId);

                            ui.postDelayed(() -> {
                                if (studyModeActive && studyModeSession != null) {
                                    Log.i(TAG, "Auto-advancing to next note after finishing chunks");

                                    studyModeSession.currentNoteIndex++;
                                    if (studyModeSession.currentNoteIndex <
                                            studyModeSession.segments.get(studyModeSession.currentSegmentIndex).notes.size()) {
                                        studyModeSession.startNoteSelection(
                                                studyModeSession.segments.get(studyModeSession.currentSegmentIndex)
                                        );
                                    } else {
                                        studyModeSession.currentSegmentIndex++;
                                        studyModeSession.inNoteSelection = false;
                                        studyModeSession.nextSegment();
                                    }
                                }
                            }, 500);
                            return;
                        }

                        // Play next chunk after pause
                        int myToken = ++studyGapToken;
                        safeStopTTS();
                        ttsSpeaking = false;

                        armStudyModeCommandListening(myToken);

                        ui.postDelayed(() -> {
                            if (!studyModeActive) return;
                            if (responsePaused) return;
                            if (studyGapToken != myToken) return;

                            playStudyNextChunk(noteId);
                        }, pauseGap * 1000);

                        return;
                    }

                    // Handle normal mode chunks with gap listening
                    if (id.startsWith("chunk_") && autoPause && !responsePaused && inConversation) {
                        Log.i(TAG, "Normal mode chunk completed: " + id + ", chunk " +
                                currentChunkIndex + " of " + responseChunks.size());

                        if (currentChunkIndex >= responseChunks.size()) {
                            Log.i(TAG, "Finished all normal mode chunks");
                            ui.postDelayed(() -> {
                                beepSingle();
                                inConversation = false;
                                releaseTtsAudioFocus();
                                WakeWordService.resumeWakeMode(MainActivity.this);
                            }, 500);
                            return;
                        }

                        armNormalModeGapListening();
                        return;
                    }

                    // Handle study mode callbacks
                    if (id.startsWith("study_") && !id.contains("_chunk_")) {
                        Log.i(TAG, "Study mode TTS completed: " + id);

                        ui.postDelayed(() -> {
                            if (studyModeActive) {
                                String[] parts = id.split("_");
                                if (parts.length > 1) {
                                    String callbackType = parts[1];
                                    String extraData = parts.length > 2 ? parts[2] : null;

                                    if ("start".equals(callbackType)) {
                                        Log.i(TAG, "Starting first segment after start message");
                                        if (studyModeSession != null) {
                                            studyModeSession.nextSegment();
                                        }
                                    } else {
                                        handleStudyModeCallback(callbackType, extraData);
                                    }
                                }
                            }
                        }, 300);
                        return;
                    }

                    // Final cleanup
                    if (!studyModeActive && !ttsSpeaking && !id.startsWith("chunk_")) {
                        ui.postDelayed(() -> {
                            if (!ttsSpeaking) {
                                inConversation = false;
                                releaseTtsAudioFocus();
                                WakeWordService.resumeWakeMode(MainActivity.this);
                            }
                        }, 500);
                    }
                }

                @Override
                public void onError(String id) {
                    ttsSpeaking = false;
                    Log.e(TAG, "Main TTS error utterance: " + id);

                    if ("conf".equals(id)) {
                        Log.e(TAG, "Confirmation TTS error");
                        return;
                    }

                    if (!studyModeActive) {
                        releaseTtsAudioFocus();
                        inConversation = false;
                        WakeWordService.resumeWakeMode(MainActivity.this);
                    }
                }
            });
        }
        private void requestTtsAudioFocus() {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            // Set correct audio mode based on Bluetooth state
            if (bluetoothAudioManager != null && bluetoothAudioManager.isBluetoothScoActive()) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } else {
                am.setMode(AudioManager.MODE_NORMAL);
            }

            int result = am.requestAudioFocus(
                    focusChange -> {
                        Log.i(TAG, "Audio focus change: " + focusChange);
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            safeStopTTS();
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            );
            Log.i(TAG, "Audio focus request result: " + result);
        }

        private void releaseTtsAudioFocus() {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int result = am.abandonAudioFocus(null);
            Log.i(TAG, "Audio focus release result: " + result);

            // Reset audio mode
            am.setMode(AudioManager.MODE_NORMAL);
        }
        private void speakStudyContentWithAutoPause(String text, String noteId) {
            Log.i(TAG, "speakStudyContentWithAutoPause for note: " + noteId);

            // Process text into chunks based on wordsGap setting
            String[] words = text.split("\\s+");
            List<String> studyChunks = new ArrayList<>();
            StringBuilder currentChunk = new StringBuilder();

            for (int i = 0; i < words.length; i++) {
                currentChunk.append(words[i]).append(" ");
                if ((i + 1) % wordsGap == 0 || i == words.length - 1) {
                    studyChunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
            }

            Log.i(TAG, "Split study content into " + studyChunks.size() + " chunks");

            // Store in instance variables for study mode
            responseChunks = studyChunks;
            currentChunkIndex = 0;
            responsePaused = false;

            // Start playing first chunk
            playStudyNextChunk(noteId);
        }

        private void playStudyNextChunk(String noteId) {
            // 🔥 FIX: Early return if paused or not in study mode
            if (responsePaused || !studyModeActive || responseChunks == null || responseChunks.isEmpty()) {
                Log.i(TAG, "Cannot play study chunk - paused=" + responsePaused +
                       ", studyMode=" + studyModeActive +
                       ", chunks=" + (responseChunks == null ? "null" : responseChunks.size()));
                return;
            }

            // 🔥 FIX: Null check for noteId
            if (noteId == null) {
                Log.w(TAG, "Cannot play study chunk - noteId is null");
                return;
            }

            if (currentChunkIndex < responseChunks.size()) {
                String text = responseChunks.get(currentChunkIndex);
                String utteranceId = "study_content_" + noteId + "_chunk_" + currentChunkIndex;

                Log.i(TAG, "Speaking study chunk " + currentChunkIndex + "/" + responseChunks.size());

                ui.post(() -> {
                    if (tts != null && ttsReady) {
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
                    }
                });

                currentChunkIndex++;
            } else {
                // Finished reading all chunks
                Log.i(TAG, "Finished reading all study content chunks");
                ui.postDelayed(() -> {
                    // Move to next note automatically
                    if (studyModeActive && studyModeSession != null) {
                        studyModeSession.currentNoteIndex++;
                        if (studyModeSession.currentNoteIndex <
                                studyModeSession.segments.get(studyModeSession.currentSegmentIndex).notes.size()) {
                            studyModeSession.startNoteSelection(
                                    studyModeSession.segments.get(studyModeSession.currentSegmentIndex)
                            );
                        } else {
                            studyModeSession.currentSegmentIndex++;
                            studyModeSession.inNoteSelection = false;
                            studyModeSession.nextSegment();
                        }
                    }
                }, 500);
            }
        }
        private void speakForStudyMode(String text, String callbackType, String extraData) {
            Log.i(TAG, "speakForStudyMode: " + callbackType + " - " +
                    (text.length() > 50 ? text.substring(0, 50) + "..." : text));

            // 🔥 NEW: Handle autoPause for note content specifically
            if (CALLBACK_CONTENT.equals(callbackType) && autoPause && callActive) {
                Log.i(TAG, "AutoPause enabled for study mode content, chunking text");
                Log.i(TAG, "Words per chunk: " + wordsGap + ", Pause gap: " + pauseGap + "s");
                speakStudyContentWithAutoPause(text, extraData);
                return;
            }

            ui.post(() -> {
                debugAudioState("speakForStudyMode-before");

                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.setMode(AudioManager.MODE_NORMAL);

                // Check TTS state first
                if (tts == null) {
                    Log.e(TAG, "TTS is null in speakForStudyMode!");
                    setupTTS();

                    // Queue the text
                    pendingTtsText = text;
                    pendingUtteranceId = "study_" + callbackType + (extraData != null ? "_" + extraData : "");
                    return;
                }
                if (!ttsReady) {
                    Log.w(TAG, "TTS not ready in speakForStudyMode");
                    pendingTtsText = text;
                    pendingUtteranceId = "study_" + callbackType + (extraData != null ? "_" + extraData : "");
                    return;
                }

                // Set volume
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (cur < max / 2) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0);
                }

                sttActive = false;

                // Request audio focus
                requestTtsAudioFocus();

                String utteranceId = "study_" + callbackType + (extraData != null ? "_" + extraData : "");

                Log.i(TAG, "TTS SPEAKING (study): " + utteranceId);
                Log.i(TAG, "Text length: " + text.length());

                // Set speech rate
                tts.setSpeechRate(speechRate);

                // Use simple speak without params first to test
                int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
                Log.i(TAG, "tts.speak() result code = " + result);

                if (result == TextToSpeech.ERROR) {
                    Log.e(TAG, "TTS ERROR! Trying alternative approach...");

                    // Try with params
                    HashMap<String, String> params = new HashMap<>();
                    params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                    result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
                    Log.i(TAG, "Alternative speak result: " + result);
                }

                // Immediate debug check
                ui.postDelayed(() -> {
                    Log.i(TAG, "TTS status after 200ms - Speaking: " + ttsSpeaking +
                            ", for utterance: " + utteranceId);

                    if (!ttsSpeaking && "study_start".equals(utteranceId)) {
                        Log.w(TAG, "TTS didn't start for study_start, using emergency fallback");
                        emergencyStudyModeFallback();
                    }
                }, 200);
            });
        }

        private void emergencyStudyModeFallback() {
            Log.w(TAG, "EMERGENCY: Bypassing TTS completely");
            ui.post(() -> {
                if (studyModeActive && studyModeSession != null) {
                    // Directly go to first segment
                    Log.i(TAG, "Going directly to first segment");
                    studyModeSession.currentSegmentIndex = 0;
                    studyModeSession.inSegmentSelection = true;

                    if (studyModeSession.currentSegmentIndex < studyModeSession.segments.size()) {
                        StudySegment segment = studyModeSession.segments.get(studyModeSession.currentSegmentIndex);
                        String segmentMessage = "Segment: " + segment.name + ". Do you want to study this segment? Say yes or no.";

                        // Try one more time with regular speak
                        speak(segmentMessage);

                        // Set up listening for response
                        ui.postDelayed(() -> {
                            if (studyModeActive) {
                                studyModeSession.listenForSegmentResponse(segment);
                            }
                        }, 3000);
                    }
                }
            });
        }

        private void handleStudyModeCallback(String callbackType, String extraData) {

            Log.i(TAG, "Study mode callback: " + callbackType + ", extra: " + extraData);
            debugAudioState("handleStudyModeCallback");

            if (studyModeActive && studyModeSession != null) {
                resetStudyModeTimeout(); // Reset timeout on each interaction

                ui.postDelayed(() -> {
                    switch (callbackType) {
                        case "start":
                            Log.i(TAG, "Starting first segment after start message");
                            studyModeSession.nextSegment();
                            break;
                        case CALLBACK_SEGMENT:
                            if (studyModeSession.inSegmentSelection) {
                                Log.i(TAG, "Listening for segment response");
                                studyModeSession.listenForSegmentResponse(
                                        studyModeSession.segments.get(studyModeSession.currentSegmentIndex)
                                );
                            } else {
                                Log.w(TAG, "Not in segment selection state, but got segment callback");
                            }
                            break;
                        case CALLBACK_NOTE:
                            if (studyModeSession.inNoteSelection) {
                                Log.i(TAG, "Listening for note response");
                                studyModeSession.listenForNoteResponse(
                                        studyModeSession.segments.get(studyModeSession.currentSegmentIndex),
                                        studyModeSession.segments.get(studyModeSession.currentSegmentIndex)
                                                .notes.get(studyModeSession.currentNoteIndex)
                                );
                            } else {
                                Log.w(TAG, "Not in note selection state, but got note callback");
                            }
                            break;
                        case CALLBACK_CONTENT:
                            Log.i(TAG, "Note content read completed");
                            studyModeSession.currentNoteIndex++;
                            if (studyModeSession.currentNoteIndex <
                                    studyModeSession.segments.get(studyModeSession.currentSegmentIndex).notes.size()) {
                                studyModeSession.startNoteSelection(
                                        studyModeSession.segments.get(studyModeSession.currentSegmentIndex)
                                );
                            } else {
                                studyModeSession.currentSegmentIndex++;
                                studyModeSession.inNoteSelection = false;
                                studyModeSession.nextSegment();
                            }
                            break;
                        default:
                            Log.w(TAG, "Unknown callback type: " + callbackType);
                            break;
                    }
                }, 500); // Small delay after speech
            } else {
                Log.w(TAG, "Study mode not active or session null, ignoring callback");
            }
        }

        private void handleConfirmedQuestion(String question) {
            new Thread(() -> {
                try {
                    final String processedQuestion;
                    if (question.split("\\s+").length > 50) {
                        processedQuestion =
                                question.substring(0, Math.min(200, question.length())) + "...";
                    } else {
                        processedQuestion = question;
                    }

                    // Simple prompt without RAG
                    String prompt = "User: " + processedQuestion + "\nAssistant:";
                    String response = llmManager.generate(prompt);

                    if (response == null
                            || response.contains("error")
                            || response.contains("couldn't")) {
                        response = "I understand you're asking about \"" +
                                processedQuestion.substring(0,
                                        Math.min(50, processedQuestion.length())) +
                                "\". Please try rephrasing your question.";
                    }

                    beepSingle();
                    processAndSpeakResponse(response);

                } catch (Exception e) {
                    Log.e(TAG, "Error in handleConfirmedQuestion", e);
                    ui.post(() -> {
                        Toast.makeText(
                                MainActivity.this,
                                "Processing error. Please try again.",
                                Toast.LENGTH_SHORT
                        ).show();
                        inConversation = false;
                        WakeWordService.resumeWakeMode(MainActivity.this);
                    });
                }
            }).start();
        }

        private void processAndSpeakResponse(String response) {
            Log.i(TAG, "processAndSpeakResponse: " +
                    (response.length() > 100 ? response.substring(0, 100) + "..." : response));

            lastGeneratedResponse = response;
            responseChunks.clear();
            currentChunkIndex = 0;

            if (callActive && autoPause) {
                String[] words = response.split("\\s+");
                StringBuilder currentChunk = new StringBuilder();
                for (int i = 0; i < words.length; i++) {
                    currentChunk.append(words[i]).append(" ");
                    if ((i + 1) % wordsGap == 0 || i == words.length - 1) {
                        responseChunks.add(currentChunk.toString().trim());
                        currentChunk = new StringBuilder();
                    }
                }
                Log.i(TAG, "Split response into " + responseChunks.size() + " chunks");

                // Beep before starting to speak
                beepSingle();
                ui.postDelayed(() -> {
                    playNextChunk();
                }, 300);
            } else if (callActive) {
                // Beep before speaking
                beepSingle();
                ui.postDelayed(() -> {
                    Log.i(TAG, "Speaking full response");
                    speak(response);
                }, 300);
            } else {
                Log.w(TAG, "Call not active, cannot speak response");
            }
        }

        private void playNextChunk() {
            if (responsePaused || !inConversation) return;

            if (currentChunkIndex < responseChunks.size()) {
                // 🔥 Request audio focus before speaking
                requestTtsAudioFocus();

                String text = responseChunks.get(currentChunkIndex);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chunk_" + currentChunkIndex);
                currentChunkIndex++;
            } else {
                Log.i(TAG, "All chunks completed, ending conversation");
                ui.postDelayed(() -> {
                    beepSingle();
                    inConversation = false;
                    releaseTtsAudioFocus();
                    WakeWordService.resumeWakeMode(this);
                }, 500);
            }
        }

        private void repeatLastSentences() {
            if (lastGeneratedResponse == null || lastGeneratedResponse.isEmpty()) return;

            WakeWordService.pauseListening(this);
            safeStopTTS();

            String[] words = lastGeneratedResponse.split("\\s+");
            int repeatWords = Math.min(20, words.length);

            StringBuilder sb = new StringBuilder();
            for (int i = words.length - repeatWords; i < words.length; i++) {
                if (i >= 0) {
                    sb.append(words[i]).append(" ");
                }
            }

            String repeatText = sb.toString().trim();
            if (!repeatText.isEmpty()) {
                speak(repeatText);
            }
        }

        private void restartResponse() {
            safeStopTTS();
            responsePaused = false;
            currentChunkIndex = 0;

            // Give TTS time to stop
            ui.postDelayed(() -> {
                if (autoPause && !responseChunks.isEmpty()) {
                    playNextChunk();
                } else if (!lastGeneratedResponse.isEmpty()) {
                    speak(lastGeneratedResponse);
                }
            }, 300);
        }
        private final AtomicBoolean sttLock = new AtomicBoolean(false);

        private void listenForCommand(int durationMs, CommandCallback callback) {
            new Thread(() -> {

                // Prevent overlapping STT sessions
                if (sttActive) {
                    return;
                }

    // ⛔ block TTS overlap ONLY outside study mode
                if (ttsSpeaking && !studyModeActive) {
                    return;
                }



                sttActive = true;

                File wav = null;

                try {
                    if (recorder == null) {
                        recorder = new Recorder(this);
                    }

                    wav = new File(dataDir, "cmd_temp.wav");
                    recorder.setFilePath(wav.getAbsolutePath());

                    Log.i(TAG, "STT command recording started");
                    Log.i(TAG, "🎤 STUDY GAP — MIC OPEN");

                    recorder.start();

                    Thread.sleep(durationMs);

                    recorder.stop();
                    Log.i(TAG, "STT command recording stopped");

                    if (whisper == null) {
                        Log.e(TAG, "Whisper not initialized");
                        callback.onResult("");
                        return;
                    }

                    File finalWav = wav;

                    whisper.setListener(new Whisper.WhisperListener() {
                        @Override
                        public void onUpdateReceived(String m) {}

                        @Override
                        public void onResultReceived(String r) {
                            Log.i(TAG, "STT command result: " + r);
                            callback.onResult(r != null ? r : "");
                        }
                    });

                    whisper.setFilePath(finalWav.getAbsolutePath());
                    whisper.setAction(Whisper.ACTION_TRANSCRIBE);
                    whisper.start();

                } catch (Exception e) {
                    Log.e(TAG, "Cmd listen fail", e);
                    callback.onResult("");
                } finally {
                    // 🔒 ALWAYS release STT lock
                    sttActive = false;
                }

            }).start();
        }


        interface CommandCallback { void onResult(String result); }

        /* ===================== HELPERS ===================== */
        private boolean isMagicWord(String text) {
            if (text == null || text.trim().isEmpty()) {
                return false;
            }

            String lowerText = text.toLowerCase().trim();
            String[] magicWords = {"pause", "resume", "continue", "repeat", "restart",
                    "skip", "next", "new", "question", "stop", "exit", "end"};

            for (String word : magicWords) {
                if (lowerText.contains(word)) {
                    Log.i(TAG, "Found magic word '" + word + "' in: " + lowerText);
                    return true;
                }
            }
            return false;
        }
        private void handleMagicWord(String magicWord) {
            Log.i(TAG, "🎯 Handling magic word: " + magicWord);

            // Cancel any scheduled next chunk
            ui.removeCallbacksAndMessages(null);

            switch (magicWord) {
                case "pause":
                    if (!responsePaused) {
                        responsePaused = true;
                        safeStopTTS();
                        // 🔥 FIX: In AutoPause mode, don't speak "Paused." - just pause silently
                        // Listen immediately for next command: resume, repeat, restart, etc.
                        Log.i(TAG, "✅ Paused via magic word in AutoPause mode");
                        // Next command will be listened for in the current gap
                    } else {
                        // Already paused - no need to speak
                        Log.i(TAG, "Already paused");
                    }
                    break;

                case "resume":
                case "continue":
                    if (responsePaused) {
                        responsePaused = false;
                        // 🔥 FIX: Don't speak "Resuming." - just resume silently
                        // Continue to next chunk without delay
                        Log.i(TAG, "✅ Resuming from pause");
                        ui.post(() -> playNextChunk());
                    } else {
                        // Already playing - no need to speak
                        Log.i(TAG, "Already playing");
                    }
                    break;

                case "repeat":
                    if (!lastGeneratedResponse.isEmpty()) {
                        responsePaused = false;
                        isRepeating = true;
                        safeStopTTS();

                        if (currentChunkIndex > 0) {
                            currentChunkIndex = Math.max(0, currentChunkIndex - 1);
                            // 🔥 FIX: Don't speak "Repeating." - just repeat silently
                            Log.i(TAG, "✅ Repeating previous chunk");
                            ui.post(() -> playNextChunk());
                        } else {
                            repeatLastSentences();
                        }
                    } else {
                        // Nothing to repeat - no need to speak
                        Log.i(TAG, "Nothing to repeat");
                    }
                    break;

                case "restart":
                    responsePaused = false;
                    currentChunkIndex = 0;
                    // 🔥 FIX: Don't speak "Restarting." - just restart silently
                    Log.i(TAG, "✅ Restarting response");
                    ui.post(() -> playNextChunk());
                    break;

                case "skip":
                case "next":
                    responsePaused = false;
                    // 🔥 FIX: Don't speak "Skipping." - just skip silently
                    Log.i(TAG, "✅ Skipping to next chunk");
                    ui.post(() -> playNextChunk());
                    break;

                case "new":
                case "question":
                    responsePaused = false;
                    safeStopTTS();
                    currentChunkIndex = 0;
                    responseChunks.clear();
                    // 🔥 FIX: Don't speak message - just start STT immediately
                    beepSingle();
                    startSTTFromWake();
                    Log.i(TAG, "✅ New question mode");
                    break;

                case "stop":
                case "exit":
                case "end":
                    // 🔥 FIX: Don't speak "Stopping." - just stop silently
                    resetConversationState();
                    WakeWordService.resumeWakeMode(MainActivity.this);
                    Log.i(TAG, "✅ Conversation stopped");
                    break;

                default:
                    // If we get here, it means isMagicWord() returned true but we didn't match
                    Log.w(TAG, "Magic word matched but not handled: " + magicWord);
                    break;
            }
        }
        private void handleMagicWordCommand(String lowerCmd) {
            Log.i(TAG, "🎯 Handling magic word command: " + lowerCmd);

            // Cancel any scheduled next chunk
            ui.removeCallbacksAndMessages(null);

            if (isMagicWord(lowerCmd)) {
                // Extract the actual magic word
                String[] magicWords = {"pause", "resume", "continue", "repeat", "restart",
                        "skip", "next", "new", "question", "stop", "exit", "end"};

                String detectedWord = "";
                for (String word : magicWords) {
                    if (lowerCmd.contains(word)) {
                        detectedWord = word;
                        break;
                    }
                }

                if (!detectedWord.isEmpty()) {
                    handleMagicWord(detectedWord);
                }
            } else {
                // Not a magic word, handle as normal command
                handleNormalModeGapCommand(lowerCmd);
            }
        }
        private void setupTTS() {
            if (ttsInitializing) {
                Log.i(TAG, "TTS already initializing, skipping");
                return;
            }

            // If TTS is already initialized, just use it
            if (tts != null && ttsReady) {
                Log.i(TAG, "TTS already initialized and ready");
                return;
            }

            ttsInitializing = true;
            Log.i(TAG, "Initializing TTS...");

            tts = new TextToSpeech(this, status -> {
                ttsInitializing = false;
                Log.i(TAG, "TTS initialization callback, status: " + status);

                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(Locale.US);
                    tts.setSpeechRate(speechRate);
                    ttsReady = true;
                    tts.setAudioAttributes(
                            new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                    );





                    Log.i(TAG, "TTS initialized successfully");

                    // Set up the utterance listener
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {

                        @Override
                        public void onStart(String id) {
                            ttsSpeaking = true;
                            Log.d(TAG, "TTS started utterance: " + id);

                            if (id.startsWith("study_")) {
                                Log.i(TAG, "Study mode TTS started: " + id);
                            }
                        }

                        @Override
                        public void onDone(String id) {
                            ttsSpeaking = false;
                            Log.d(TAG, "TTS done utterance: " + id);
                            if ("conf".equals(id)) {
                                Log.i(TAG, "Confirmation utterance done - handled in speakConfirmation");
                                return;
                            }
                            if (id.equals("utt") && responsePaused) {
                                Log.i(TAG, "✅ 'Paused.' message finished, setting up command listening");
                                ui.postDelayed(() -> listenForSingleCommandAfterPause(), 500);
                                return;
                            }
                            // For other control messages during AutoPause mode, don't do anything special
                            // Normal messages like "I didn't catch that" should proceed normally
                            if (responsePaused) {
                                Log.i(TAG, "Paused — skipping gap STT completely");
                                return; // 🔥 Don't do anything if paused
                            }

                            // 🔥 IMPORTANT: DON'T release audio focus during gaps if we're in conversation
                            if (!studyModeActive && !inConversation) {
                                releaseTtsAudioFocus();
                            }

                            if ("repeat".equals(id)) {
                                isRepeating = false;
                                beepSingle();
                                WakeWordService.unlockAfterTTS(MainActivity.this, true);
                                return;
                            }

                            if (isRepeating) {
                                return;
                            }

                            // 🔥 UPDATED: Handle study mode chunks with autoPause
                            if (id.startsWith("study_content_") && id.contains("_chunk_") && autoPause && !responsePaused) {
                                Log.i(TAG, "Study mode chunk completed: " + id);

                                String[] parts = id.split("_");
                                String noteId = parts.length > 2 ? parts[2] : null;

                                // Check if we're at the end
                                if (currentChunkIndex >= responseChunks.size()) {
                                    Log.i(TAG, "Finished all study content chunks for note: " + noteId);

                                    // Move to next note after a delay
                                    ui.postDelayed(() -> {
                                        if (studyModeActive && studyModeSession != null) {
                                            Log.i(TAG, "Auto-advancing to next note after finishing chunks");

                                            studyModeSession.currentNoteIndex++;
                                            if (studyModeSession.currentNoteIndex <
                                                    studyModeSession.segments.get(studyModeSession.currentSegmentIndex).notes.size()) {
                                                studyModeSession.startNoteSelection(
                                                        studyModeSession.segments.get(studyModeSession.currentSegmentIndex)
                                                );
                                            } else {
                                                studyModeSession.currentSegmentIndex++;
                                                studyModeSession.inNoteSelection = false;
                                                studyModeSession.nextSegment();
                                            }
                                        }
                                    }, 500);
                                    return;
                                }

                                // Play next chunk after pause
                                // 🎧 Listen FIRST
                                // 🔑 Create a new gap token
                                int myToken = ++studyGapToken;

                                // 🔥 ensure mic is free
                                safeStopTTS();
                                ttsSpeaking = false;

                                // 🎧 LISTEN DURING GAP
                                armStudyModeCommandListening(myToken);

                                // ▶️ continue only if silence
                                ui.postDelayed(() -> {
                                    if (!studyModeActive) return;
                                    if (responsePaused) return;
                                    if (studyGapToken != myToken) return;

                                    // 🔥 FIX: Add beep to signal gap closing and content resuming
                                    beepSingle();
                                    playStudyNextChunk(noteId);
                                }, pauseGap * 1000);

                                return;
                            }

                            // 🔥 NEW: Handle normal mode chunks with gap listening
                            if (id.startsWith("chunk_") && autoPause && !responsePaused && inConversation) {
                                Log.i(TAG, "Normal mode chunk completed: " + id + ", chunk " +
                                        currentChunkIndex + " of " + responseChunks.size());

                                // Check if we're at the end
                                if (currentChunkIndex >= responseChunks.size()) {
                                    Log.i(TAG, "Finished all normal mode chunks");
                                    ui.postDelayed(() -> {
                                        beepSingle();
                                        inConversation = false;
                                        releaseTtsAudioFocus(); // 🔥 Release audio focus at the END
                                        WakeWordService.resumeWakeMode(MainActivity.this);
                                    }, 500);
                                    return;
                                }

                                // 🎧 LISTEN FOR COMMANDS DURING GAP (NORMAL MODE)
                                armNormalModeGapListening();

                                // ▶️ Schedule next chunk only if no command is detected

                                return;
                            }

                            // Handle study mode callbacks (non-chunked)
                            if (id.startsWith("study_") && !id.contains("_chunk_")) {
                                Log.i(TAG, "Study mode TTS completed: " + id);

                                ui.postDelayed(() -> {
                                    if (studyModeActive) {
                                        String[] parts = id.split("_");
                                        if (parts.length > 1) {
                                            String callbackType = parts[1];
                                            String extraData = parts.length > 2 ? parts[2] : null;

                                            Log.i(TAG, "Processing study callback: " + callbackType +
                                                    ", extra: " + extraData + ", session: " + (studyModeSession != null));

                                            // Direct handling for "start" callback
                                            if ("start".equals(callbackType)) {
                                                Log.i(TAG, "Starting first segment after start message");
                                                if (studyModeSession != null) {
                                                    studyModeSession.nextSegment();
                                                }
                                            } else {
                                                // Use the callback method for other types
                                                handleStudyModeCallback(callbackType, extraData);
                                            }
                                        }
                                    } else {
                                        Log.w(TAG, "Study mode not active, ignoring callback");
                                    }
                                }, 300);
                                return;
                            }

                            // Final cleanup if not in study mode and TTS is done
                            if (!studyModeActive && !ttsSpeaking && !id.startsWith("chunk_")) {
                                // Small delay before ending conversation
                                ui.postDelayed(() -> {
                                    if (!ttsSpeaking) {
                                        inConversation = false;
                                        releaseTtsAudioFocus(); // 🔥 Release audio focus at the END
                                        WakeWordService.resumeWakeMode(MainActivity.this);
                                    }
                                }, 500);
                            }
                        }

                        @Override
                        public void onError(String id) {
                            ttsSpeaking = false;
                            Log.e(TAG, "TTS error utterance: " + id);

                            // Release audio focus
                            if (!studyModeActive) {
                                releaseTtsAudioFocus();
                            }


                            if (id.startsWith("study_")) {
                                Log.e(TAG, "Study mode TTS error");
                            }

                            if (!studyModeActive) {
                                inConversation = false;
                                WakeWordService.resumeWakeMode(MainActivity.this);
                            }
                        }
                    });

                    // Check if we have pending speech
                    if (pendingTtsText != null) {
                        Log.i(TAG, "Speaking pending text: " + pendingTtsText);
                        if (pendingUtteranceId != null) {
                            // Study mode pending speech
                            requestTtsAudioFocus();
                            int result = tts.speak(
                                    pendingTtsText,
                                    TextToSpeech.QUEUE_FLUSH,
                                    pendingUtteranceParams,
                                    pendingUtteranceId
                            );
                            Log.i(TAG, "Pending speech result: " + result);
                        } else {
                            // Regular pending speech
                            safeSpeak(pendingTtsText);
                        }

                        pendingTtsText = null;
                        pendingUtteranceId = null;
                        pendingUtteranceParams = null;
                    }
                } else {
                    Log.e(TAG, "TTS initialization failed with status: " + status);
                    ttsReady = false;

                    // Try again after delay
                    ui.postDelayed(() -> {
                        if (!ttsReady && !ttsInitializing) {
                            Log.i(TAG, "Retrying TTS initialization");
                            setupTTS();
                        }
                    }, 1000);
                }
            });
        }

        private void debugTTSStatus() {
            Log.i(TAG, "TTS Status - Ready: " + ttsReady +
                    ", Initializing: " + ttsInitializing +
                    ", Speaking: " + ttsSpeaking +
                    ", Pending Text: " + (pendingTtsText != null));

            if (tts != null) {
                Log.i(TAG, "TTS Engine: " + tts.getDefaultEngine());
                Log.i(TAG, "TTS Language: " + tts.getLanguage());
                Log.i(TAG, "Current Speech Rate (stored): " + speechRate);
            }
        }
        @JavascriptInterface
        public void testGapListening() {
            ui.post(() -> {
                Log.i(TAG, "=== GAP LISTENING TEST ===");
                Log.i(TAG, "AutoPause: " + autoPause);
                Log.i(TAG, "Pause Gap: " + pauseGap + "s");
                Log.i(TAG, "In Conversation: " + inConversation);
                Log.i(TAG, "TTS Speaking: " + ttsSpeaking);
                Log.i(TAG, "Response Chunks: " + responseChunks.size());

                // Simulate a gap listening scenario
                if (inConversation && !ttsSpeaking && !responseChunks.isEmpty()) {
                    speak("Testing gap listening. Say a command like 'pause' or 'repeat' in the next " + pauseGap + " seconds.");
                    armNormalModeGapListening();
                } else {
                    speak("Not in a conversation with gaps. Start a conversation first.");
                }
            });
        }
        private void armNormalModeGapListening() {
            if (studyModeActive || !inConversation || responsePaused) return;

            int myToken = ++normalGapToken;
            Log.i(TAG, "🎧 Arming STT for normal mode gap (token=" + myToken + ")");

            // HARD RULE: do NOT request audio focus here
            // do NOT schedule TTS yet

            listenForCommand(pauseGap * 1000, result -> {
                if (normalGapToken != myToken) {
                    Log.i(TAG, "⛔ Stale gap STT result ignored");
                    return;
                }

                if (result == null || result.trim().isEmpty()) {
                    Log.i(TAG, "No command detected during gap");

                    // ONLY now allow next chunk
                    ui.post(() -> {
                        if (!responsePaused && inConversation && !ttsSpeaking) {
                            // 🔥 FIX: Add beep to signal gap closing
                            beepSingle();
                            playNextChunk();
                        }
                    });
                    return;
                }

                String cmd = result.toLowerCase().trim();
                Log.i(TAG, "🎙 Normal gap command detected: " + cmd);

                // Cancel next chunk permanently
                normalGapToken++;

                ui.post(() -> {
                    // 🔥 FIX: Increment token to prevent any stale listeners
                    normalGapToken++;
                    handleMagicWordCommand(cmd);
                });
            });
        }

        // 🔥 NEW: Handle commands during normal mode gaps
        // 🔥 NEW: Handle commands during normal mode gaps
        private void handleNormalModeGapCommand(String lowerCmd) {
            Log.i(TAG, "Handling normal mode gap command: " + lowerCmd);

            // 🔥 CRITICAL FIX: Check if it's a magic word FIRST
            if (isMagicWord(lowerCmd)) {
                Log.i(TAG, "🎯 Magic word detected, using magic word handler");
                handleMagicWordCommand(lowerCmd);
                return;
            }

            // Cancel any scheduled next chunk
            ui.removeCallbacksAndMessages(null);

            if (lowerCmd.contains("pause")) {
                responsePaused = true;
                safeStopTTS(); // 🔥 Stop TTS immediately
                speak("Paused.");
                Log.i(TAG, "Paused during gap");

                // 🔥 NEW: Listen for ONE command after pause
                ui.postDelayed(() -> {
                    if (responsePaused && inConversation) {
                        Log.i(TAG, "🎧 Listening for resume/stop command after pause");
                        listenForSingleCommandAfterPause();
                    }
                }, 500);

            } else if (lowerCmd.contains("resume") || lowerCmd.contains("continue")) {
                if (responsePaused) {
                    responsePaused = false;
                    speak("Resuming.");
                    ui.postDelayed(() -> playNextChunk(), 500);
                } else {
                    speak("Already playing.");
                }
            } else if (lowerCmd.contains("repeat")) {
                if (!lastGeneratedResponse.isEmpty()) {
                    responsePaused = false;
                    isRepeating = true;
                    safeStopTTS();

                    // Repeat the last chunk
                    if (currentChunkIndex > 0) {
                        currentChunkIndex = Math.max(0, currentChunkIndex - 1);
                        speak("Repeating.");
                        ui.postDelayed(() -> playNextChunk(), 500);
                    } else {
                        repeatLastSentences();
                    }
                } else {
                    speak("Nothing to repeat.");
                }
            } else if (lowerCmd.contains("restart")) {
                responsePaused = false;
                currentChunkIndex = 0;
                speak("Restarting.");
                ui.postDelayed(() -> playNextChunk(), 500);
            } else if (lowerCmd.contains("skip") || lowerCmd.contains("next")) {
                responsePaused = false;
                speak("Skipping.");
                ui.postDelayed(() -> playNextChunk(), 500);
            } else if (lowerCmd.contains("new") || lowerCmd.contains("question")) {
                responsePaused = false;
                safeStopTTS();
                currentChunkIndex = 0;
                responseChunks.clear();
                speak("Ask your new question.");
                ui.postDelayed(() -> {
                    beepSingle();
                    startSTTFromWake();
                }, 1200);
            } else if (lowerCmd.contains("stop") || lowerCmd.contains("exit") || lowerCmd.contains("end")) {
                speak("Stopping.");
                resetConversationState();
                WakeWordService.resumeWakeMode(MainActivity.this);
            } else {
                // If command not recognized, continue with next chunk
                Log.i(TAG, "Unknown command, continuing with next chunk");
                if (!responsePaused) {
                    playNextChunk();
                }
            }
        }
        @JavascriptInterface
        public void testMagicWords() {
            ui.post(() -> {
                Log.i(TAG, "=== MAGIC WORDS TEST ===");

                // Test the isMagicWord method
                String[] testCommands = {
                        "pause please",
                        "resume now",
                        "repeat that",
                        "restart from beginning",
                        "skip this",
                        "next chunk",
                        "new question",
                        "stop talking",
                        "exit conversation",
                        "end now",
                        "thank you", // Not a magic word
                        "boss boss"  // Not a magic word
                };

                for (String cmd : testCommands) {
                    boolean isMagic = isMagicWord(cmd);
                    Log.i(TAG, "Test: '" + cmd + "' -> isMagicWord: " + isMagic);
                }

                speak("Magic words test complete. Check logs.");
            });
        }

        // 🔥 NEW: Listen for a single command after pause
        private void listenForSingleCommandAfterPause() {
            if (!responsePaused || !inConversation) return;

            Log.i(TAG, "🎧 Starting single-command STT after pause");

            listenForCommand(5000, result -> {
                sttActive = false;

                if (result == null || result.trim().isEmpty()) {
                    Log.i(TAG, "No command heard after pause");
                    // If no command heard, listen again for another 5 seconds
                    ui.postDelayed(() -> listenForSingleCommandAfterPause(), 500);
                    return;
                }

                String cmd = result.toLowerCase().trim();
                Log.i(TAG, "🎙 Command after pause: " + cmd);

                ui.post(() -> handleResumeCommand(cmd));
            });
        }

        private void handleResumeCommand(String cmd) {
            // 🔥 Process commands in priority order (longer matches first to avoid false positives)
            if (cmd.contains("resume") || cmd.contains("continue")) {
                responsePaused = false;
                // 🔥 FIX: Don't speak - just resume silently
                Log.i(TAG, "✅ Resuming from pause");
                ui.post(() -> playNextChunk());
            } else if (cmd.contains("repeat")) {
                responsePaused = false;
                if (currentChunkIndex > 0) {
                    currentChunkIndex = Math.max(0, currentChunkIndex - 1);
                    // 🔥 FIX: Don't speak - just repeat silently
                    Log.i(TAG, "✅ Repeating previous chunk");
                    ui.post(() -> playNextChunk());
                } else {
                    Log.i(TAG, "✅ Repeating from beginning");
                    repeatLastSentences();
                }
            } else if (cmd.contains("restart")) {
                responsePaused = false;
                currentChunkIndex = 0;
                // 🔥 FIX: Don't speak - just restart silently
                Log.i(TAG, "✅ Restarting response");
                ui.post(() -> playNextChunk());
            } else if (cmd.contains("stop") || cmd.contains("exit") || cmd.contains("end")) {
                // 🔥 FIX: Don't speak - just stop silently
                Log.i(TAG, "✅ Stopping conversation");
                resetConversationState();
                WakeWordService.resumeWakeMode(MainActivity.this);
            } else if (cmd.contains("new") || cmd.contains("question")) {
                responsePaused = false;
                safeStopTTS();
                currentChunkIndex = 0;
                responseChunks.clear();
                // 🔥 FIX: Don't speak - just start STT immediately
                Log.i(TAG, "✅ New question mode");
                beepSingle();
                startSTTFromWake();
            } else if (cmd.contains("pause")) {
                // 🔥 FIX: Already paused - ignore duplicate pause command
                Log.i(TAG, "Already paused - ignoring duplicate pause command");
                return;
            } else {
                // Unknown command - don't speak, just listen again
                Log.i(TAG, "Unknown command after pause, listening again");
                ui.postDelayed(() -> listenForSingleCommandAfterPause(), 500);
            }
        }
        @JavascriptInterface
        public void debugTTS() {
            ui.post(() -> {
                Log.i(TAG, "=== TTS DEBUG ===");
                Log.i(TAG, "TTS Object: " + (tts != null));
                Log.i(TAG, "TTS Ready: " + ttsReady);
                Log.i(TAG, "TTS Speaking: " + ttsSpeaking);
                Log.i(TAG, "Study Mode Active: " + studyModeActive);

                // Test TTS with a simple message
                if (tts != null && ttsReady) {
                    Log.i(TAG, "Testing TTS with simple message...");
                    tts.speak("TTS test message", TextToSpeech.QUEUE_FLUSH, null, "debug_test");
                } else {
                    Log.e(TAG, "TTS not ready for test");
                }
            });
        }

        // Update the debugAudioState method:
        private void debugAudioState(String context) {
            Log.i(TAG, "=== DEBUG AUDIO STATE - " + context + " ===");
            Log.i(TAG, "TTS Ready: " + ttsReady);
            Log.i(TAG, "TTS Speaking: " + ttsSpeaking);
            Log.i(TAG, "Study Mode Active: " + studyModeActive);
            Log.i(TAG, "In Conversation: " + inConversation);
            Log.i(TAG, "Call Active: " + callActive);
            Log.i(TAG, "STT Active: " + sttActive);
            Log.i(TAG, "Response Paused: " + responsePaused);
            Log.i(TAG, "Is Repeating: " + isRepeating);
            Log.i(TAG, "Current Speech Rate: " + speechRate);

            // Bluetooth audio state
            if (bluetoothAudioManager != null) {
                Log.i(TAG, "Bluetooth Connected: " + bluetoothAudioManager.isBluetoothHeadsetConnected());
                Log.i(TAG, "Bluetooth SCO Active: " + bluetoothAudioManager.isBluetoothScoActive());
            }

            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            Log.i(TAG, "Audio Mode: " + am.getMode());
            Log.i(TAG, "Speakerphone On: " + am.isSpeakerphoneOn());
            Log.i(TAG, "Bluetooth SCO On: " + am.isBluetoothScoOn());
            Log.i(TAG, "Volume: " + am.getStreamVolume(AudioManager.STREAM_MUSIC));

            if (tts != null) {
                Log.i(TAG, "TTS Engine: " + tts.getDefaultEngine());
                Log.i(TAG, "TTS Language: " + tts.getLanguage());
            }

            if (studyModeSession != null) {
                Log.i(TAG, "Study Session - Segment: " + studyModeSession.currentSegmentIndex +
                        ", Note: " + studyModeSession.currentNoteIndex);
            }
        }


        // Update the speak method to use correct audio mode:
        private void speak(String txt) {
            Log.i(TAG, "speak called: " + (txt.length() > 50 ? txt.substring(0, 50) + "..." : txt));

            // Set correct audio mode based on Bluetooth state
            if (bluetoothAudioManager != null) {
                bluetoothAudioManager.setAudioModeForTTS();
            }

            if (tts != null && ttsReady) {
                safeSpeak(txt);
            } else {
                Log.w(TAG, "TTS not ready, storing for later");
                pendingTtsText = txt;
                setupTTS();
            }
        }

        // Add method to start STT with proper audio routing:
        private void startRecordingWithAudioRouting() {
            Log.i(TAG, "Starting recording with audio routing");

            // Ensure Bluetooth audio is active if headset is connected
            if (bluetoothAudioManager != null && bluetoothAudioManager.isBluetoothHeadsetConnected()) {
                bluetoothAudioManager.switchToBluetooth();

                // Give time for Bluetooth to switch
                ui.postDelayed(() -> {
                    beepSingle();
                    startSTTFromWake();
                }, 300);
            } else {
                // Use phone audio
                beepSingle();
                startSTTFromWake();
            }
        }

        private void safeSpeak(String txt) {
            if (tts != null) {
                requestTtsAudioFocus();
                tts.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "utt");
            } else {
                Log.e(TAG, "TTS is null in safeSpeak");
            }
        }

        private void safeStopTTS() {
            Log.i(TAG, "safeStopTTS called");
            if (tts != null) {
                tts.stop();
                ttsSpeaking = false;
                releaseTtsAudioFocus();
            }
            WakeWordService.unlockAfterTTS(this, true);
        }

        private void initWhisper() {
            File model = new File(dataDir, "whisper-base.en.tflite");
            File vocab = new File(dataDir, "filters_vocab_en.bin");
            if (model.exists() && vocab.exists()) {
                whisper = new Whisper(this);
                whisper.loadModel(model, vocab, false);
                sttReady = true;
                notifyStatus();
                Log.i(TAG, "Whisper initialized successfully");
            } else {
                Log.e(TAG, "Whisper model or vocab not found");
            }
        }

        private void startWakeService() {
            Intent i = new Intent(this, WakeWordService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        }

        private void downloadFileWithProgress(String urlStr, File out, String jsCallback) throws Exception {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.connect();
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int r; long downloaded = 0;
                while ((r = in.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                    downloaded += r;
                    if (total > 0) {
                        int pct = (int)(downloaded * 100 / total);
                        ui.post(() -> web.evaluateJavascript(jsCallback + "(" + pct + ")", null));
                    }
                }
            }
        }

        private void downloadModelAndInit(String url) {
            new Thread(() -> {
                File out = new File(getExternalFilesDir(null), "model.task");

                try {
                    if (out.exists()) out.delete();

                    downloadFileWithProgress(
                            url,
                            out,
                            "window.onLLMDownloadProgress"
                    );

                    ui.post(() -> {
                        boolean ok = initLlm(out.getAbsolutePath());

                        if (ok) {
                            llmReady = true;
                            notifyStatus();
                            web.evaluateJavascript(
                                    "window.onModelDownloaded && window.onModelDownloaded()",
                                    null
                            );
                        } else {
                            llmReady = false;
                            out.delete();
                            web.evaluateJavascript(
                                    "window.onModelDownloadFailed && window.onModelDownloadFailed()",
                                    null
                            );
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "LLM download/init failed", e);
                    if (out.exists()) out.delete();
                    ui.post(() ->
                            web.evaluateJavascript(
                                    "window.onModelDownloadFailed && window.onModelDownloadFailed()",
                                    null
                            )
                    );
                }
            }).start();
        }

        private boolean initLlm(String path) {
            llmReady = llmManager.init(this, path);
            notifyStatus();
            return llmReady;
        }

        private void notifyStatus() {
            ui.post(() -> web.evaluateJavascript("if(window.onNativeStatus) window.onNativeStatus(" + sttReady + "," + llmReady + ")", null));
        }

        private void beepOnce() { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120); }
        private void beepSingle() { if (studyModeActive) return;
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150); }
        private void beepDouble() {
            if (studyModeActive) return;

            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
            ui.postDelayed(() -> toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100), 200);
        }

        private void startSTTFromWake() {
            ui.post(() -> web.evaluateJavascript("if(window.startVoiceFromWake) window.startVoiceFromWake()", null));
        }

        private void requestMicPermission() {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERM_REQUEST_MIC);
            }
        }

        private void copyAssetFlat(String path, String name) {
            try {
                File f = new File(dataDir, name);
                if (f.exists()) return;
                try (InputStream in = getAssets().open(path); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] b = new byte[4096]; int r;
                    while ((r = in.read(b)) != -1) out.write(b, 0, r);
                }
            } catch (Exception ignored) {}
        }

        /* ===================== SUPPORT CLASSES ===================== */

        public static class GoogleLlmManager {
            private LlmInference engine;
            private LlmInferenceSession session;
            private Context appContext;
            private LicenseManager licenseManager;

            public GoogleLlmManager(Context context) {
                this.appContext = context.getApplicationContext();
                this.licenseManager = new LicenseManager(context);
            }

            public boolean init(Context ctx, String path) {
                // 🔒 LICENSE CHECK: Block initialization if license invalid
                if (licenseManager != null && !licenseManager.enforceLicenseCheck()) {
                    Log.e("LLM_INIT", "Init blocked - license invalid");
                    return false;
                }

                try {
                    LlmInference.LlmInferenceOptions opts = LlmInference.LlmInferenceOptions
                            .builder()
                            .setModelPath(path)
                            .setMaxTokens(512)
                            .build();
                    engine = LlmInference.createFromOptions(ctx, opts);
                    return true;
                } catch (Exception e) {
                    Log.e("LLM_INIT", "Init failed", e);
                    return false;
                }
            }

            public String generate(String prompt) {
                // 🔒 LICENSE CHECK: Block generation if license invalid
                if (licenseManager != null && !licenseManager.enforceLicenseCheck()) {
                    Log.e("LLM_GEN", "Generation blocked - license invalid");
                    return "Your subscription has expired. Please renew to continue using this feature.";
                }

                if (prompt == null || prompt.isEmpty()) {
                    return "Please provide a question.";
                }

                try {
                    String truncatedPrompt = prompt.length() > 1200
                            ? prompt.substring(0, 1200)
                            : prompt;

                    LlmInferenceSession.LlmInferenceSessionOptions sessionOpts =
                            LlmInferenceSession.LlmInferenceSessionOptions
                                    .builder()
                                    .setTemperature(0.7f)
                                    .setTopK(40)
                                    .build();

                    session = LlmInferenceSession.createFromOptions(engine, sessionOpts);
                    session.addQueryChunk(truncatedPrompt);

                    final String[] response = new String[1];
                    final CountDownLatch latch = new CountDownLatch(1);

                    new Thread(() -> {
                        try {
                            response[0] = session.generateResponse();
                        } catch (Exception e) {
                            Log.e("LLM_GEN", "Generation error", e);
                            response[0] = "I couldn't process that request. Please try again with a shorter question.";
                        } finally {
                            latch.countDown();
                        }
                    }).start();

                    boolean completed = latch.await(30, TimeUnit.SECONDS);
                    if (!completed) {
                        Log.e("LLM_GEN", "Generation timeout");
                        return "The response took too long. Please try a simpler question.";
                    }

                    return response[0] != null ? response[0] : "No response generated.";

                } catch (Exception e) {
                    Log.e("LLM_GEN", "Generation failed", e);

                    try {
                        String minimalPrompt = extractUserQuestion(prompt);
                        if (minimalPrompt.length() < prompt.length()) {
                            return generate(minimalPrompt);
                        }
                    } catch (Exception ex) {
                        // Ignore fallback error
                    }

                    return "Sorry, I encountered an error. Please try again.";
                }
            }

            private String smartTruncate(String prompt, int maxWords) {
                if (prompt == null) return "";

                String[] words = prompt.split("\\s+");
                if (words.length <= maxWords) return prompt;

                int userIndex = -1;
                for (int i = 0; i < words.length; i++) {
                    if (words[i].equals("User:") || words[i].equals("user:")) {
                        userIndex = i;
                        break;
                    }
                }

                StringBuilder truncated = new StringBuilder();
                if (userIndex >= 0 && userIndex < words.length - 1) {
                    int start = Math.max(0, userIndex);
                    int end = Math.min(words.length, start + maxWords);
                    for (int i = start; i < end; i++) {
                        truncated.append(words[i]).append(" ");
                    }
                    if (end < words.length) {
                        truncated.append("...");
                    }
                } else {
                    int start = Math.max(0, words.length - maxWords);
                    for (int i = start; i < words.length; i++) {
                        truncated.append(words[i]).append(" ");
                    }
                    truncated.insert(0, "...");
                }

                return truncated.toString().trim();
            }

            private String extractUserQuestion(String prompt) {
                if (prompt == null) return "";

                String lowerPrompt = prompt.toLowerCase();
                int userIndex = lowerPrompt.indexOf("user:");
                if (userIndex >= 0) {
                    String afterUser = prompt.substring(userIndex + 5).trim();
                    int assistantIndex = afterUser.toLowerCase().indexOf("assistant:");
                    if (assistantIndex > 0) {
                        return "User: " + afterUser.substring(0, assistantIndex).trim() + "\nAssistant:";
                    }
                    return "User: " + afterUser + "\nAssistant:";
                }

                String[] words = prompt.split("\\s+");
                StringBuilder result = new StringBuilder("User: ");
                int start = Math.max(0, words.length - 50);
                for (int i = start; i < words.length; i++) {
                    result.append(words[i]).append(" ");
                }
                result.append("\nAssistant:");
                return result.toString();
            }
        }
    }