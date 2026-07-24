const app = document.getElementById("app");
const logoutButton = document.getElementById("logoutButton");
const adminLink = document.getElementById("adminLink");
let me = null;
let csrfToken = null;
let ws = null;
let currentGame = null;
let selectedPieceId = null;
let lastMove = null;
let pending = false;
let renderTimer = null;
let boardFlipped = false;

const glyphs = {
  WHITE_KING: "\u2654", WHITE_QUEEN: "\u2655", WHITE_ROOK: "\u2656", WHITE_BISHOP: "\u2657", WHITE_KNIGHT: "\u2658", WHITE_PAWN: "\u2659",
  BLACK_KING: "\u265a", BLACK_QUEEN: "\u265b", BLACK_ROOK: "\u265c", BLACK_BISHOP: "\u265d", BLACK_KNIGHT: "\u265e", BLACK_PAWN: "\u265f"
};

async function api(path, options = {}) {
  const headers = Object.assign({"Accept": "application/json"}, options.headers || {});
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (csrfToken && options.method && options.method !== "GET") headers["X-CSRF-Token"] = csrfToken;
  const response = await fetch(path, Object.assign({}, options, {headers, credentials: "same-origin"}));
  if (!response.ok) {
    const text = await response.text();
    let error;
    try {
      error = JSON.parse(text);
    } catch (_) {
      error = {code: "HTTP_ERROR", message: text || response.statusText};
    }
    error.status = response.status;
    throw error;
  }
  return response.json();
}

async function loadMe() {
  try {
    const data = await api("/api/me");
    me = data.user;
    csrfToken = data.csrfToken;
    logoutButton.classList.remove("hidden");
    adminLink.classList.toggle("hidden", me.role !== "ADMIN");
  } catch (_) {
    me = null;
    csrfToken = null;
    logoutButton.classList.add("hidden");
    adminLink.classList.add("hidden");
  }
}

logoutButton.onclick = async () => {
  await api("/auth/logout", {method: "POST", body: "{}"}).catch(() => {});
  location.href = "/";
};

function loginView() {
  app.innerHTML = `
    <section class="grid dashboard">
      <div>
        <h1>Continuous Chess</h1>
        <p class="muted">연속 좌표 기물이 있는 1대1 체스 변형입니다.</p>
        <a class="button primary" href="/auth/google/login">Google 로그인</a>
      </div>
      <div class="card">
        <h2>구현 상태</h2>
        <p>서버 권위 이동 판정, Google OAuth, 초대방, WebSocket, SQLite 전적 저장을 제공합니다.</p>
        <p class="notice">현재 버전은 체크를 정확히 판정하지만, 체크메이트와 스테일메이트를 자동 판정하지 않습니다.</p>
      </div>
    </section>`;
}

async function dashboard() {
  const [stats, matches] = await Promise.all([
    api("/api/me/stats").catch(() => null),
    api("/api/me/matches?page=0&size=5").catch(() => ({items: []}))
  ]);
  app.innerHTML = `
    <section class="grid dashboard">
      <div class="grid">
        <div class="card">
          <div class="profile">
            ${me.profileImageUrl ? `<img class="avatar" src="${me.profileImageUrl}" alt="">` : `<div class="avatar"></div>`}
            <div><strong class="${me.role === "ADMIN" ? "adminName" : ""}">${escapeHtml(me.displayName || "사용자")}</strong><div class="muted">친선전 계정</div></div>
          </div>
          <div class="profileEditor">
            <label>Nickname<input id="displayNameInput" maxlength="32" value="${escapeHtml(me.displayName || "")}"></label>
            <div class="actions">
              <button id="saveProfile" type="button">Save profile</button>
              <label class="button fileButton">Upload avatar<input id="profileImageInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif"></label>
            </div>
            <div id="profileMessage" class="muted"></div>
          </div>
          <div class="actions">
            <button id="createRoom" class="primary" type="button">새 친선전</button>
            <input id="inviteCode" placeholder="초대 코드">
            <button id="joinRoom" type="button">참가</button>
          </div>
        </div>
        <div class="card">
          <h2>AI전</h2>
          <div class="optionGrid">
            <label>내 색상<select id="aiColor"><option value="WHITE">백</option><option value="BLACK">흑</option></select></label>
            <label>난도<select id="aiDifficulty"><option value="EASY">쉬움</option><option value="NORMAL" selected>보통</option><option value="HARD">어려움</option></select></label>
            <label>제한시간(분)<input id="initialMinutes" type="number" min="1" max="1440" value="10"></label>
            <label>증초(초)<input id="incrementSeconds" type="number" min="0" max="3600" value="0"></label>
          </div>
          <div class="actions"><button id="createAiGame" class="primary" type="button">AI전 시작</button></div>
        </div>
      </div>
      <div class="grid">
        ${statsView(stats)}
        <div class="card"><h2>최근 대국</h2>${matchesView(matches.items)}</div>
      </div>
    </section>`;
  document.getElementById("createRoom").onclick = async () => {
    const room = await api("/api/rooms", {method: "POST", body: "{}"});
    location.href = `/game/join/${room.inviteCode}`;
  };
  document.getElementById("saveProfile").onclick = saveProfile;
  document.getElementById("profileImageInput").onchange = uploadProfileImage;
  document.getElementById("createAiGame").onclick = async () => {
    const minutes = Number(document.getElementById("initialMinutes").value || 10);
    const increment = Number(document.getElementById("incrementSeconds").value || 0);
    const body = JSON.stringify({
      userColor: document.getElementById("aiColor").value,
      initialSeconds: Math.max(1, minutes) * 60,
      incrementSeconds: Math.max(0, increment),
      difficulty: document.getElementById("aiDifficulty").value
    });
    const game = await api("/api/ai-games", {method: "POST", body});
    location.href = `/game/${game.gameId}`;
  };
  document.getElementById("joinRoom").onclick = () => {
    const code = document.getElementById("inviteCode").value.trim();
    if (code) location.href = `/game/join/${encodeURIComponent(code)}`;
  };
}

async function saveProfile() {
  const input = document.getElementById("displayNameInput");
  const message = document.getElementById("profileMessage");
  const displayName = input.value.trim();
  if (!displayName || displayName.length > 32) {
    message.textContent = "Nickname must be 1 to 32 characters.";
    return;
  }
  message.textContent = "Saving...";
  try {
    const result = await api("/api/me", {method: "PATCH", body: JSON.stringify({displayName})});
    me = result.user;
    message.textContent = "Saved.";
    await dashboard();
  } catch (error) {
    message.textContent = formatApiError(error, "Profile save failed.");
  }
}

async function uploadProfileImage(event) {
  const file = event.target.files && event.target.files[0];
  const message = document.getElementById("profileMessage");
  if (!file) return;
  if (file.size > 2 * 1024 * 1024) {
    message.textContent = "Avatar image must be up to 2 MB.";
    event.target.value = "";
    return;
  }
  message.textContent = "Uploading...";
  try {
    const dataBase64 = await readFileAsDataUrl(file);
    const result = await api("/api/me/profile-image", {
      method: "POST",
      body: JSON.stringify({fileName: file.name, contentType: file.type, dataBase64})
    });
    me = result.user;
    message.textContent = "Uploaded.";
    await dashboard();
  } catch (error) {
    message.textContent = formatApiError(error, "Avatar upload failed.");
  } finally {
    event.target.value = "";
  }
}

function formatApiError(error, fallback) {
  const parts = [error.status, error.code, error.message].filter(Boolean);
  return parts.length ? parts.join(" ") : fallback;
}

function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ""));
    reader.onerror = () => reject(reader.error || new Error("File read failed."));
    reader.readAsDataURL(file);
  });
}

function statsView(stats) {
  if (!stats) return `<div class="card">전적을 불러올 수 없습니다.</div>`;
  return `<div class="card statbar">
    ${stat("총", stats.totalGames)}${stat("승", stats.wins)}${stat("패", stats.losses)}
    ${stat("무", stats.draws)}${stat("백", stats.gamesAsWhite)}${stat("흑", stats.gamesAsBlack)}
  </div>`;
}

function stat(label, value) {
  return `<div class="stat"><span>${label}</span><strong>${value}</strong></div>`;
}

function matchesView(items) {
  if (!items || items.length === 0) return `<p class="muted">기록된 완료 대국이 없습니다.</p>`;
  return items.map(m => `<div class="row"><span>${escapeHtml(m.opponentDisplayName || "상대")} · ${m.userColor}</span><strong>${m.result}</strong></div>`).join("");
}

async function joinRoomView(inviteCode) {
  const room = await api(`/api/rooms/${encodeURIComponent(inviteCode)}`).catch(() => null);
  if (!room) { app.innerHTML = `<div class="card">방을 찾을 수 없습니다.</div>`; return; }
  app.innerHTML = `
    <section class="card">
      <h1>친선전 대기</h1>
      <p>초대 코드 <span class="code">${room.inviteCode}</span></p>
      <p class="code">${room.inviteLink}</p>
      <div class="actions">
        <button id="copyInvite" type="button">초대 링크 복사</button>
        ${room.hostUserId !== me.userId && room.status === "WAITING" ? `<button id="joinNow" class="primary" type="button">참가</button>` : ""}
        ${room.hostUserId === me.userId && room.status === "WAITING" ? `<button id="cancelRoom" class="danger" type="button">방 취소</button>` : ""}
        ${room.status === "ACTIVE" ? `<a class="button primary" href="/game/${room.gameId}">게임으로 이동</a>` : ""}
      </div>
      <p class="muted">색상 정책: 친선전은 방 생성자가 백입니다.</p>
    </section>`;
  document.getElementById("copyInvite").onclick = () => navigator.clipboard.writeText(room.inviteLink);
  const join = document.getElementById("joinNow");
  if (join) join.onclick = async () => {
    const result = await api(`/api/rooms/${encodeURIComponent(inviteCode)}/join`, {method: "POST", body: "{}"});
    location.href = `/game/${result.room.gameId}`;
  };
  const cancel = document.getElementById("cancelRoom");
  if (cancel) cancel.onclick = async () => { await api(`/api/rooms/${encodeURIComponent(inviteCode)}/cancel`, {method: "POST", body: "{}"}); location.href = "/"; };
  if (room.status === "WAITING") setTimeout(() => joinRoomView(inviteCode), 2500);
}

async function gameView(gameId) {
  const view = await api(`/api/games/${encodeURIComponent(gameId)}`);
  currentGame = view;
  boardFlipped = view.yourColor === "BLACK";
  app.innerHTML = `
    <section class="grid gameLayout">
      <div class="boardWrap"><canvas id="board"></canvas></div>
      <aside class="sidePanel">
        <div class="card">
          <h2>게임</h2>
          ${view.isAiGame ? `<p><strong>AI전</strong> · 난도 ${view.aiDifficulty || "NORMAL"}</p>` : ""}
          <p>내 색상: <strong>${view.yourColor}</strong></p>
          <p>현재 턴: <strong id="turn">${view.state.turn}</strong></p>
          <p>백 <strong id="whiteClock">--:--</strong> · 흑 <strong id="blackClock">--:--</strong></p>
          <div class="actions"><button id="flipBoard" type="button">흑백 전환</button></div>
          <p id="checkLine" class="muted"></p>
          <p class="notice">현재 버전은 체크를 정확히 판정하지만, 체크메이트와 스테일메이트를 자동 판정하지 않습니다.</p>
        </div>
        <div class="card">
          <h2>수식 입력기</h2>
          <p class="muted">예: 3, 1/2, sqrt(5)/2, pi/2, 2+sqrt(3)/4</p>
          <div class="fieldGrid"><input id="targetX" placeholder="x expression"><input id="targetY" placeholder="y expression"></div>
          <div class="segmented"><button data-axis="targetX">x</button><button data-axis="targetY">y</button></div>
          <div id="formulaPad" class="formulaPad"></div>
        </div>
        <div class="card">
          <h2>이동</h2>
          <p id="selected">선택된 기물 없음</p>
          <div class="actions">
            <button id="moveButton" class="primary">이동</button>
            <button id="castleKing">킹사이드 캐슬링</button>
            <button id="castleQueen">퀸사이드 캐슬링</button>
            <button id="clearSelection">선택 취소</button>
          </div>
          <div id="error" class="error"></div>
        </div>
        <div class="card">
          <div class="actions">
            <button id="resign" class="danger">기권</button>
            ${view.isAiGame ? "" : `<button id="offerDraw">무승부 제안</button><button id="acceptDraw">수락</button><button id="declineDraw">거절</button>`}
          </div>
        </div>
        <div class="card"><h2>최근 수</h2><ol id="moves" class="moveList"></ol></div>
      </aside>
    </section>`;
  bindBoard();
  bindFormulaPad();
  renderGame();
  if (renderTimer) clearInterval(renderTimer);
  renderTimer = setInterval(renderGame, 1000);
  connectSocket(gameId);
  document.getElementById("moveButton").onclick = sendMove;
  document.getElementById("flipBoard").onclick = () => { boardFlipped = !boardFlipped; renderGame(); };
  document.getElementById("castleKing").onclick = () => sendCastle("CASTLE_KINGSIDE");
  document.getElementById("castleQueen").onclick = () => sendCastle("CASTLE_QUEENSIDE");
  document.getElementById("clearSelection").onclick = () => { selectedPieceId = null; renderGame(); };
  document.getElementById("resign").onclick = () => wsSend({type: "RESIGN", requestId: crypto.randomUUID(), gameId});
  const offerDraw = document.getElementById("offerDraw");
  const acceptDraw = document.getElementById("acceptDraw");
  const declineDraw = document.getElementById("declineDraw");
  if (offerDraw) offerDraw.onclick = () => wsSend({type: "OFFER_DRAW", requestId: crypto.randomUUID(), gameId});
  if (acceptDraw) acceptDraw.onclick = () => wsSend({type: "ACCEPT_DRAW", requestId: crypto.randomUUID(), gameId});
  if (declineDraw) declineDraw.onclick = () => wsSend({type: "DECLINE_DRAW", requestId: crypto.randomUUID(), gameId});
}

function bindFormulaPad() {
  let activeAxis = "targetX";
  document.querySelectorAll(".segmented button").forEach(button => {
    button.onclick = () => { activeAxis = button.dataset.axis; document.getElementById(activeAxis).focus(); };
  });
  const tokens = [
    ["7", "8", "9", "/", "sqrt()"],
    ["4", "5", "6", "*", "^2"],
    ["1", "2", "3", "-", "pi"],
    ["0", ".", "()", "+", "frac"],
    ["e", "sqrt(5)/2", "sqrt(15)/2", "clear"]
  ];
  document.getElementById("formulaPad").innerHTML = tokens.map(row =>
    `<div>${row.map(token => `<button type="button" data-token="${token}">${token}</button>`).join("")}</div>`
  ).join("");
  document.querySelectorAll("#formulaPad button").forEach(button => {
    button.onclick = () => insertToken(document.getElementById(activeAxis), button.dataset.token);
  });
}

function insertToken(input, token) {
  if (token === "clear") { input.value = ""; input.focus(); return; }
  const map = { "sqrt()": "sqrt()", "()": "()", "frac": "()/()" };
  const text = map[token] || token;
  const start = input.selectionStart ?? input.value.length;
  const end = input.selectionEnd ?? input.value.length;
  input.value = input.value.slice(0, start) + text + input.value.slice(end);
  const cursor = start + (token === "sqrt()" || token === "()" || token === "frac" ? 1 : text.length);
  input.setSelectionRange(cursor, cursor);
  input.focus();
}

function bindBoard() {
  const canvas = document.getElementById("board");
  canvas.onclick = event => {
    const board = screenToBoard(canvas, event.offsetX, event.offsetY);
    let best = null, bestDistance = 0.18;
    for (const p of currentGame.state.pieces) {
      const dx = p.position.x.numericValue - board.x, dy = p.position.y.numericValue - board.y;
      const d = Math.sqrt(dx * dx + dy * dy);
      if (d < bestDistance) { best = p; bestDistance = d; }
    }
    if (best) {
      selectedPieceId = best.id;
      document.getElementById("targetX").value = best.position.x.numericValue;
      document.getElementById("targetY").value = best.position.y.numericValue;
      renderGame();
    }
  };
  window.onresize = renderGame;
}

function connectSocket(gameId) {
  if (ws) ws.close();
  ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws/games/${encodeURIComponent(gameId)}`);
  ws.onmessage = event => {
    const msg = JSON.parse(event.data);
    if (msg.state) currentGame.state = msg.state;
    if (msg.move) lastMove = msg.move;
    if (msg.errorCode || msg.type === "MOVE_REJECTED") document.getElementById("error").textContent = `${msg.errorCode}: ${msg.message || ""}`;
    if (msg.type === "GAME_FINISHED") document.getElementById("error").textContent = "게임이 종료되었습니다.";
    pending = false;
    renderGame();
  };
  ws.onclose = () => setTimeout(() => connectSocket(gameId), 1500);
}

function wsSend(message) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(message));
}

function sendMove() {
  if (!selectedPieceId || pending) return;
  pending = true;
  document.getElementById("error").textContent = "";
  wsSend({
    type: "MOVE",
    requestId: crypto.randomUUID(),
    gameId: currentGame.gameId,
    pieceId: selectedPieceId,
    targetX: document.getElementById("targetX").value,
    targetY: document.getElementById("targetY").value,
    expectedVersion: currentGame.state.version
  });
  setTimeout(() => pending = false, 800);
}

function sendCastle(side) {
  if (pending) return;
  const color = currentGame.yourColor;
  const king = currentGame.state.pieces.find(p => p.type === "KING" && p.color === color);
  if (!king) return;
  const y = color === "WHITE" ? "0" : "7";
  const x = side === "CASTLE_KINGSIDE" ? "6" : "2";
  selectedPieceId = king.id;
  pending = true;
  wsSend({
    type: "MOVE",
    requestId: crypto.randomUUID(),
    gameId: currentGame.gameId,
    pieceId: king.id,
    targetX: x,
    targetY: y,
    expectedVersion: currentGame.state.version,
    specialMove: side
  });
  setTimeout(() => pending = false, 800);
}

function renderGame() {
  if (!currentGame) return;
  const canvas = document.getElementById("board");
  if (!canvas) return;
  const size = Math.floor(canvas.clientWidth * devicePixelRatio);
  canvas.width = size; canvas.height = size;
  const ctx = canvas.getContext("2d");
  drawBoard(ctx, canvas.width, canvas.height);
  const selected = currentGame.state.pieces.find(p => p.id === selectedPieceId);
  if (selected) drawAttackOverlay(ctx, canvas, selected);
  if (lastMove) drawMove(ctx, canvas, lastMove);
  for (const piece of currentGame.state.pieces) drawPiece(ctx, canvas, piece);
  document.getElementById("selected").textContent = selected ? `${selected.id} (${roundCoord(selected.position.x.numericValue)}, ${roundCoord(selected.position.y.numericValue)})` : "선택된 기물 없음";
  document.getElementById("turn").textContent = currentGame.state.turn;
  document.getElementById("checkLine").textContent = [currentGame.state.whiteInCheck ? "백 체크" : "", currentGame.state.blackInCheck ? "흑 체크" : ""].filter(Boolean).join(" · ");
  document.getElementById("moves").innerHTML = currentGame.state.moveHistory.slice(-20).reverse().map(m => `<li>${m.pieceId} → (${m.end.x.normalizedExpression}, ${m.end.y.normalizedExpression})${m.specialMove ? " · " + m.specialMove : ""}</li>`).join("");
  renderClock();
}

function renderClock() {
  const clock = currentGame.state.clock;
  if (!clock) return;
  const white = liveRemaining("WHITE");
  const black = liveRemaining("BLACK");
  document.getElementById("whiteClock").textContent = formatTime(white);
  document.getElementById("blackClock").textContent = formatTime(black);
}

function liveRemaining(color) {
  const clock = currentGame.state.clock;
  const base = color === "WHITE" ? clock.whiteRemainingSeconds : clock.blackRemainingSeconds;
  if (currentGame.state.turn !== color || !clock.turnStartedAt || currentGame.state.status !== "ACTIVE") return base;
  const elapsed = Math.max(0, Math.floor((Date.now() - Date.parse(clock.turnStartedAt)) / 1000));
  return Math.max(0, base - elapsed);
}

function formatTime(seconds) {
  const s = Math.max(0, Number(seconds || 0));
  const minutes = Math.floor(s / 60);
  return `${minutes}:${String(s % 60).padStart(2, "0")}`;
}

function boardToScreen(canvas, x, y) {
  const pad = canvas.width * 0.08;
  const scale = (canvas.width - pad * 2) / 7;
  const bx = boardFlipped ? 7 - x : x;
  const by = boardFlipped ? 7 - y : y;
  return {x: pad + bx * scale, y: canvas.height - pad - by * scale};
}

function screenToBoard(canvas, sx, sy) {
  const scaleFactor = canvas.width / canvas.clientWidth;
  const x = sx * scaleFactor, y = sy * scaleFactor;
  const pad = canvas.width * 0.08;
  const scale = (canvas.width - pad * 2) / 7;
  const bx = (x - pad) / scale;
  const by = (canvas.height - pad - y) / scale;
  return {x: boardFlipped ? 7 - bx : bx, y: boardFlipped ? 7 - by : by};
}

function drawBoard(ctx, w, h) {
  ctx.clearRect(0, 0, w, h);
  const pad = w * 0.08, cell = (w - pad * 2) / 8;
  for (let row = 0; row < 8; row++) for (let col = 0; col < 8; col++) {
    ctx.fillStyle = (row + col) % 2 ? "#e8d8b8" : "#f7f0df";
    ctx.fillRect(pad + col * cell, pad + (7 - row) * cell, cell, cell);
  }
  ctx.strokeStyle = "#243141"; ctx.lineWidth = 2; ctx.strokeRect(pad, pad, cell * 8, cell * 8);
  ctx.fillStyle = "#25313e"; ctx.font = `${Math.max(10, w * 0.022)}px sans-serif`;
  for (let i = 0; i <= 7; i++) {
    const p = boardToScreen({width: w, height: h}, i, i);
    ctx.fillText(String(i), p.x - 3, h - pad / 3);
    ctx.fillText(String(i), pad / 3, p.y + 4);
  }
}

function drawAttackOverlay(ctx, canvas, piece) {
  const p = boardToScreen(canvas, piece.position.x.numericValue, piece.position.y.numericValue);
  ctx.save();
  ctx.strokeStyle = "rgba(31, 122, 109, .55)";
  ctx.fillStyle = "rgba(31, 122, 109, .18)";
  ctx.lineWidth = Math.max(2, canvas.width * 0.004);
  if (piece.type === "ROOK" || piece.type === "QUEEN") {
    lineBoard(ctx, canvas, 0, piece.position.y.numericValue, 7, piece.position.y.numericValue);
    lineBoard(ctx, canvas, piece.position.x.numericValue, 0, piece.position.x.numericValue, 7);
  }
  if (piece.type === "BISHOP" || piece.type === "QUEEN") {
    drawDiagonals(ctx, canvas, piece.position.x.numericValue, piece.position.y.numericValue);
  }
  if (piece.type === "KNIGHT") {
    const q = boardToScreen(canvas, piece.position.x.numericValue + Math.sqrt(5), piece.position.y.numericValue);
    ctx.beginPath(); ctx.arc(p.x, p.y, Math.abs(q.x - p.x), 0, Math.PI * 2); ctx.stroke();
  }
  if (piece.type === "PAWN") {
    const d = piece.color === "WHITE" ? 1 : -1;
    marker(ctx, canvas, piece.position.x.numericValue - 1, piece.position.y.numericValue + d);
    marker(ctx, canvas, piece.position.x.numericValue + 1, piece.position.y.numericValue + d);
  }
  if (piece.type === "KING") {
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) if (dx || dy) marker(ctx, canvas, piece.position.x.numericValue + dx, piece.position.y.numericValue + dy);
  }
  ctx.restore();
}

function lineBoard(ctx, canvas, x1, y1, x2, y2) {
  const a = boardToScreen(canvas, x1, y1);
  const b = boardToScreen(canvas, x2, y2);
  ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
}

function drawDiagonals(ctx, canvas, x, y) {
  const lines = [];
  const t1 = Math.min(7 - x, 7 - y); lines.push([x - Math.min(x, y), y - Math.min(x, y), x + t1, y + t1]);
  const t2 = Math.min(7 - x, y); lines.push([x - Math.min(x, 7 - y), y + Math.min(x, 7 - y), x + t2, y - t2]);
  lines.forEach(l => lineBoard(ctx, canvas, l[0], l[1], l[2], l[3]));
}

function marker(ctx, canvas, x, y) {
  if (x < 0 || x > 7 || y < 0 || y > 7) return;
  const p = boardToScreen(canvas, x, y);
  ctx.beginPath(); ctx.arc(p.x, p.y, canvas.width * 0.018, 0, Math.PI * 2); ctx.fill();
}

function drawMove(ctx, canvas, move) {
  const a = boardToScreen(canvas, move.start.x.numericValue, move.start.y.numericValue);
  const b = boardToScreen(canvas, move.end.x.numericValue, move.end.y.numericValue);
  ctx.strokeStyle = "#c97a12"; ctx.lineWidth = 4; ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
}

function drawPiece(ctx, canvas, piece) {
  const p = boardToScreen(canvas, piece.position.x.numericValue, piece.position.y.numericValue);
  const selected = piece.id === selectedPieceId;
  const checked = piece.type === "KING" && ((piece.color === "WHITE" && currentGame.state.whiteInCheck) || (piece.color === "BLACK" && currentGame.state.blackInCheck));
  ctx.beginPath(); ctx.arc(p.x, p.y, canvas.width * 0.035, 0, Math.PI * 2);
  ctx.fillStyle = checked ? "#b9374b" : selected ? "#1f7a6d" : "rgba(255,255,255,.9)";
  ctx.fill(); ctx.strokeStyle = "#202124"; ctx.stroke();
  ctx.fillStyle = piece.color === "WHITE" ? "#202124" : "#111";
  ctx.font = `${canvas.width * 0.052}px serif`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.fillText(glyphs[`${piece.color}_${piece.type}`] || "?", p.x, p.y + 1);
}

async function historyView() {
  const [stats, matches] = await Promise.all([api("/api/me/stats"), api("/api/me/matches?page=0&size=20")]);
  app.innerHTML = `<section class="grid"><h1>전적</h1>${statsView(stats)}<div class="card">${matchesView(matches.items)}</div></section>`;
}

async function adminView() {
  if (me.role !== "ADMIN") {
    app.innerHTML = `<section class="card">Admin role is required.</section>`;
    return;
  }
  const data = await api("/api/admin/users?page=0&size=100");
  app.innerHTML = `
    <section class="grid">
      <h1>Admin</h1>
      <div class="card">
        <h2>Users</h2>
        <div class="adminTable">
          ${data.users.map(adminUserRow).join("")}
        </div>
      </div>
      <div id="adminDetail" class="grid hidden"></div>
    </section>`;
  document.querySelectorAll("[data-admin-action]").forEach(button => {
    button.onclick = () => adminAction(button.dataset.adminAction, Number(button.dataset.userId));
  });
}

function adminUserRow(user) {
  const banned = Boolean(user.bannedAt);
  const nameClass = user.role === "ADMIN" ? "adminName" : "";
  return `
    <div class="adminUserRow">
      <div class="profile">
        ${user.profileImageUrl ? `<img class="avatar" src="${user.profileImageUrl}" alt="">` : `<div class="avatar"></div>`}
        <div>
          <strong class="${nameClass}">#${user.id} ${escapeHtml(user.displayName || "user")}</strong>
          <div class="muted">${escapeHtml(user.email || "")} ${user.role}${banned ? " BANNED" : ""}</div>
        </div>
      </div>
      <div class="actions">
        <button data-admin-action="stats" data-user-id="${user.id}" type="button">Stats</button>
        <button data-admin-action="${banned ? "unban" : "ban"}" data-user-id="${user.id}" class="${banned ? "" : "danger"}" type="button">${banned ? "Unban" : "Ban"}</button>
      </div>
    </div>`;
}

async function adminAction(action, userId) {
  if (action === "stats") {
    const [stats, matches] = await Promise.all([
      api(`/api/admin/users/${userId}/stats`),
      api(`/api/admin/users/${userId}/matches?page=0&size=20`)
    ]);
    const detail = document.getElementById("adminDetail");
    detail.classList.remove("hidden");
    detail.innerHTML = `<h2>User #${userId}</h2>${statsView(stats)}<div class="card"><h3>Matches</h3>${matchesView(matches.items)}</div>`;
    return;
  }
  if (action === "ban" || action === "unban") {
    await api(`/api/admin/users/${userId}/${action}`, {method: "POST", body: "{}"});
    await adminView();
  }
}

function roundCoord(value) {
  return Math.round(value * 100000) / 100000;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
}

(async function init() {
  await loadMe();
  if (!me) { loginView(); return; }
  const path = location.pathname;
  if (path.startsWith("/game/join/")) return joinRoomView(decodeURIComponent(path.split("/").pop()));
  if (path.startsWith("/game/")) return gameView(decodeURIComponent(path.split("/").pop()));
  if (path === "/history") return historyView();
  if (path === "/admin") return adminView();
  return dashboard();
})();
