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
    var keyHelp = document.createElement('div');
    keyHelp.className = 'hint';
    keyHelp.innerHTML = '<b>Google 키 만들기</b>: console.cloud.google.com → 새 프로젝트 → 결제 연결 → ' +
      'API 및 서비스 → 라이브러리에서 <b>Cloud Translation API</b> 사용 → 사용자 인증 정보 → API 키 만들기 → 여기에 붙여넣고 저장 → 연결 테스트.<br>' +
      '매달 50만 자까지 무료, 그 뒤 100만 자당 약 $20. 키는 이 폰에만 저장돼요. 키가 없거나 인터넷이 끊기면 기기 번역(ML Kit)으로 자동 전환해요.';
    var testOut = document.getElementById('aiTestOut');
    if (testOut) testOut.after(keyHelp);
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
    [['C', 'fn'], ['⌫', 'fn'], ['.', 'fn'], ['÷', 'op'],
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
