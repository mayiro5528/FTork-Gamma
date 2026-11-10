// FTolk-G: FTalk Beta 화면을 FTolk-G 엔진(휴대폰 음성 인식 · ML Kit 번역 · 휴대폰 음성 · ML Kit 글자 인식)으로 쓰기 위한 덧붙임.
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

  // 2) 유료 엔진(위스퍼 모델·GPT·Google Cloud 키) 설정은 숨겨요. FTolk-G 는 무료 기기 엔진 하나만 써요.
  function hide(el) { if (el) el.style.display = 'none'; }
  var ai = document.getElementById('aiSection');
  if (ai) {
    Array.prototype.forEach.call(ai.children, function (c) {
      if (c.tagName !== 'H3' && c.id !== 'aiSummary') hide(c);
    });
  }
  ['liveOn', 'shutterHaptic'].forEach(function (id) {
    var row = document.getElementById(id); row = row && row.closest('.setting');
    if (row) { hide(row); hide(row.nextElementSibling); }
  });
  var ler = document.getElementById('liveEngineRow'); if (ler) { hide(ler); hide(ler.nextElementSibling); }
  hide(document.getElementById('apiGuideBtn'));

  // 엔진 요약 문구
  try {
    aiSummaryText = function () {
      return '음성 인식: 휴대폰 음성 인식 (Google)\n번역: ML Kit · 기기 안 · 무료\n읽기: 휴대폰 음성\n사진: ML Kit 글자 인식 + 기기 번역';
    };
    renderAi();
  } catch (e) {}

  // 앱 정보 · 안내 문구
  try {
    var info = Array.prototype.find.call(document.querySelectorAll('#settingsSheet strong'), function (s) { return /FriendlyTalk/.test(s.textContent); });
    if (info) info.textContent = 'FTolk-G v' + N.appVersion() + ' (FTalk Beta 화면)';
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

  // 3) 듣기 모드에서 번역을 읽을 때는 통화 스피커(귀)로 보내요. (FTolk-G "듣기" 방식)
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
