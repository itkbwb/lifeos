const state = {
  version: window.LIFEOS_BOOTSTRAP.version,
  selectedDate: window.LIFEOS_BOOTSTRAP.today,
  planEndDate: window.LIFEOS_BOOTSTRAP.planEndDate,
  blocks: window.LIFEOS_BOOTSTRAP.blocks || [],
  week: window.LIFEOS_BOOTSTRAP.week || [],
  projectStats: window.LIFEOS_BOOTSTRAP.projectStats || [],
  projects: window.LIFEOS_BOOTSTRAP.projects || [],
  installPrompt: null
};

const els = {
  clock: document.getElementById("clock"),
  todayLabel: document.getElementById("today-label"),
  daysToPlan: document.getElementById("days-to-plan"),
  missionCard: document.getElementById("mission-card"),
  missionStatus: document.getElementById("mission-status"),
  missionWindow: document.getElementById("mission-window"),
  progressOrbit: document.getElementById("progress-orbit"),
  missionTimer: document.getElementById("mission-timer"),
  missionProject: document.getElementById("mission-project"),
  missionTitle: document.getElementById("mission-title"),
  missionNotes: document.getElementById("mission-notes"),
  completeButton: document.getElementById("complete-button"),
  skipButton: document.getElementById("skip-button"),
  nextTitle: document.getElementById("next-title"),
  nextTime: document.getElementById("next-time"),
  dayScore: document.getElementById("day-score"),
  dayScoreFill: document.getElementById("day-score-fill"),
  completedCount: document.getElementById("completed-count"),
  remainingCount: document.getElementById("remaining-count"),
  workHours: document.getElementById("work-hours"),
  timeline: document.getElementById("timeline"),
  weekGrid: document.getElementById("week-grid"),
  weekTotal: document.getElementById("week-total"),
  projectList: document.getElementById("project-list"),
  addBlockButton: document.getElementById("add-block-button"),
  blockDialog: document.getElementById("block-dialog"),
  blockForm: document.getElementById("block-form"),
  closeDialogButton: document.getElementById("close-dialog-button"),
  dialogEyebrow: document.getElementById("dialog-eyebrow"),
  dialogTitle: document.getElementById("dialog-title"),
  blockId: document.getElementById("block-id"),
  blockTitle: document.getElementById("block-title"),
  blockDate: document.getElementById("block-date"),
  blockType: document.getElementById("block-type"),
  blockStart: document.getElementById("block-start"),
  blockEnd: document.getElementById("block-end"),
  blockProject: document.getElementById("block-project"),
  blockNotes: document.getElementById("block-notes"),
  deleteBlockButton: document.getElementById("delete-block-button"),
  installButton: document.getElementById("install-button"),
  connectionDot: document.getElementById("connection-dot"),
  toast: document.getElementById("toast")
};

const typeLabels = {
  work: "Работа",
  routine: "Рутина",
  meal: "Еда",
  health: "Здоровье",
  travel: "Дорога",
  life: "Быт",
  recovery: "Восстановление"
};

const typeColors = {
  work: "#ad91ff",
  routine: "#d9ccff",
  meal: "#f0bf73",
  health: "#79d6a5",
  travel: "#a7b4ca",
  life: "#d5a8d8",
  recovery: "#7e82a8"
};

function parseLocalDate(dateString) {
  const [year, month, day] = dateString.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function parseDateTime(dateString, timeString) {
  const date = parseLocalDate(dateString);
  const [hours, minutes] = timeString.split(":").map(Number);
  date.setHours(hours, minutes, 0, 0);
  return date;
}

function formatDuration(milliseconds) {
  const total = Math.max(0, Math.floor(milliseconds / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;

  return [hours, minutes, seconds]
    .map(value => String(value).padStart(2, "0"))
    .join(":");
}

function formatHours(minutes) {
  const value = minutes / 60;
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

function escapeHtml(value) {
  const div = document.createElement("div");
  div.textContent = value ?? "";
  return div.innerHTML;
}

function getCurrentAndNext() {
  const now = new Date();
  let current = null;
  let next = null;

  for (const block of state.blocks) {
    const start = parseDateTime(block.block_date, block.start_time);
    const end = parseDateTime(block.block_date, block.end_time);

    if (now >= start && now < end) {
      current = block;
      continue;
    }

    if (start > now && next === null) {
      next = block;
    }
  }

  return { current, next };
}

function dayMetrics() {
  const totalBlocks = state.blocks.filter(block => block.block_type !== "recovery");
  const completed = totalBlocks.filter(block => block.status === "completed");
  const skipped = totalBlocks.filter(block => block.status === "skipped");
  const remaining = totalBlocks.length - completed.length - skipped.length;
  const workMinutes = state.blocks
    .filter(block => block.block_type === "work")
    .reduce((sum, block) => sum + block.duration_minutes, 0);

  const score = totalBlocks.length
    ? Math.round((completed.length / totalBlocks.length) * 100)
    : 0;

  return {
    score,
    completed: completed.length,
    remaining: Math.max(0, remaining),
    workMinutes
  };
}

function renderClock() {
  const now = new Date();
  els.clock.textContent = now.toLocaleTimeString("ru-RU", {
    hour: "2-digit",
    minute: "2-digit"
  });

  els.todayLabel.textContent = now
    .toLocaleDateString("ru-RU", {
      weekday: "long",
      day: "numeric",
      month: "long"
    })
    .toUpperCase();

  const planEnd = parseLocalDate(state.planEndDate);
  planEnd.setHours(23, 59, 59, 999);
  const diff = Math.ceil((planEnd - now) / 86400000);
  els.daysToPlan.textContent = Math.max(0, diff);
}

function renderMission() {
  const now = new Date();
  const { current, next } = getCurrentAndNext();

  if (!current) {
    els.missionStatus.textContent = next ? "ПАУЗА МЕЖДУ БЛОКАМИ" : "ДЕНЬ ЗАВЕРШЕН";
    els.missionWindow.textContent = next ? `следующий в ${next.start_time}` : "план выполнен";
    els.missionProject.textContent = "LIFE OS";
    els.missionTitle.textContent = next ? "Подготовься к следующему блоку" : "Отдых";
    els.missionNotes.textContent = next
      ? next.title
      : "Следующий рабочий день уже сохранен на сервере";
    els.missionTimer.textContent = next
      ? formatDuration(parseDateTime(next.block_date, next.start_time) - now)
      : "00:00:00";
    els.progressOrbit.style.setProperty("--progress", "0deg");
    els.completeButton.disabled = true;
    els.skipButton.disabled = true;
  } else {
    const start = parseDateTime(current.block_date, current.start_time);
    const end = parseDateTime(current.block_date, current.end_time);
    const total = end - start;
    const elapsed = Math.min(total, Math.max(0, now - start));
    const progress = total > 0 ? elapsed / total : 0;

    els.missionStatus.textContent = current.status === "planned"
      ? "ТЕКУЩИЙ БЛОК"
      : current.status === "completed"
        ? "ЗАВЕРШЕНО"
        : "ПРОПУЩЕНО";

    els.missionWindow.textContent = `${current.start_time} • ${current.end_time}`;
    els.missionProject.textContent = current.project_name || typeLabels[current.block_type] || "LIFE OS";
    els.missionTitle.textContent = current.title;
    els.missionNotes.textContent = current.notes || "Работай только над этим блоком";
    els.missionTimer.textContent = formatDuration(end - now);
    els.progressOrbit.style.setProperty("--progress", `${progress * 360}deg`);

    els.completeButton.disabled = current.status === "completed";
    els.skipButton.disabled = current.status === "skipped";
    els.completeButton.onclick = () => setBlockAction(current.id, "completed");
    els.skipButton.onclick = () => setBlockAction(current.id, "skipped");
  }

  els.nextTitle.textContent = next ? next.title : "План на сегодня завершен";
  els.nextTime.textContent = next ? next.start_time : "•";
}

function renderDayScore() {
  const metrics = dayMetrics();

  els.dayScore.textContent = `${metrics.score}%`;
  els.dayScoreFill.style.width = `${metrics.score}%`;
  els.completedCount.textContent = metrics.completed;
  els.remainingCount.textContent = metrics.remaining;
  els.workHours.textContent = formatHours(metrics.workMinutes);
}

function renderTimeline() {
  const { current } = getCurrentAndNext();
  els.timeline.innerHTML = "";

  for (const block of state.blocks) {
    const item = document.createElement("article");
    const isCurrent = current && current.id === block.id;
    item.className = [
      "timeline-item",
      block.status,
      isCurrent ? "current" : ""
    ].filter(Boolean).join(" ");

    item.style.setProperty(
      "--type-color",
      typeColors[block.block_type] || typeColors.work
    );

    const statusSymbol = block.status === "completed"
      ? "✓"
      : block.status === "skipped"
        ? "×"
        : isCurrent
          ? "●"
          : "";

    item.innerHTML = `
      <div class="timeline-time">
        <strong>${escapeHtml(block.start_time)}</strong><br>
        ${escapeHtml(block.end_time)}
      </div>
      <div class="timeline-main">
        <div class="timeline-title">${escapeHtml(block.title)}</div>
        <div class="timeline-meta">
          ${escapeHtml(block.project_name || typeLabels[block.block_type] || block.block_type)}
          · ${formatHours(block.duration_minutes)} ч
        </div>
      </div>
      <div class="timeline-status">${statusSymbol}</div>
    `;

    item.addEventListener("click", () => openEditDialog(block));
    els.timeline.appendChild(item);
  }
}

function renderWeek() {
  els.weekGrid.innerHTML = "";

  const maxMinutes = Math.max(
    1,
    ...state.week.map(day => day.productive_minutes)
  );

  const total = state.week.reduce(
    (sum, day) => sum + day.productive_minutes,
    0
  );
  els.weekTotal.textContent = `${formatHours(total)} ч`;

  for (const day of state.week) {
    const date = parseLocalDate(day.date);
    const plannedHeight = Math.max(
      12,
      Math.round((day.productive_minutes / maxMinutes) * 100)
    );
    const completedHeight = day.productive_minutes
      ? Math.round((day.completed_minutes / day.productive_minutes) * 100)
      : 0;

    const card = document.createElement("article");
    card.className = "week-card";
    card.innerHTML = `
      <div class="week-day">
        <strong>${date.toLocaleDateString("ru-RU", { weekday: "short" })}</strong>
        <span>${date.getDate()}</span>
      </div>
      <div class="week-bar-wrap">
        <div class="week-bar" style="--bar-height:${plannedHeight}%">
          <div class="week-bar-fill" style="--fill-height:${completedHeight}%"></div>
        </div>
      </div>
      <div class="week-hours">${formatHours(day.productive_minutes)}ч</div>
    `;

    els.weekGrid.appendChild(card);
  }
}

function renderProjects() {
  els.projectList.innerHTML = "";

  const maxScheduled = Math.max(
    1,
    ...state.projectStats.map(project => project.scheduled_minutes)
  );

  for (const project of state.projectStats) {
    const plannedPercent = Math.round(
      (project.scheduled_minutes / maxScheduled) * 100
    );
    const completionPercent = project.scheduled_minutes
      ? Math.round((project.completed_minutes / project.scheduled_minutes) * 100)
      : 0;

    const card = document.createElement("article");
    card.className = "project-card";
    card.innerHTML = `
      <div class="project-topline">
        <div>
          <h3>${escapeHtml(project.name)}</h3>
          <div class="project-category">${escapeHtml(project.category)}</div>
        </div>
        <div class="project-priority">${project.priority}</div>
      </div>

      <div class="project-progress">
        <div
          class="project-progress-fill"
          style="width:${Math.max(completionPercent, project.scheduled_minutes ? 3 : 0)}%"
        ></div>
      </div>

      <div class="project-metrics">
        <span><strong>${formatHours(project.scheduled_minutes)} ч</strong> запланировано</span>
        <span>${project.block_count} блоков</span>
      </div>
    `;

    card.style.opacity = project.scheduled_minutes ? "1" : ".56";
    els.projectList.appendChild(card);
  }
}

function renderAll() {
  renderClock();
  renderMission();
  renderDayScore();
  renderTimeline();
  renderWeek();
  renderProjects();
}

async function refreshDashboard() {
  try {
    const response = await fetch(`/api/dashboard?block_date=${state.selectedDate}`, {
      cache: "no-store"
    });
    if (!response.ok) throw new Error("Dashboard request failed");

    const payload = await response.json();
    state.blocks = payload.blocks;
    state.week = payload.week;
    state.projectStats = payload.projects;
    state.planEndDate = payload.plan_end_date;
    updateConnectionStatus(true);
    renderAll();
  } catch (error) {
    console.error(error);
    updateConnectionStatus(false);
    showToast("Сервер недоступен. Показаны последние данные.");
  }
}

async function setBlockAction(blockId, action) {
  try {
    const body = new URLSearchParams({ action });
    const response = await fetch(`/api/blocks/${blockId}/action`, {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded"
      },
      body
    });

    if (!response.ok) throw new Error("Action failed");

    const block = state.blocks.find(item => item.id === blockId);
    if (block) block.status = action;

    showToast(action === "completed" ? "Блок завершен" : "Блок пропущен");
    await refreshDashboard();
  } catch (error) {
    console.error(error);
    showToast("Не удалось сохранить действие");
  }
}

function populateProjectSelect() {
  els.blockProject.innerHTML = '<option value="">Без проекта</option>';

  for (const project of state.projects) {
    const option = document.createElement("option");
    option.value = String(project.id);
    option.textContent = `${project.priority} · ${project.name}`;
    els.blockProject.appendChild(option);
  }
}

function resetDialog() {
  els.blockForm.reset();
  els.blockId.value = "";
  els.blockDate.value = state.selectedDate;
  els.blockType.value = "work";
  els.blockProject.value = "";
  els.deleteBlockButton.classList.add("hidden");
  els.dialogEyebrow.textContent = "НОВЫЙ БЛОК";
  els.dialogTitle.textContent = "Добавить в план";
}

function openCreateDialog() {
  resetDialog();

  const now = new Date();
  const startMinutes = Math.ceil((now.getHours() * 60 + now.getMinutes()) / 15) * 15;
  const endMinutes = startMinutes + 60;

  els.blockStart.value = minutesToTime(startMinutes);
  els.blockEnd.value = minutesToTime(endMinutes);
  els.blockDialog.showModal();
}

function openEditDialog(block) {
  resetDialog();

  els.blockId.value = block.id;
  els.blockTitle.value = block.title;
  els.blockDate.value = block.block_date;
  els.blockType.value = block.block_type;
  els.blockStart.value = block.start_time;
  els.blockEnd.value = block.end_time;
  els.blockProject.value = block.project_id ? String(block.project_id) : "";
  els.blockNotes.value = block.notes || "";
  els.deleteBlockButton.classList.remove("hidden");
  els.dialogEyebrow.textContent = "РЕДАКТИРОВАНИЕ";
  els.dialogTitle.textContent = block.title;
  els.blockDialog.showModal();
}

function minutesToTime(totalMinutes) {
  const normalized = ((totalMinutes % 1440) + 1440) % 1440;
  const hours = Math.floor(normalized / 60);
  const minutes = normalized % 60;
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
}

async function saveBlock(event) {
  event.preventDefault();

  const blockId = els.blockId.value;
  const payload = {
    block_date: els.blockDate.value,
    start_time: els.blockStart.value,
    end_time: els.blockEnd.value,
    title: els.blockTitle.value.trim(),
    notes: els.blockNotes.value.trim() || null,
    block_type: els.blockType.value,
    project_id: els.blockProject.value
      ? Number(els.blockProject.value)
      : null
  };

  if (!payload.title) {
    showToast("Укажи название блока");
    return;
  }

  try {
    const response = await fetch(
      blockId ? `/api/blocks/${blockId}` : "/api/blocks",
      {
        method: blockId ? "PATCH" : "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify(payload)
      }
    );

    if (!response.ok) {
      const body = await response.json().catch(() => null);
      throw new Error(body?.detail || "Save failed");
    }

    els.blockDialog.close();
    showToast(blockId ? "Блок обновлен" : "Блок добавлен");
    await refreshDashboard();
  } catch (error) {
    console.error(error);
    showToast(error.message || "Не удалось сохранить блок");
  }
}

async function deleteBlock() {
  const blockId = els.blockId.value;
  if (!blockId) return;

  const confirmed = window.confirm("Удалить этот блок?");
  if (!confirmed) return;

  try {
    const response = await fetch(`/api/blocks/${blockId}`, {
      method: "DELETE"
    });
    if (!response.ok) throw new Error("Delete failed");

    els.blockDialog.close();
    showToast("Блок удален");
    await refreshDashboard();
  } catch (error) {
    console.error(error);
    showToast("Не удалось удалить блок");
  }
}

function showToast(message, timeout = 2500) {
  els.toast.textContent = message;
  els.toast.classList.remove("hidden");

  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => {
    els.toast.classList.add("hidden");
  }, timeout);
}

function updateConnectionStatus(isOnline) {
  els.connectionDot.classList.toggle("offline", !isOnline);
  els.connectionDot.title = isOnline ? "Сервер доступен" : "Нет соединения с сервером";
}

function observeSections() {
  const links = [...document.querySelectorAll(".rail-link")];
  const sections = links
    .map(link => document.querySelector(link.getAttribute("href")))
    .filter(Boolean);

  const observer = new IntersectionObserver(
    entries => {
      const visible = entries
        .filter(entry => entry.isIntersecting)
        .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];

      if (!visible) return;

      for (const link of links) {
        link.classList.toggle(
          "active",
          link.getAttribute("href") === `#${visible.target.id}`
        );
      }
    },
    {
      rootMargin: "-25% 0px -60% 0px",
      threshold: [0, .25, .5, .75]
    }
  );

  sections.forEach(section => observer.observe(section));
}

function setupInstallPrompt() {
  window.addEventListener("beforeinstallprompt", event => {
    event.preventDefault();
    state.installPrompt = event;
    els.installButton.classList.remove("hidden");
  });

  els.installButton.addEventListener("click", async () => {
    if (!state.installPrompt) return;

    state.installPrompt.prompt();
    await state.installPrompt.userChoice;
    state.installPrompt = null;
    els.installButton.classList.add("hidden");
  });
}

function setupServiceWorker() {
  if (!("serviceWorker" in navigator)) return;

  navigator.serviceWorker.register(`/static/sw.js?v=${state.version}`)
    .then(registration => {
      registration.addEventListener("updatefound", () => {
        const worker = registration.installing;
        if (!worker) return;

        worker.addEventListener("statechange", () => {
          if (
            worker.state === "installed" &&
            navigator.serviceWorker.controller
          ) {
            showToast("Новая версия готова. Перезапускаю приложение.", 1400);
            window.setTimeout(() => window.location.reload(), 1500);
          }
        });
      });
    })
    .catch(error => console.error("Service worker failed", error));
}

els.addBlockButton.addEventListener("click", openCreateDialog);
els.closeDialogButton.addEventListener("click", () => els.blockDialog.close());
els.blockForm.addEventListener("submit", saveBlock);
els.deleteBlockButton.addEventListener("click", deleteBlock);

window.addEventListener("online", () => {
  updateConnectionStatus(true);
  refreshDashboard();
});
window.addEventListener("offline", () => updateConnectionStatus(false));

populateProjectSelect();
observeSections();
setupInstallPrompt();
setupServiceWorker();
renderAll();

window.setInterval(() => {
  renderClock();
  renderMission();
  renderDayScore();
}, 1000);

window.setInterval(refreshDashboard, 60000);
