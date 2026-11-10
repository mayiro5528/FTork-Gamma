// FTalk: FTalk Beta 화면을 FTalk 엔진(휴대폰 음성 인식 · ML Kit 번역 · 휴대폰 음성 · ML Kit 글자 인식)으로 쓰기 위한 덧붙임.
// index.html(FTalk Beta 화면)은 그대로 두고, 이 파일에서만 바꿔요.
(function () {
  'use strict';
  var N = window.FriendlyNative;

  // 1) 복사: WebView 에서는 navigator.clipboard 가 막힐 수 있어서 앱 쪽 클립보드로 보내요.
  try {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: function (t) { try { N.copyText(String(t)); return Promise.resolve(); } catch (e) { return Promise.reject(e); } } }
    });
  } catch (e) {}

  // 2) 엔진 설정: 위스퍼 모델·GPT 는 숨기고, 기기(ML Kit) / Google(서버 번역, 키 필요) 두 가지만 보여요.
  function hide(el) { if (el) el.style.display = 'none'; }
  var ai = document.getElementById('aiSection');
  if (ai) {
    var googleH4 = Array.prototype.find.call(ai.querySelectorAll('h4'), function (h) { return /Google/.test(h.textContent); });
    var keep = [
      document.getElementById('engPick'), document.getElementById('engModels'), document.getElementById('aiSummary'),
      googleH4, document.getElementById('googleKey'),
      document.getElementById('googleSave') && document.getElementById('googleSave').parentElement,
      document.getElementById('googleRestrict'), document.getElementById('aiTestOut')
    ];
    Array.prototype.forEach.call(ai.children, function (c) {
      if (c.tagName !== 'H3' && keep.indexOf(c) < 0) hide(c);
    });
    hide(ai.querySelector('.eng-card[data-eng="gpt"]'));
    if (googleH4) googleH4.textContent = 'Google 번역 API 키 (Google 엔진에 필요)';
    // ---- 내 Google 키 만들기 안내 (앱을 받은 사람이 각자 자기 키를 만들어 써요) ----
    hide(document.getElementById('googleRestrict'));
    var help = document.createElement('div');
    help.className = 'ftalk-keyhelp';
    var cfg0 = {}; try { cfg0 = loadAi(); } catch (e) {}
    var pkg = cfg0.package || 'com.ftork.gamma', sha = cfg0.sha1 || '';
    help.innerHTML =
      '<div class="kh-title">내 Google 키 만들기 (약 5분 · 처음 한 번)</div>' +
      '<ol class="kh-steps">' +
      '<li>PC에서 <b>console.cloud.google.com</b> 접속 → Google 계정으로 로그인</li>' +
      '<li>위쪽 프로젝트 선택 → <b>새 프로젝트</b> 만들기</li>' +
      '<li><b>결제</b> 메뉴에서 결제 계정(카드) 연결 <small>· 매달 50만 자까지 무료, 넘으면 100만 자당 약 $20</small></li>' +
      '<li><b>API 및 서비스 → 라이브러리</b>에서 <b>Cloud Translation API</b> 검색 → <b>사용</b></li>' +
      '<li><b>사용자 인증 정보 → 사용자 인증 정보 만들기 → API 키</b> → 키(AIza…) 복사</li>' +
      '<li>(권장) 만든 키 → <b>애플리케이션 제한사항: Android 앱</b> → 항목 추가에 아래 두 값을 넣고, <b>API 제한사항</b>은 Cloud Translation API만 체크 → 저장</li>' +
      '<li>이 화면 위 칸에 키 붙여넣기 → <b>저장</b> → 5분 뒤 <b>연결 테스트</b></li>' +
      '</ol>' +
      '<div class="kh-val"><span>패키지 이름</span><code>' + pkg + '</code><button type="button" data-copy="' + pkg + '">복사</button></div>' +
      (sha ? '<div class="kh-val"><span>SHA-1 인증서 지문</span><code>' + sha + '</code><button type="button" data-copy="' + sha + '">복사</button></div>' : '') +
      '<div class="kh-note">키는 이 폰 안에만 저장되고 Google 외에는 보내지 않아요. 요금은 키 주인에게 청구돼요. 키가 없거나 인터넷이 끊기면 기기 번역(ML Kit)으로 자동 전환해요.</div>';
    help.querySelectorAll('[data-copy]').forEach(function (b) {
      b.onclick = function () { try { N.copyText(b.dataset.copy); notice('복사했어요'); } catch (e) {} };
    });
    var st = document.createElement('style');
    st.textContent =
      '.ftalk-keyhelp{margin:10px 0 4px;padding:12px;border-radius:12px;background:var(--surface,#303134);font-size:13px;line-height:1.55}' +
      '.ftalk-keyhelp .kh-title{font-weight:800;margin-bottom:6px}' +
      '.ftalk-keyhelp .kh-steps{margin:0 0 8px;padding-left:20px}' +
      '.ftalk-keyhelp .kh-steps li{margin:3px 0}' +
      '.ftalk-keyhelp small{opacity:.75}' +
      '.ftalk-keyhelp .kh-val{display:grid;grid-template-columns:1fr auto;gap:2px 8px;align-items:center;padding:8px 0;border-top:1px solid var(--separator,#515763)}' +
      '.ftalk-keyhelp .kh-val span{grid-column:1/3;font-size:12px;opacity:.75}' +
      '.ftalk-keyhelp .kh-val code{font-size:12.5px;overflow-wrap:anywhere;word-break:break-all}' +
      '.ftalk-keyhelp .kh-val button{padding:6px 12px;border-radius:9px;border:1px solid var(--stroke,#606773);background:transparent;color:inherit;font-weight:700}' +
      '.ftalk-keyhelp .kh-note{font-size:12px;opacity:.75;margin-top:6px}' +
      '#aiTestOut{overflow-wrap:anywhere}';
    document.head.appendChild(st);
    var testOut = document.getElementById('aiTestOut');
    if (testOut) testOut.after(help);
  }
  // 엔진 카드 설명을 FTalk 실제 동작에 맞게
  try {
    ENGINES.device.name = '기기';
    ENGINES.device.rows = [['음성 인식', 'Google 음성 인식 (Google 번역 앱과 같음)'], ['번역', 'ML Kit · 기기 안 (Google 번역 오프라인과 같은 수준)'], ['사진', 'ML Kit 글자 인식 + 기기 번역'], ['비용', '무료 · 인터넷 없이도 번역']];
    ENGINES.google.cfg = { stt: 'android', translate: 'google', realtime: false };
    ENGINES.google.rows = [['음성 인식', 'Google 음성 인식 (Google 번역 앱과 같음)'], ['번역', 'Google 서버 번역 (Google 번역 앱 온라인과 같음)'], ['사진', 'ML Kit 글자 인식 + Google 번역 (실시간 카메라 미리보기는 기기 번역)'], ['비용', '매달 50만 자 무료 · Google 키 필요']];
    var cardSmall = ai && ai.querySelector('.eng-card[data-eng="device"] small'); if (cardSmall) cardSmall.textContent = '무료 · 인터넷 없이 · 정확도 보통';
    var gSmall = ai && ai.querySelector('.eng-card[data-eng="google"] small'); if (gSmall) gSmall.textContent = 'Google 번역 앱과 같은 서버 번역 · 키 필요';
  } catch (e) {}
  ['liveOn', 'shutterHaptic'].forEach(function (id) {
    var row = document.getElementById(id); row = row && row.closest('.setting');
    if (row) { hide(row); hide(row.nextElementSibling); }
  });
  var ler = document.getElementById('liveEngineRow'); if (ler) { hide(ler); hide(ler.nextElementSibling); }
  hide(document.getElementById('apiGuideBtn'));

  // 듣기 최대 구간: 휴대폰 음성 인식이 말이 끝나는 곳을 스스로 정해서 이 설정은 쓰이지 않아요. → 숨김
  hide(document.getElementById('listenSegBtn'));
  var lms = document.getElementById('listenMaxSec'); lms = lms && lms.closest('.setting');
  if (lms) { hide(lms); hide(lms.nextElementSibling); }

  // 환율: 인터넷이 되면 자동으로만 받아요. 수동 입력칸과 켜고 끄는 스위치는 숨김
  ['calcRateInput', 'fxAuto'].forEach(function (id) {
    var r = document.getElementById(id); hide(r && r.closest('.setting'));
  });
  try {
    if (settings.fxAuto === false) { settings.fxAuto = true; save(); }
    // 아직 실제 환율을 못 받았을 때 "수동 환율" 대신 받는 중이라고 알려요.
    var originalShowAmount = showAmount;
    showAmount = function () {
      originalShowAmount();
      if (!settings.fxRates) {
        var r = document.getElementById('calcRate');
        if (r && /수동 환율/.test(r.textContent)) r.textContent = r.textContent.replace('수동 환율', '임시 환율 (인터넷이 되면 실제 환율로 자동 갱신)');
      }
    };
    showAmount(); renderFxHint(); refreshRates(false);
  } catch (e) {}

  // 계산기: 한화 환산가 글씨를 지금의 70% 크기로
  try {
    var krw = document.getElementById('calcKrw');
    var base = parseFloat(getComputedStyle(krw).fontSize) || 20;
    krw.style.fontSize = (base * 0.7).toFixed(1) + 'px';
  } catch (e) {}

  // 화면 설정(다크/라이트 · 컬러/심플)을 앱에 알려서 실시간 카메라 화면도 같은 색으로
  function syncTheme() { try { N.setTheme(settings.theme, settings.uiStyle); } catch (e) {} }
  syncTheme();
  ['themeSelect', 'styleSelect'].forEach(function (id) {
    var el = document.getElementById(id); if (el) el.addEventListener('change', syncTheme);
  });

  // ===== 계산기: 실제 계산 (+ − × ÷) 키패드 =====
  // 금액칸은 키패드로만 입력해요. 계산 결과가 곧 "제시 금액"이고, 한화 환산가도 결과로 계산해요.
  try {
    var css = document.createElement('style');
    css.textContent =
      '.app.calc-mode #calcPanel{gap:8px}' +
      // 금액 칸 : 키패드 = 4 : 6 (세로 높이)
      '.app.calc-mode .calc-stage{flex:4 1 0;min-height:0;padding:10px 14px;gap:6px;justify-content:center}' +
      '.calc-expr{min-height:20px;text-align:right;font-size:15px;color:var(--muted,#b6c0cd);overflow-wrap:anywhere}' +
      '.app.calc-mode .amount-input{text-align:right;caret-color:transparent}' +
      '.app.calc-mode .calc-rate{padding:8px 0 0;line-height:1.5}' +
      '.calc-keys{display:grid;grid-template-columns:repeat(4,1fr);grid-auto-rows:1fr;gap:6px;flex:6 1 0;min-height:0}' +
      '.calc-keys button{min-height:0;padding:0;border-radius:14px;font-size:22px;font-weight:700;background:var(--surface,#303134);color:var(--base-contrast,#f4f6f8);border:1px solid var(--stroke,#53565b);display:flex;align-items:center;justify-content:center}' +
      '.calc-keys button:active{filter:brightness(1.25)}' +
      '.calc-keys .op{color:var(--blue)}' +
      '.calc-keys .fn{color:var(--muted,#b6c0cd);font-size:18px}' +
      '.calc-keys .eq{background:var(--blue);color:#fff;border-color:var(--blue)}' +
      // C(모두 지우기)는 빨간 버튼, C 와 ⌫ 표시는 다른 기능 키보다 10% 크게 (18px → 20px)
      '.calc-keys .fn.clr,.calc-keys .fn.big{font-size:20px}' +
      '.calc-keys .clr,html[data-style="simple"] .calc-keys .clr{background:#d93025;color:#fff;border-color:#d93025}' +
      'html[data-style="simple"] .calc-keys button{border:0}' +
      'html[data-style="simple"] .calc-keys .eq{background:#4c6a8a}' +
      '.app.calc-mode .calc-actions{grid-template-columns:repeat(2,minmax(0,1fr))}';
    document.head.appendChild(css);

    var label = document.querySelector('#calcPanel .calc-label');
    if (label) label.textContent = '금액 입력';
    hide(document.getElementById('calcClear'));
    hide(document.getElementById('calcHint'));

    var amount = document.getElementById('amountInput');
    amount.readOnly = true; amount.setAttribute('inputmode', 'none');
    amount.onfocus = null; amount.oninput = null; amount.onblur = null;

    var exprEl = document.createElement('div');
    exprEl.className = 'calc-expr'; exprEl.id = 'calcExpr';
    var wrap = amount.closest('.amount-wrap'); wrap.parentNode.insertBefore(exprEl, wrap);

    var keys = document.createElement('div');
    keys.className = 'calc-keys'; keys.id = 'calcKeys';
    [['C', 'fn clr'], ['⌫', 'fn big'], ['.', 'fn'], ['÷', 'op'],
     ['7'], ['8'], ['9'], ['×', 'op'],
     ['4'], ['5'], ['6'], ['−', 'op'],
     ['1'], ['2'], ['3'], ['+', 'op'],
     ['000'], ['0'], ['00'], ['=', 'eq']].forEach(function (k) {
      var b = document.createElement('button'); b.type = 'button'; b.textContent = k[0];
      if (k[1]) b.className = k[1];
      b.setAttribute('aria-label', { 'C': '모두 지우기', '⌫': '한 글자 지우기', '÷': '나누기', '×': '곱하기', '−': '빼기', '+': '더하기', '=': '계산' }[k[0]] || k[0]);
      b.onclick = function () { press(k[0]); };
      keys.appendChild(b);
    });
    var stage = document.querySelector('#calcPanel .calc-stage');
    stage.parentNode.insertBefore(keys, stage.nextSibling);

    var expr = amountValue ? String(amountValue) : '';
    var OPS = '+−×÷';
    function lastNumber() { var m = expr.match(/[0-9.]*$/); return m ? m[0] : ''; }

    // 사칙연산 계산 (곱셈·나눗셈 먼저). eval 은 쓰지 않아요.
    function evaluate(s) {
      s = s.replace(/[+−×÷.]+$/, '');
      if (!s) return 0;
      var parts = s.split(/([+−×÷])/);
      var terms = [], ops = [], cur = parseFloat(parts[0]) || 0;
      for (var i = 1; i < parts.length; i += 2) {
        var op = parts[i], n = parseFloat(parts[i + 1]) || 0;
        if (op === '×') cur *= n;
        else if (op === '÷') cur = n === 0 ? NaN : cur / n;
        else { terms.push(cur); ops.push(op); cur = n; }
      }
      terms.push(cur);
      var total = terms[0];
      for (var j = 0; j < ops.length; j++) total = ops[j] === '+' ? total + terms[j + 1] : total - terms[j + 1];
      return total;
    }
    function pretty(s) {
      return s.replace(/[0-9.]+/g, function (n) {
        var p = n.split('.'); return Number(p[0] || 0).toLocaleString('ko-KR') + (p.length > 1 ? '.' + p[1] : '');
      }).replace(/([+−×÷])/g, ' $1 ');
    }
    function update(done) {
      var v = evaluate(expr);
      if (!isFinite(v)) { exprEl.textContent = pretty(expr) + ' = 계산할 수 없어요'; return; }
      amountValue = Math.max(0, Math.min(Math.round(v * 100) / 100, 9999999999));
      savePrice(); showAmount();
      exprEl.textContent = (/[+−×÷]/.test(expr) && !done) ? pretty(expr) + ' =' : '';
    }
    function press(k) {
      if (k === 'C') { expr = ''; }
      else if (k === '⌫') { expr = expr.slice(0, -1); }
      else if (k === '=') { var v = evaluate(expr); if (isFinite(v)) expr = String(Math.round(v * 100) / 100); update(true); return; }
      else if (OPS.indexOf(k) >= 0) { if (!expr) return; if (OPS.indexOf(expr.slice(-1)) >= 0) expr = expr.slice(0, -1); expr += k; }
      else if (k === '.') { var ln = lastNumber(); if (ln.indexOf('.') >= 0) return; expr += ln ? '.' : '0.'; }
      else { var ln2 = lastNumber(); if (ln2 === '0' && k[0] === '0') return; if (ln2 === '0') expr = expr.slice(0, -1); if (lastNumber().replace('.', '').length >= 12) return; if (!ln2 && k !== '0' && k.length > 1) return; expr += k; }
      update(false);
    }
    // 통화를 바꾸면 계산식을 새로 시작해요.
    document.getElementById('calcCurrency').addEventListener('change', function () { expr = amountValue ? String(amountValue) : ''; exprEl.textContent = ''; });
  } catch (e) { console.error(e); }

  // ===== 들으면서 바로 번역 (Google 번역 앱 방식) =====
  // 듣기 · 실시간 대화에서 상대가 말하는 도중에도 인식된 부분을 계속 번역해서 흐린 글씨로 보여 줘요.
  // 말이 끝나면 원래대로 확정 번역(키가 있으면 Google)이 기록에 남아요. 중간 번역은 기기 번역이라 무료예요.
  try {
    var originalShowInterim = showInterim;
    var live = { seq: 0, timer: null, lastSent: '', busy: false, pendingText: null, side: null };
    function quickTranslate(text, from, to) {
      if (!N || typeof N.translateQuick !== 'function') return translateNative(text, from, to);
      return new Promise(function (resolve, reject) {
        var id = ++window.nativeTranslateSequence;
        window.nativeTranslateTasks[id] = { resolve: resolve, reject: reject };
        N.translateQuick(id, text, from, to);
      });
    }
    function showLiveTranslation(side, src, dst) {
      if (mode === 'listen') { setListenInterim(src, dst); return; }
      if (mode === 'live') originalShowInterim(other(side), dst);
    }
    function sendPartial() {
      live.timer = null;
      var text = live.pendingText, side = live.side;
      if (!text || live.busy || text === live.lastSent) return;
      live.busy = true; live.lastSent = text;
      var seq = live.seq;
      quickTranslate(text, settings[side], settings[other(side)]).then(function (dst) {
        // 그사이 문장이 끝났으면(확정 번역으로 넘어갔으면) 보여 주지 않아요.
        if (seq === live.seq && dst && (mode === 'listen' || mode === 'live')) { live.lastDst = dst; showLiveTranslation(side, live.pendingText || text, dst); }
      }).catch(function () {}).then(function () {
        live.busy = false;
        if (seq === live.seq && live.pendingText && live.pendingText !== live.lastSent && !live.timer) live.timer = setTimeout(sendPartial, 150);
      });
    }
    showInterim = function (side, text) {
      originalShowInterim(side, text);
      if (mode !== 'listen' && mode !== 'live') return;
      var t = (text || '').trim();
      // 빈 값 = 문장이 끝나 확정 번역으로 넘어감 → 중간 번역 멈추기
      if (!t || /^(말소리 감지 중|인식 중)/.test(t)) {
        live.seq++; live.pendingText = null; live.lastSent = ''; live.lastDst = null;
        if (live.timer) { clearTimeout(live.timer); live.timer = null; }
        if (mode === 'live') originalShowInterim(other(side), ''); // 상대 칸의 중간 번역도 지우기
        return;
      }
      // 원문은 바로, 번역은 직전 번역을 유지한 채 새 번역이 오면 바꿔요(깜빡임 없음).
      if (mode === 'listen') setListenInterim(t, live.lastDst);
      live.pendingText = t; live.side = side;
      if (!live.timer) live.timer = setTimeout(sendPartial, 400);
    };
  } catch (e) { console.error(e); }

  // ===== 실시간 대화 개선 =====
  // 1) 지금 누구(어느 언어) 차례인지 보여 줘요.
  // 2) 2.5초 동안 아무 말도 안 들리면 바로 상대 언어로 바꿔 들어요 (예전엔 5초 이상 기다림).
  // 3) 말하는 사람 칸을 누르면 그 언어로 바로 들어요.
  // 4) Android 14+ 폰은 두 언어를 함께 듣고, 들린 언어로 알아서 번역 방향을 정해요.
  try {
    var detected = null, heardAt = 0, switchTimer = null;
    window.ftolkDetectedLanguage = function (id, code) { detected = code; };
    var originalShowInterim2 = showInterim;
    showInterim = function (side, text) { if (text && text.trim()) heardAt = Date.now(); return originalShowInterim2(side, text); };

    var originalOnSpeech = onSpeech;
    onSpeech = function (from, text, token) {
      if (mode === 'live' && detected) {
        var s = sideOf(detected);
        if (s && s !== from) from = s; // 들린 언어 쪽 사람이 말한 것으로
      }
      detected = null;
      return originalOnSpeech(from, text, token);
    };

    var originalStartRecognizer = startRecognizer;
    startRecognizer = function (side, token, asLive) {
      try { N.setAutoDetect(!!asLive && mode === 'live'); } catch (e) {}
      detected = null;
      if (switchTimer) { clearTimeout(switchTimer); switchTimer = null; }
      originalStartRecognizer(side, token, asLive);
      if (asLive && mode === 'live') {
        var mine = recognizer, startedAt = Date.now();
        switchTimer = setTimeout(function () {
          switchTimer = null;
          if (mode !== 'live' || !liveActive || liveStopping || ttsHold || recognizer !== mine) return;
          if (heardAt > startedAt) return; // 누군가 말하는 중이면 그대로
          liveSide = other(side); render();
          stopListening();
          startRecognizer(liveSide, token, true);
        }, 2500);
      }
    };

    function pickSide(side) {
      if (mode !== 'live' || !liveActive || liveStopping) return;
      if (switchTimer) { clearTimeout(switchTimer); switchTimer = null; }
      liveSide = side; render();
      stopListening();
      startRecognizer(side, session, true);
      notice(lang(side).flag + ' ' + lang(side).name + ' 듣는 중');
    }
    [['meText', 'me'], ['partnerText', 'partner']].forEach(function (p) {
      var card = document.getElementById(p[0]); card = card && card.closest('.card');
      if (card) card.addEventListener('click', function (e) { if (e.target.closest('button')) return; pickSide(p[1]); });
    });

    var originalRender = render;
    render = function () {
      originalRender();
      if (mode !== 'live') return;
      var info = document.getElementById('liveInfo');
      if (liveActive && !liveStopping) {
        if (liveAct === 'idle' || !liveAct) info.textContent = lang(liveSide).flag + ' ' + lang(liveSide).name + ' 듣는 중 · 다른 사람이 말하면 그 사람 칸을 누르세요';
      } else if (!liveStopping) {
        info.textContent = '시작하면 두 언어를 번갈아 들어요 · 칸을 누르면 그 언어로';
      }
      // 지금 듣는 쪽 칸을 살짝 강조
      ['me', 'partner'].forEach(function (s) {
        var card = document.getElementById(s + 'Text'); card = card && card.closest('.card');
        if (card) card.style.boxShadow = (liveActive && liveSide === s) ? '0 0 0 2px var(--blue) inset' : '';
      });
    };
    var originalSetMode = setMode;
    setMode = function (m, opt) {
      originalSetMode(m, opt);
      if (m !== 'live') ['me', 'partner'].forEach(function (s) {
        var card = document.getElementById(s + 'Text'); card = card && card.closest('.card');
        if (card) card.style.boxShadow = '';
      });
    };
    ['pushBtn', 'liveBtn', 'listenBtn', 'photoBtn', 'calcBtn'].forEach(function (id) {
      var b = document.getElementById(id); if (b) b.onclick = function () { setMode({ pushBtn: 'push', liveBtn: 'live', listenBtn: 'listen', photoBtn: 'photo', calcBtn: 'calc' }[id]); };
    });
  } catch (e) { console.error(e); }

  // 대화 리셋 안내를 짧게 ("대화를 보관하고 새로 시작했습니다 · 설정 → 대화 기록에서 볼 수 있어요" → "새 대화 시작")
  try {
    var originalReset = resetConversation;
    resetConversation = function () { originalReset(); notice('새 대화 시작'); };
    document.getElementById('resetBtn').onclick = resetConversation;
  } catch (e) {}

  // 엔진 요약 문구
  try {
    aiSummaryText = function () {
      var g = ai2().translate === 'google';
      var lines = ['음성 인식: ' + (ai2().speechService || 'Google 음성 인식'),
        '번역: ' + (g ? (ai2().hasGoogleKey ? 'Google 서버 번역 (인터넷 끊기면 ML Kit)' : 'Google 선택됨 · 키 없음 → ML Kit 로 번역 중') : 'ML Kit · 기기 안 · 무료'),
        '읽기: 휴대폰 음성'];
      return lines.join('\n');
    };
    function ai2() { try { return loadAi(); } catch (e) { return {}; } }
    renderAi();
  } catch (e) {}

  // 앱 정보 · 안내 문구
  try {
    var info = Array.prototype.find.call(document.querySelectorAll('#settingsSheet strong'), function (s) { return /FriendlyTalk/.test(s.textContent); });
    if (info) info.textContent = 'FriendlyTalk (FTalk) v' + N.appVersion();
    // 새 버전 받기: GitHub 최신 릴리스 확인 + 다운로드 페이지 열기
    var row = info && info.closest('.setting');
    if (row) {
      var btn = document.createElement('button');
      btn.type = 'button'; btn.className = 'fullbtn'; btn.id = 'ftolkUpdateBtn';
      btn.textContent = '새 버전 받기 (GitHub)';
      btn.onclick = function () { try { N.openDownload(); } catch (e) {} };
      var hintEl = document.createElement('div');
      hintEl.className = 'hint'; hintEl.id = 'ftolkUpdateHint';
      hintEl.textContent = '최신 버전 확인 중…';
      row.after(btn, hintEl);
      window.ftolkUpdateInfo = function (tag, current) {
        var latest = String(tag || '').replace(/^v/, '');
        if (!latest) { hintEl.textContent = '최신 버전을 확인하지 못했어요 (인터넷 확인). 버튼을 누르면 다운로드 페이지가 열려요.'; return; }
        var newer = latest.split('.').map(Number).join('.') !== String(current) &&
          (Number(latest.split('.')[0]) * 10 + Number(latest.split('.')[1] || 0)) > (Number(String(current).split('.')[0]) * 10 + Number(String(current).split('.')[1] || 0));
        hintEl.textContent = newer
          ? '새 버전 v' + latest + ' 이 있어요 (지금 v' + current + ') · 버튼을 눌러 APK 를 받아 설치하세요'
          : '지금 최신 버전이에요 (v' + current + ')';
        btn.textContent = newer ? '새 버전 v' + latest + ' 받기' : '다운로드 페이지 열기 (GitHub)';
      };
      try { N.checkUpdate(); } catch (e) {}
    }
    var guide = document.getElementById('historyBtn');
    var hint = guide && guide.previousElementSibling;
    if (hint && hint.classList.contains('hint')) {
      hint.textContent = '음성 인식은 휴대폰의 Google 음성 인식, 번역은 ML Kit 가 기기 안에서 처리합니다. 언어마다 처음 한 번 번역 모델(약 30MB)을 받을 때 인터넷이 필요합니다. 실시간 대화는 두 언어를 번갈아 듣고, 듣기는 상대방 말만 계속 듣고 번역합니다. 듣는 동안 화면은 꺼지지 않습니다.';
    }
  } catch (e) {}

  // 3) 듣기 모드에서 번역을 읽을 때는 통화 스피커(귀)로 보내요. (FTalk "듣기" 방식)
  try {
    var originalSpeakSide = speakSide;
    speakSide = function (side) {
      try { N.setEarpiece(mode === 'listen'); } catch (e) {}
      return originalSpeakSide(side);
    };
  } catch (e) {}

  // 4) 뒤로 가기: 열린 창 닫기 → 다른 모드면 눌러 말하기로 → 그다음 앱 종료
  window.ftolkBack = function () {
    if (document.querySelector('.sheet-backdrop.open')) { closeAll(); return 'handled'; }
    if (typeof mode !== 'undefined' && mode !== 'push') { setMode('push'); return 'handled'; }
    return 'exit';
  };

  // 5) 다른 앱의 공유 · 글자 선택 메뉴로 받은 글을 텍스트 번역 창에 넣어요.
  window.ftolkIncoming = function (text, side) {
    try {
      if (mode !== 'push') setMode('push');
      document.getElementById('textSource').value = side || 'partner';
      document.getElementById('textInput').value = text;
      openSheet('textSheet');
    } catch (e) {}
  };
})();
