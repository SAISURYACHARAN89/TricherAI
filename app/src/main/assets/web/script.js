/* ================= GLOBAL STATE ================= */

let library = [];
let callActive = false;
let freeLimitLocked = false;
const API_BASE = "https://api.tricher.app";
let currentUser = {
  user: { name: "Developer", email: "dev@example.com" },
  plan: { name: "Pro", expiresAt: "2099-01-01" }
};

/* ================= LICENSE STATE ================= */
let licenseValid = false;
let licenseEmail = "";
let licenseDeviceId = "";
let licenseExpiryTime = 0;

// License status callback from Android
window.onLicenseStatus = function(valid, message) {
  console.log("License status:", valid, message);
  licenseValid = valid;

  // Update auth screen license status - minimal text only
  const licenseStatusEl = document.getElementById("authLicenseStatus");
  if (licenseStatusEl) {
    licenseStatusEl.style.display = "block";
    licenseStatusEl.textContent = valid ? "✓ License valid" : message || "License required";
    licenseStatusEl.style.color = valid ? "#4CAF50" : "#f44336";
  }

  if (valid) {
    hideLicenseScreen();
    updateLicenseUI(true, message);
  } else {
    updateLicenseUI(false, message);
  }
};

function updateLicenseUI(valid, message) {
  const statusEl = document.getElementById("license-status");
  if (statusEl) {
    statusEl.textContent = message || (valid ? "License Valid" : "License Required");
    statusEl.className = valid ? "license-valid" : "license-invalid";
  }

  // Disable/enable controls based on license
  const startBtn = document.getElementById("start-call-btn");
  const studyBtn = document.getElementById("start-study-btn");

  if (startBtn) startBtn.disabled = !valid;
  if (studyBtn) studyBtn.disabled = !valid;
}

function showLicenseScreen(message) {
  // DEPRECATED: Use auth screen instead
  // Just show auth screen with license message
  showAuth();
  const licenseStatusEl = document.getElementById("authLicenseStatus");
  if (licenseStatusEl && message) {
    licenseStatusEl.style.display = "block";
    licenseStatusEl.textContent = message;
    licenseStatusEl.style.color = "#f44336";
  }
}

function hideLicenseScreen() {
  // No longer needed - handled by auth screen
}

function validateLicenseFromUI() {
  // DEPRECATED: Use sendOTPAndValidate instead
  const emailInput = document.getElementById("license-email-input") || document.getElementById("authEmail");
  const email = emailInput ? emailInput.value.trim() : "";

  if (!email || !email.includes("@")) {
    alert("Please enter a valid email address.");
    return;
  }

  if (typeof AndroidBridge !== "undefined" && AndroidBridge.validateLicense) {
    AndroidBridge.validateLicense(email);
  }
}

function checkLicenseOnStartup() {
  // Don't show separate license screen - auth screen handles everything
  if (typeof AndroidBridge !== "undefined" && AndroidBridge.isLicenseValid) {
    licenseValid = AndroidBridge.isLicenseValid();
    // License status will be shown on auth screen if needed
  }
}

// Called when license auto-expires while app is running
window.onLicenseExpired = function() {
  console.log("LICENSE EXPIRED - auto-blocking access");
  licenseValid = false;

  // End any active call
  if (callActive) {
    callActive = false;
    stopFreeTimer();
    updateCallButton();
  }

  // Show auth screen with expiry message
  showAuth();
  const licenseStatusEl = document.getElementById("authLicenseStatus");
  if (licenseStatusEl) {
    licenseStatusEl.style.display = "block";
    licenseStatusEl.textContent = "Your subscription has expired. Please renew.";
    licenseStatusEl.style.color = "#f44336";
  }

  // Update UI elements
  updateLicenseUI(false, "Subscription expired");
};

/* ================= STUDY MODE STATE ================= */

let studySegments = [];
let currentEditingSegment = null;
let currentTab = 'segments';

// Load study data from localStorage
// Load study data from localStorage
function loadStudyData() {
  const saved = localStorage.getItem("studySegments");
  console.log("Loading study data from localStorage:", saved);

  if (saved) {
    try {
      studySegments = JSON.parse(saved);
      console.log("Successfully parsed studySegments:", studySegments);
      renderSegments();
      renderAllNotes();
    } catch (e) {
      console.error("Error loading study data:", e);
      studySegments = [];
    }
  } else {
    console.log("No saved study data found");
    studySegments = [];
  }
}

// Save study data to localStorage
function saveStudyData() {
  localStorage.setItem("studySegments", JSON.stringify(studySegments));
}

// Generate unique ID
function generateId() {
  return Date.now().toString(36) + Math.random().toString(36).substr(2);
}

/* ================= FREE TRIAL TIMER ================= */

const FREE_DAILY_LIMIT_MS = 2 * 60 * 1000; // 2 minutes
let freeTimerInterval = null;
let freeTimeUsedToday = 0;
let freeTimerRunning = false;
let freeCallStartTs = null;

function getTodayKey() {
  const d = new Date();
  return `free_time_${d.getFullYear()}_${d.getMonth()}_${d.getDate()}`;
}

function loadFreeTime() {
  freeTimeUsedToday = Number(localStorage.getItem(getTodayKey())) || 0;

  if (freeTimeUsedToday >= FREE_DAILY_LIMIT_MS) {
    freeLimitLocked = true;
  } else {
    freeLimitLocked = false;
  }
}

function formatMs(ms) {
  const totalSec = Math.max(0, Math.ceil(ms / 1000));
  const min = String(Math.floor(totalSec / 60)).padStart(2, "0");
  const sec = String(totalSec % 60).padStart(2, "0");
  return `${min}:${sec}`;
}

function updateFreeTimerUI() {
  if (!isFreeUser()) {
    hideFreeTimerUI();
    return;
  }

  loadFreeTime();

  let used = freeTimeUsedToday;

  if (callActive && freeCallStartTs) {
    used += Date.now() - freeCallStartTs;
  }

  const remaining = FREE_DAILY_LIMIT_MS - used;

  const ui = document.getElementById("freeTimerUI");
  const text = document.getElementById("freeTimerText");

  if (remaining <= 0) {
    hideFreeTimerUI();
    return;
  }

  ui.style.display = "block";
  text.innerText = formatMs(remaining);
}

window.onBluetoothStatus = function (connected) {
  const el = document.getElementById("btStatus");
  if (!el) return;

  el.style.display = connected ? "flex" : "none";
};

function hideFreeTimerUI() {
  const ui = document.getElementById("freeTimerUI");
  if (ui) ui.style.display = "none";
}

function saveFreeTime() {
  localStorage.setItem(getTodayKey(), freeTimeUsedToday);
}



/* ================= MAGIC WORDS ================= */
const magicWords = [
  { word: "Hello", action: "Wake Word" },
  { word: "Study Mode", action: "Activate study mode" },
  { word: "Pause", action: "Stop TTS" },
  { word: "Continue", action: "Resume TTS" },
  { word: "Repeat", action: "Repeat answer" },
  { word: "Restart", action: "Restart response" },
  { word: "New Question", action: "Wake state" }
];

/* ================= SETTINGS ================= */

let appSettings = {
  allowQuestionRepeat: false,
  autoPause: false,
  pauseGap: 5,
  wordsGap: 6,
  talkingSpeed: 1.0
};

function loadSettings() {
  const saved = localStorage.getItem("appSettings");
  if (!saved) return;

  appSettings = JSON.parse(saved);

  document.getElementById("allowQuestionRepeat").checked = appSettings.allowQuestionRepeat;
  document.getElementById("autoPause").checked = appSettings.autoPause;
  document.getElementById("pauseGap").value = appSettings.pauseGap;
  document.getElementById("wordsGap").value = appSettings.wordsGap;
  document.getElementById("speedRange").value = appSettings.talkingSpeed;
  document.getElementById("speedVal").innerText = appSettings.talkingSpeed + "x";

  toggleAutoPauseSubSettings(appSettings.autoPause);
  window.AndroidBridge?.updateSettings?.(JSON.stringify(appSettings));
}

function updateSettings() {
  appSettings.allowQuestionRepeat = document.getElementById("allowQuestionRepeat").checked;
  appSettings.autoPause = document.getElementById("autoPause").checked;
  appSettings.pauseGap = +document.getElementById("pauseGap").value;
  appSettings.wordsGap = +document.getElementById("wordsGap").value;
  appSettings.talkingSpeed = +document.getElementById("speedRange").value;

  toggleAutoPauseSubSettings(appSettings.autoPause);
  localStorage.setItem("appSettings", JSON.stringify(appSettings));
  window.AndroidBridge?.updateSettings?.(JSON.stringify(appSettings));
}

function toggleAutoPauseSubSettings(show) {
  document.getElementById("autoPauseSubSettings").style.display = show ? "block" : "none";
}

/* ================= AUTH ================= */

function showAuth() {
  document.getElementById("authScreen").style.display = "flex";
  document.getElementById("mainApp").style.display = "none";

  // Check if user is already logged in to show logout options
  const savedEmail = localStorage.getItem("userEmail");
  if (savedEmail) {
    document.getElementById("authLoggedEmail").textContent = savedEmail;
    document.getElementById("loggedInActions").style.display = "block";
    document.getElementById("emailStep").style.display = "none";
  } else {
    document.getElementById("loggedInActions").style.display = "none";
    document.getElementById("emailStep").style.display = "block";
  }
}

function showMainApp() {
  document.getElementById("authScreen").style.display = "none";
  document.getElementById("mainApp").style.display = "block";
}

// Combined: Send OTP + Validate License
async function sendOTPAndValidate() {
  const email = document.getElementById("authEmail").value.trim();
  const error = document.getElementById("authError");
  const loader = document.getElementById("authLoader");
  const licenseStatus = document.getElementById("authLicenseStatus");

  if (!email || !email.includes("@")) {
    error.innerText = "Please enter a valid email";
    return;
  }

  error.innerText = "";
  loader.style.display = "block";
  if (licenseStatus) {
    licenseStatus.style.display = "block";
    licenseStatus.textContent = "Validating license...";
    licenseStatus.style.color = "#aaa";
  }

  // Trigger license validation via Android bridge
  if (typeof AndroidBridge !== "undefined" && AndroidBridge.validateLicense) {
    AndroidBridge.validateLicense(email);
  }

  try {
    const res = await fetch(`${API_BASE}/api/send-otp`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email })
    });

    const data = await res.json();

    if (!res.ok) {
      throw new Error(data.message || "Failed to send OTP");
    }

    showOTPStep();
  } catch (err) {
    error.innerText = err.message;
  } finally {
    loader.style.display = "none";
  }
}

// Keep original sendOTP for backward compatibility
async function sendOTP() {
  return sendOTPAndValidate();
}

function showOTPStep() {
  document.getElementById("emailStep").style.display = "none";
  document.getElementById("otpStep").style.display = "block";
}

function showEmailStep() {
  document.getElementById("otpStep").style.display = "none";
  document.getElementById("emailStep").style.display = "block";
  document.getElementById("authError").innerText = "";
}

async function verifyOTP() {
  const email = document.getElementById("authEmail").value.trim();
  const otp = document.getElementById("authOtp").value.trim();
  const error = document.getElementById("authError");
  const loader = document.getElementById("authLoader");

  if (!email || otp.length !== 6) {
    error.innerText = "Invalid email or OTP";
    return;
  }

  error.innerText = "";
  loader.style.display = "block";

  try {
    const res = await fetch(`${API_BASE}/api/verify-otp`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, otp })
    });

    const data = await res.json();

    if (!res.ok || !data.ok) {
      throw new Error(data.message || "OTP verification failed");
    }

    const meRes = await fetch(
      `${API_BASE}/api/me?email=${encodeURIComponent(email)}`
    );

    const meData = await meRes.json();

    if (!meRes.ok) {
      throw new Error("Failed to load user profile");
    }

    const userData = {
      user: {
        id: meData.user.id || generateUserId(email),
        name: meData.user.name,
        email: meData.user.email
      },
      plan: {
        name: meData.plan?.name || "FREE",
        expiresAt: meData.plan?.expiresAt || "2099-01-01"
      }
    };

    localStorage.setItem("authToken", data.token || "dummy-token");
    localStorage.setItem("userId", userData.user.id);
    localStorage.setItem("userEmail", userData.user.email);
    localStorage.setItem("userProfile", JSON.stringify(userData));
    localStorage.setItem("lastLogin", new Date().toISOString());

    updateUIWithUser(userData);
    showMainApp();
  } catch (err) {
    error.innerText = err.message;
    localStorage.removeItem("authToken");
    localStorage.removeItem("userId");
    localStorage.removeItem("userEmail");
    localStorage.removeItem("userProfile");
  } finally {
    loader.style.display = "none";
  }
}

function generateUserId(email) {
  return btoa(email).replace(/[^a-zA-Z0-9]/g, '').substring(0, 16);
}

/* ================= INIT ================= */
function syncDownloadUI() {
  // LLM
  if (window.AndroidBridge?.isModelDownloaded?.()) {
    document.getElementById("llmStatus").innerText = "Downloaded";
    document.getElementById("llmProgress").style.display = "none";
    document.getElementById("llmBtn").style.display = "none";
  } else {
    document.getElementById("llmBtn").style.display = "inline-block";
    document.getElementById("llmProgress").style.display = "none";
  }

  // STT
  if (window.AndroidBridge?.isSTTDownloaded?.()) {
    document.getElementById("sttStatus").innerText = "Downloaded";
    document.getElementById("sttProgress").style.display = "none";
    document.getElementById("sttBtn").style.display = "none";
  } else {
    document.getElementById("sttBtn").style.display = "inline-block";
    document.getElementById("sttProgress").style.display = "none";
  }
}

window.onNativeStatus = (sttReady, llmReady) => {
  if (llmReady) {
    document.getElementById("llmStatus").innerText = "Downloaded";
    document.getElementById("llmProgress").style.display = "none";
    document.getElementById("llmBtn").style.display = "none";
  }
};

document.addEventListener("DOMContentLoaded", async () => {
  syncDownloadUI();
  loadFreeTime();
  loadSettings();
  loadStudyData();

  // Check license status on startup
  checkLicenseOnStartup();

  const cachedUser = localStorage.getItem("userProfile");
  const token = localStorage.getItem("authToken");
  const userId = localStorage.getItem("userId");

  if (cachedUser && token && userId) {
    try {
      const userData = JSON.parse(cachedUser);
      updateUIWithUser(userData);
      showMainApp();
      updateFreeTimerUI();
      updateCallButton();
    } catch {
      showAuth();
      return;
    }
    return;
  }

  showAuth();
});

/* ================= STUDY MODE FUNCTIONS ================= */

function toggleStudyMode() {
  closePanels();

  // force light mode
  document.body.classList.remove("dark-mode");
  document.body.classList.add("light-mode");

  document.getElementById("studyPanel").classList.add("open");
  document.getElementById("overlay").classList.add("show");
}


function showStudyTab(tabName) {
  currentTab = tabName;

  // Update tab buttons
  document.querySelectorAll('.tab-btn').forEach(btn => {
    btn.classList.remove('active');
  });
  event.target.classList.add('active');

  // Show selected tab
  document.getElementById('segmentsTab').style.display = tabName === 'segments' ? 'block' : 'none';
  document.getElementById('notesTab').style.display = tabName === 'notes' ? 'block' : 'none';

  if (tabName === 'notes') {
    renderAllNotes();
  }
}

function showAddSegmentForm() {
  document.getElementById('addSegmentForm').style.display = 'block';
  document.getElementById('newSegmentName').value = '';
  document.getElementById('newSegmentName').focus();
}

function addSegment() {
  const name = document.getElementById('newSegmentName').value.trim();
  if (!name) return;

  const segment = {
    id: generateId(),
    name: name,
    notes: []
  };

  studySegments.push(segment);
  saveStudyData();
  renderSegments();

  document.getElementById('addSegmentForm').style.display = 'none';
  document.getElementById('newSegmentName').value = '';
}

function renderSegments() {
  const container = document.getElementById('segmentsList');
  container.innerHTML = '';

  if (studySegments.length === 0) {
    container.innerHTML = '<p style="color: #888; text-align: center;">No segments yet. Add your first segment!</p>';
    return;
  }

  studySegments.forEach(segment => {
    const segmentEl = document.createElement('div');
    segmentEl.className = 'segment-item';
    segmentEl.innerHTML = `
      <div style="flex: 1;">
        <strong>${segment.name}</strong>
        <p style="font-size: 12px; color: #888; margin-top: 4px;">
          ${segment.notes.length} note${segment.notes.length !== 1 ? 's' : ''}
        </p>
      </div>
      <div>
        <button class="edit-btn" onclick="showNotesForSegment('${segment.id}')">Notes</button>
        <button class="edit-btn" onclick="editSegment('${segment.id}')">Edit</button>
        <button class="delete-btn" onclick="deleteSegment('${segment.id}')">Delete</button>
      </div>
    `;
    container.appendChild(segmentEl);
  });
}

function showNotesForSegment(segmentId) {
  const segment = studySegments.find(s => s.id === segmentId);
  if (!segment) return;

  document.getElementById('currentSegmentName').textContent = segment.name;
  currentEditingSegment = segmentId;

  const notesSection = document.getElementById('notesSection');
  const notesList = document.getElementById('notesList');

  notesSection.style.display = 'block';
  notesList.innerHTML = '';

  if (segment.notes.length === 0) {
    notesList.innerHTML = '<p style="color: #888; text-align: center;">No notes yet. Add your first note!</p>';
  } else {
    segment.notes.forEach(note => {
      const noteEl = document.createElement('div');
      noteEl.className = 'note-item';
      noteEl.innerHTML = `
        <div style="flex: 1;">
          <strong>${note.name}</strong>
          <div class="note-content">${note.content.substring(0, 100)}${note.content.length > 100 ? '...' : ''}</div>
        </div>
        <div>
          <button class="edit-btn" onclick="editNote('${segmentId}', '${note.id}')">Edit</button>
          <button class="delete-btn" onclick="deleteNote('${segmentId}', '${note.id}')">Delete</button>
        </div>
      `;
      notesList.appendChild(noteEl);
    });
  }

  // Add button to add new note
  const addNoteBtn = document.createElement('button');
  addNoteBtn.className = 'add-btn';
  addNoteBtn.textContent = '+ Add Note';
  addNoteBtn.onclick = () => showAddNoteForm(segmentId);
  addNoteBtn.style.marginTop = '15px';
  notesList.appendChild(addNoteBtn);
}

function showAddNoteForm(segmentId) {
  const segment = studySegments.find(s => s.id === segmentId);
  if (!segment) return;

  const formHtml = `
    <div style="margin-top: 15px; padding: 15px; background: #222; border-radius: 6px;">
      <input type="text" id="newNoteName" placeholder="Note name" class="text-input">
      <textarea id="newNoteContent" placeholder="Paste your note content here..." class="text-input" rows="4"></textarea>
      <div style="display: flex; gap: 10px; margin-top: 10px;">
        <button class="save-btn" style="flex: 1;" onclick="addNote('${segmentId}')">Save Note</button>
        <button class="delete-btn" style="flex: 1;" onclick="hideAddNoteForm()">Cancel</button>
      </div>
    </div>
  `;

  const notesList = document.getElementById('notesList');
  const existingForm = notesList.querySelector('#addNoteForm');
  if (existingForm) existingForm.remove();

  const formDiv = document.createElement('div');
  formDiv.id = 'addNoteForm';
  formDiv.innerHTML = formHtml;
  notesList.appendChild(formDiv);

  document.getElementById('newNoteName').focus();
}

function hideAddNoteForm() {
  const form = document.getElementById('addNoteForm');
  if (form) form.remove();
}

function addNote(segmentId) {
  const segment = studySegments.find(s => s.id === segmentId);
  if (!segment) return;

  const name = document.getElementById('newNoteName').value.trim();
  const content = document.getElementById('newNoteContent').value.trim();

  if (!name) return;

  const note = {
    id: generateId(),
    name: name,
    content: content
  };

  segment.notes.push(note);
  saveStudyData();
  showNotesForSegment(segmentId);
}

function editSegment(segmentId) {
  const segment = studySegments.find(s => s.id === segmentId);
  if (!segment) return;

  openDialog({
    title: "Edit Segment",
    text: "Enter a new name",
    input: true,
    defaultValue: segment.name,
    onOk: (val) => {
      if (!val) return;
      segment.name = val;
      saveStudyData();
      renderSegments();
      renderAllNotes();
    }
  });
}


function editNote(segmentId, noteId) {
  const segment = studySegments.find(s => s.id === segmentId);
  if (!segment) return;

  const note = segment.notes.find(n => n.id === noteId);
  if (!note) return;

  openDialog({
    title: "Edit Note Title",
    text: "Update note name",
    input: true,
    defaultValue: note.name,
    onOk: (newName) => {
      if (newName) note.name = newName;

      openDialog({
        title: "Edit Note Content",
        text: "Update note content",
        input: true,
        defaultValue: note.content,
        onOk: (newContent) => {
          if (newContent !== null) note.content = newContent;
          saveStudyData();
          showNotesForSegment(segmentId);
          renderAllNotes();
        }
      });
    }
  });
}

function openDialog({ title, text, input = false, defaultValue = "", onOk }) {
  const dlg = document.getElementById("miniDialog");
  const dlgTitle = document.getElementById("dlgTitle");
  const dlgText = document.getElementById("dlgText");
  const dlgInput = document.getElementById("dlgInput");
  const dlgOk = document.getElementById("dlgOk");

  dlgTitle.innerText = title;
  dlgText.innerText = text;

  if (input) {
    dlgInput.style.display = "block";
    dlgInput.value = defaultValue;
    setTimeout(() => dlgInput.focus(), 50);
  } else {
    dlgInput.style.display = "none";
  }

  dlgOk.onclick = () => {
    closeDialog();
    onOk(input ? dlgInput.value.trim() : true);
  };

  dlg.style.display = "flex";
}

function closeDialog() {
  document.getElementById("miniDialog").style.display = "none";
}


function deleteSegment(segmentId) {
  openDialog({
    title: "Delete Segment",
    text: "This will delete the segment and all its notes.",
    onOk: () => {
      studySegments = studySegments.filter(s => s.id !== segmentId);
      saveStudyData();
      renderSegments();
      renderAllNotes();

      if (currentEditingSegment === segmentId) {
        document.getElementById('notesSection').style.display = 'none';
        currentEditingSegment = null;
      }
    }
  });
}

function deleteNote(segmentId, noteId) {
  const segment = studySegments.find(s => s.id === segmentId);
  if (!segment) return;

  openDialog({
    title: "Delete Note",
    text: "This action cannot be undone.",
    onOk: () => {
      segment.notes = segment.notes.filter(n => n.id !== noteId);
      saveStudyData();
      showNotesForSegment(segmentId);
      renderAllNotes();
    }
  });
}

function renderAllNotes() {
  const container = document.getElementById('allNotesList');
  container.innerHTML = '';

  let hasNotes = false;

  studySegments.forEach(segment => {
    if (segment.notes.length > 0) {
      hasNotes = true;

      const segmentHeader = document.createElement('div');
      segmentHeader.innerHTML = `<h5 style="margin: 10px 0; color: #4CAF50;">${segment.name}</h5>`;
      container.appendChild(segmentHeader);

      segment.notes.forEach(note => {
        const noteEl = document.createElement('div');
        noteEl.className = 'note-item';
        noteEl.innerHTML = `
          <div style="flex: 1;">
            <strong>${note.name}</strong>
            <div class="note-content">${note.content.substring(0, 80)}${note.content.length > 80 ? '...' : ''}</div>
          </div>
          <div>
            <button class="edit-btn" onclick="editNote('${segment.id}', '${note.id}')">Edit</button>
            <button class="delete-btn" onclick="deleteNote('${segment.id}', '${note.id}')">Delete</button>
          </div>
        `;
        container.appendChild(noteEl);
      });
    }
  });

  if (!hasNotes) {
    container.innerHTML = '<p style="color: #888; text-align: center;">No notes yet. Add notes to segments first!</p>';
  }
}

// Expose segments to Android for study mode
// Expose segments to Android for study mode
window.getStudySegments = function() {
  console.log("getStudySegments called, returning:", studySegments);
  return studySegments;
};
function testStudyMode() {
  console.log("Current studySegments:", studySegments);
  console.log("Number of segments:", studySegments.length);

  if (studySegments.length > 0) {
    console.log("First segment:", studySegments[0]);
    console.log("First segment notes:", studySegments[0].notes);
  }

  // Test the getStudySegments function
  console.log("getStudySegments() returns:", window.getStudySegments());
  console.log("Type of return:", typeof window.getStudySegments());
}

/* ================= PANELS ================= */

function toggleSettings() {
  closePanels();
  document.getElementById("settingsPanel").classList.add("open");
  document.getElementById("overlay").classList.add("show");
}

function toggleDownloads() {
  closePanels();
  document.getElementById("downloadPanel").classList.add("open");
  document.getElementById("overlay").classList.add("show");
}

function toggleProfile() {
  closePanels();
  document.getElementById("profilePanel").classList.add("open");
  document.getElementById("overlay").classList.add("show");
}

function closePanels() {
  ["settingsPanel", "downloadPanel", "profilePanel", "studyPanel"].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.classList.remove("open");
  });

  document.getElementById("overlay")?.classList.remove("show");
}

function logout() {
  logoutWithReset();
}

// Logout: reset device on backend + clear local data
async function logoutWithReset() {
  const email = localStorage.getItem("userEmail");

  // Try to reset device on backend (ignore errors - just best effort)
  if (email) {
    try {
      await fetch(`${API_BASE}/api/reset-device`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email })
      });
    } catch (e) {
      // Ignore - logout anyway
    }
  }

  // Clear local data
  callActive = false;
  window.AndroidBridge?.endCall?.();

  const settings = localStorage.getItem("appSettings");
  const freeTimeKey = getTodayKey();
  const freeTime = localStorage.getItem(freeTimeKey);
  const studyData = localStorage.getItem("studySegments");

  localStorage.clear();

  if (settings) {
    localStorage.setItem("appSettings", settings);
  }
  if (freeTime) {
    localStorage.setItem(freeTimeKey, freeTime);
  }
  if (studyData) {
    localStorage.setItem("studySegments", studyData);
  }

  // Clear license data via Android bridge
  if (typeof AndroidBridge !== "undefined" && AndroidBridge.clearLicense) {
    AndroidBridge.clearLicense();
  }

  licenseValid = false;
  showAuth();
}

// Keep for backward compatibility
function logoutFull() {
  logoutWithReset();
}

// Keep for backward compatibility
async function resetDeviceAndLogout() {
  logoutWithReset();
}

function downloadModel() {
  if (window.AndroidBridge?.isModelDownloaded?.()) return;

  const bar = document.getElementById("llmProgressBar");
  const pct = document.getElementById("llmPct");

  bar.style.width = "0%";
  pct.innerText = "0%";

  document.getElementById("llmProgress").style.display = "block";
  window.AndroidBridge?.startModelDownload?.();
}

function downloadSTTModel() {
  if (window.AndroidBridge?.isSTTDownloaded?.()) return;
  document.getElementById("sttProgress").style.display = "block";
  window.AndroidBridge?.startSTTDownload?.();
}

// Android → JS callbacks
window.onLLMDownloadProgress = pct => {
  document.getElementById("llmProgressBar").style.width = pct + "%";
  document.getElementById("llmPct").innerText = pct + "%";
};

window.onSTTDownloadProgress = pct => {
  document.getElementById("sttProgressBar").style.width = pct + "%";
  document.getElementById("sttPct").innerText = pct + "%";
};

window.onModelDownloaded = () => {
  document.getElementById("llmStatus").innerText = "Downloaded";
  document.getElementById("llmProgress").style.display = "none";
  document.getElementById("llmBtn").style.display = "none";
};

window.onSTTDownloadDone = () => {
  document.getElementById("sttStatus").innerText = "Downloaded";
  document.getElementById("sttProgress").style.display = "none";
  document.getElementById("sttBtn").style.display = "none";
};

/* ================= USER UI ================= */

function updateUIWithUser(data) {
  document.getElementById("userName").innerText = data.user.name;
  document.getElementById("profName").innerText = data.user.name;
  document.getElementById("profEmail").innerText = data.user.email;

  const planNameEl = document.getElementById("planName");
  const planExpiryEl = document.getElementById("planExpiry");

  planNameEl.innerText = data.plan.name.toUpperCase();

  if (data.plan.name.toUpperCase() === "FREE") {
    planExpiryEl.innerText = "2 min / day";
    return;
  }

  planExpiryEl.innerText = formatPlanExpiry(data.plan.expiresAt);
}

function formatPlanExpiry(expiresAt) {
  if (!expiresAt) return "Unlimited";

  const d = new Date(expiresAt);
  if (isNaN(d.getTime())) return "Unlimited";

  return d.toLocaleDateString(undefined, {
    year: "numeric",
    month: "long",
    day: "numeric"
  });
}


/* ================= CALL AI ================= */
function toggleCall() {
  // 🔒 LICENSE CHECK: Block if license invalid
  if (typeof AndroidBridge !== "undefined" && AndroidBridge.isLicenseValid) {
    if (!AndroidBridge.isLicenseValid()) {
      showLicenseScreen("Your subscription has expired. Please renew to use voice features.");
      return;
    }
  }

  loadFreeTime();

  if (isFreeUser() && freeLimitLocked) {
    showFreeLimitPopup();
    return;
  }

  callActive = !callActive;

  if (callActive) {
    freeCallStartTs = Date.now();
    startFreeTimerIfNeeded();
    updateFreeTimerUI();
    window.AndroidBridge?.startCall?.();
    setStatus("Listening for wake word…");
    window.AndroidBridge?.resumeListening?.();
  } else {
    stopFreeTimer();
    window.AndroidBridge?.endCall?.();
    setStatus("Call ended");
    window.AndroidBridge?.pauseListening?.();
  }

  updateCallButton();
}

function isFreeUser() {
  return document.getElementById("planName")?.innerText === "FREE";
}

function startFreeTimerIfNeeded() {
  if (!isFreeUser()) return;
  if (freeTimerRunning) return;

  freeTimerRunning = true;

  freeTimerInterval = setInterval(() => {
    updateFreeTimerUI();

    let used = freeTimeUsedToday;
    if (freeCallStartTs) {
      used += Date.now() - freeCallStartTs;
    }

    if (used >= FREE_DAILY_LIMIT_MS) {
      freeTimeUsedToday = FREE_DAILY_LIMIT_MS;
      saveFreeTime();
      stopFreeTimer(true);
      forceEndFreeCall();
    }
  }, 1000);
}

function stopFreeTimer(force = false) {
  if (!freeTimerRunning) return;

  freeTimerRunning = false;
  clearInterval(freeTimerInterval);

  if (!force && freeCallStartTs) {
    const sessionUsed = Date.now() - freeCallStartTs;
    freeTimeUsedToday += sessionUsed;
    saveFreeTime();
  }

  freeCallStartTs = null;
  updateFreeTimerUI();
}

function forceEndFreeCall() {
  freeTimeUsedToday = FREE_DAILY_LIMIT_MS;
  freeLimitLocked = true;
  saveFreeTime();

  callActive = false;

  window.AndroidBridge?.forceEndCallFromJS?.();
  updateCallButton();

  showFreeLimitPopup();
}

function updateCallButton() {
  const btn = document.getElementById("callAiBtn");
  if (!btn) return;

  loadFreeTime();

  if (isFreeUser() && freeLimitLocked) {
    btn.innerText = "⛔ Free Limit Reached";
    btn.disabled = true;
    btn.style.opacity = "0.6";
    btn.style.pointerEvents = "none";
    return;
  }

  btn.disabled = false;
  btn.style.pointerEvents = "auto";
  btn.style.opacity = "1";

  btn.innerText = callActive ? "End Call" : "Call AI";
  btn.classList.toggle("active", callActive);
}

/* ================= WAKE WORD ================= */

window.startVoiceFromWake = () => {
  if (!callActive) return;
  askWithSpeech();
};

function askWithSpeech() {
  setStatus("Listening…");
  const res = window.AndroidBridge?.askWithSpeech?.();
  if (res) document.getElementById("response").innerText = res;
}

function continueFree() {
  localStorage.removeItem("authToken");
  localStorage.removeItem("userId");
  localStorage.removeItem("userEmail");
  localStorage.removeItem("userProfile");

  const guestData = {
    user: {
      id: "guest_" + Date.now(),
      name: "Guest User",
      email: "free@guest.com"
    },
    plan: {
      name: "FREE",
      expiresAt: "2099-01-01"
    }
  };

  localStorage.setItem("userProfile", JSON.stringify(guestData));
  localStorage.setItem("isGuest", "true");

  updateUIWithUser(guestData);
  showMainApp();
}

function setStatus(text) {
  const el = document.getElementById("status");
  if (el) el.innerText = text;
}

/* ================= MAGIC WORDS ================= */

function toggleMagicWords(btn) {
  const list = document.getElementById("magicWordsList");
  if (!list) return;

  const isHidden =
    list.style.display === "none" || list.style.display === "";

  if (isHidden) {
    renderMagicWords();
    list.style.display = "flex";
    btn.innerText = "Hide Magic Words";
  } else {
    list.style.display = "none";
    btn.innerText = "Show Magic Words";
  }
}

function renderMagicWords() {
  const list = document.getElementById("magicWordsList");
  list.innerHTML = "";
  magicWords.forEach(m => {
    const s = document.createElement("span");
    s.className = "magic-word";
    s.innerText = m.word;
    s.title = m.action;
    list.appendChild(s);
  });
}

function showFreeLimitPopup() {
  const popup = document.getElementById("freeLimitPopup");
  if (popup) popup.style.display = "flex";
}

function closeFreeLimitPopup() {
  const popup = document.getElementById("freeLimitPopup");
  if (popup) popup.style.display = "none";
}

function updateSpeedDisplay(value) {
  document.getElementById("speedVal").innerText = value + "x";
}