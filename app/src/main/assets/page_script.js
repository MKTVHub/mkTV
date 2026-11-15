(function () {
  if (window.__tvbClean) return;
  window.__tvbClean = true;
  // Hands off human checks (Cloudflare Turnstile, hCaptcha, reCAPTCHA): they read a changed navigator
  // or a removed element as a tampered browser and spin on "Verifying..." forever.
  if (/(^|\.)(challenges\.cloudflare\.com|hcaptcha\.com|recaptcha\.net)$/.test(location.hostname) ||
      /^\/recaptcha\//.test(location.pathname)) return;
  // Global Privacy Control: tells sites not to sell/share data (honoured in some regions). Top page only.
  if (window.top === window) try { Object.defineProperty(navigator, 'globalPrivacyControl', { get: function () { return true; } }); } catch (e) {}

  // Generic cosmetic filters for common ad containers.
  var css = [
    'ins.adsbygoogle', '[id^="google_ads"]', '[id^="div-gpt-ad"]', '.adsbygoogle',
    'iframe[src*="doubleclick.net"]', 'iframe[src*="googlesyndication"]',
    '[class*="ad-banner"]', '[class*="ad_banner"]', '[id*="ad-banner"]',
    '.taboola', '[id^="taboola-"]', '.OUTBRAIN', '[data-ad-slot]'
  ].join(',') + '{display:none !important;}';

  function addStyle() {
    var root = document.head || document.documentElement;
    if (!root) return false;
    var s = document.createElement('style');
    s.textContent = css;
    root.appendChild(s);
    return true;
  }

  // Invisible full-screen layers that turn any click into an ad pop-up.
  // Conservative: fixed/absolute, covers >50% of the viewport, nearly transparent,
  // and contains no video/iframe/canvas and no visible text.
  function isClickTrap(el) {
    var cs = getComputedStyle(el);
    if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
    var z = parseInt(cs.zIndex, 10);
    if (!(z >= 100)) return false;
    var r = el.getBoundingClientRect();
    var vw = window.innerWidth, vh = window.innerHeight;
    if (r.width * r.height < 0.5 * vw * vh) return false;
    var transparent = parseFloat(cs.opacity) < 0.1 ||
      ((cs.backgroundColor === 'transparent' || /rgba\(.*,\s*0(\.0\d*)?\)$/.test(cs.backgroundColor)) && cs.backgroundImage === 'none');
    if (!transparent) return false;
    if (el.querySelector('video,iframe,canvas,img,svg')) return false;
    if ((el.innerText || '').trim().length > 0) return false;
    return true;
  }

  function sweep() {
    var body = document.body;
    if (!body) return;
    var nodes = body.querySelectorAll('div,a,span,section');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      try { if (isClickTrap(el)) el.remove(); } catch (e) {}
    }
  }

  if (!addStyle()) document.addEventListener('DOMContentLoaded', addStyle);
  var n = 0;
  var timer = setInterval(function () { sweep(); if (++n > 20) clearInterval(timer); }, 500);
  document.addEventListener('DOMContentLoaded', sweep);
})();

// ---- Jump mode: D-pad moves between clickable things (top frame only) ----
(function () {
  if (window.top !== window || window.__sp) return;
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,video,iframe,' +
    '[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=switch],' +
    '[onclick],[tabindex]:not([tabindex="-1"]),[contenteditable=""],[contenteditable=true]';
  var cur = null;

  // ---- candidate cache: rebuilt only when the DOM changes or we scroll into new territory ----
  var domVersion = 0, cache = null, cacheKey = '';
  try {
    new MutationObserver(function () { domVersion++; })
      .observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}

  var pinnedMap = new WeakMap();
  // Inside a position:fixed/sticky layer (headers, bottom nav bars, floating buttons)?
  function pinned(el) {
    if (pinnedMap.has(el)) return pinnedMap.get(el);
    var res = false;
    for (var p = el, d = 0; p && p !== document.body && d < 20; p = p.parentElement, d++) {
      var pos = getComputedStyle(p).position;
      if (pos === 'fixed' || pos === 'sticky') { res = true; break; }
    }
    pinnedMap.set(el, res);
    return res;
  }

  // Inline links (<a> around a block image) report only a one-line box; measure what's inside.
  function rectOf(el) {
    var r = el.getBoundingClientRect();
    if (!el.children.length || getComputedStyle(el).display !== 'inline') return r;
    var l = 1e9, t = 1e9, rt = -1e9, b = -1e9, any = false;
    for (var i = 0; i < el.children.length && i < 12; i++) {
      var c = el.children[i].getBoundingClientRect();
      if (c.width < 2 || c.height < 2) continue;
      any = true; l = Math.min(l, c.left); t = Math.min(t, c.top); rt = Math.max(rt, c.right); b = Math.max(b, c.bottom);
    }
    if (!any) return r;
    l = Math.min(l, r.left); t = Math.min(t, r.top); rt = Math.max(rt, r.right); b = Math.max(b, r.bottom);
    return { left: l, top: t, right: rt, bottom: b, width: rt - l, height: b - t };
  }

  function shown(el, r) {
    if (r.width < 6 || r.height < 6) return false;
    var cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && parseFloat(cs.opacity) > 0.05;
  }

  function scrollBucket() {
    var se = document.scrollingElement || document.documentElement;
    return Math.round(se.scrollTop / innerHeight) + ':' + Math.round(se.scrollLeft / innerWidth);
  }

  function candidates() {
    var key = domVersion + '|' + scrollBucket() + '|' + innerWidth;
    if (cache && key === cacheKey) return cache;
    var vh = innerHeight, vw = innerWidth, set = new Set(), list = [], real = new Set();
    function add(el, r) {
      if (set.has(el)) return;
      r = rectOf(el);
      if (r.bottom < -2 * vh || r.top > 3 * vh || r.right < -vw || r.left > vw * 3) return;
      if (!shown(el, r)) return;
      var tag = el.tagName;
      // Giant wrappers with cursor:pointer are not useful targets.
      if (tag !== 'VIDEO' && tag !== 'IFRAME' && r.width * r.height > 0.6 * vw * vh) return;
      set.add(el); list.push(el);
    }
    var q = document.querySelectorAll(SEL);
    for (var i = 0; i < q.length; i++) { add(q[i]); if (set.has(q[i])) real.add(q[i]); }
    // Framework buttons often have no attributes, only cursor:pointer. Bounded scan near the viewport.
    if (document.body) {
      var all = document.body.getElementsByTagName('*'), checked = 0;
      for (var j = 0; j < all.length && checked < 1200; j++) {
        var el = all[j];
        if (set.has(el)) continue;
        var r = el.getBoundingClientRect();
        if (r.bottom < -vh || r.top > 2 * vh || r.width < 6 || r.height < 6) continue;
        checked++;
        if (getComputedStyle(el).cursor === 'pointer') add(el, r);
      }
    }
    // Keep the outermost interactive element (a link's inner spans inherit cursor:pointer), except when
    // the outer one is only a cursor:pointer wrapper around real controls (YouTube Music's whole player
    // bar is clickable): then its buttons are the targets and the wrapper is dropped.
    var wrappers = new Set();
    cache = list.filter(function (el) {
      for (var p = el.parentElement, d = 0; p && d < 8; p = p.parentElement, d++) {
        if (!set.has(p)) continue;
        if (real.has(el) && !real.has(p)) { wrappers.add(p); continue; }
        return false;
      }
      return true;
    }).filter(function (el) { return !wrappers.has(el); });
    cacheKey = key;
    return cache;
  }

  function report() {
    if (!cur || !document.contains(cur)) return null;
    var r = rectOf(cur), zf = 1;
    // Page zoom: older Chromium reports boxes in unzoomed px; zoomFactor() converts (1 on current versions).
    try { if (window.__sp && __sp.zoomFactor) zf = __sp.zoomFactor() || 1; } catch (e) {}
    return JSON.stringify({ x: r.left, y: r.top, w: r.width, h: r.height, iw: innerWidth / zf });
  }

  function inView(r) { return r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth; }

  // First thing to land on: top-left-most visible content (not the fixed header/footer).
  function pickStart(cands) {
    var best = null, bs = 1e12, pbest = null, pbs = 1e12;
    cands.forEach(function (el) {
      var r = rectOf(el);
      if (!inView(r)) return;
      var s = Math.max(r.top, 0) * 3 + Math.max(r.left, 0);
      if (pinned(el)) { if (s < pbs) { pbs = s; pbest = el; } }
      else if (s < bs) { bs = s; best = el; }
    });
    return best || pbest;
  }

  function score(dir, cr, r) {
    var cx = cr.left + cr.width / 2, cy = cr.top + cr.height / 2;
    var ex = r.left + r.width / 2, ey = r.top + r.height / 2, primary, gap;
    if (dir === 'down') { if (ey <= cy + 2 || r.bottom <= cr.bottom) return -1; primary = Math.max(0, r.top - cr.bottom); gap = Math.max(0, r.left - cr.right, cr.left - r.right); }
    else if (dir === 'up') { if (ey >= cy - 2 || r.top >= cr.top) return -1; primary = Math.max(0, cr.top - r.bottom); gap = Math.max(0, r.left - cr.right, cr.left - r.right); }
    else if (dir === 'right') { if (ex <= cx + 2 || r.right <= cr.right) return -1; primary = Math.max(0, r.left - cr.right); gap = Math.max(0, r.top - cr.bottom, cr.top - r.bottom); }
    else { if (ex >= cx - 2 || r.left >= cr.left) return -1; primary = Math.max(0, cr.left - r.right); gap = Math.max(0, r.top - cr.bottom, cr.top - r.bottom); }
    // Prefer the same row/column; sideways drift costs a lot.
    return primary + gap * 3 + (gap > 0 ? 80 : 0) + (dir === 'up' || dir === 'down' ? Math.abs(ex - cx) : Math.abs(ey - cy)) * 0.15;
  }

  function bestIn(dir, cr, cands, wantPinned) {
    var best = null, bs = 1e12;
    for (var i = 0; i < cands.length; i++) {
      var el = cands[i];
      if (el === cur || el.contains(cur) || (cur && cur.contains(el))) continue;
      if (pinned(el) !== wantPinned) continue;
      var r = rectOf(el);
      // Content under a fixed bar is hidden; don't target what the user can't see.
      if (!wantPinned && (dir === 'up' || dir === 'down') && !inView(r) && Math.abs(r.top - cr.top) > 2.5 * innerHeight) continue;
      var s = score(dir, cr, r);
      if (s >= 0 && s < bs) { bs = s; best = el; }
    }
    return best;
  }

  function canScroll(dir) {
    var se = document.scrollingElement || document.documentElement;
    if (dir === 'down') return se.scrollTop + innerHeight < se.scrollHeight - 2;
    if (dir === 'up') return se.scrollTop > 0;
    return false;
  }

  function focusOn(el) {
    cur = el;
    if (pinned(el)) return report();
    var r = rectOf(el), vh = innerHeight;
    // Keep the highlight in the comfortable middle band so the next row is always visible.
    if (r.top < vh * 0.12 || r.bottom > vh * 0.88 || r.left < 0 || r.right > innerWidth) {
      try { el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' }); }
      catch (e) { el.scrollIntoView(); }
    }
    return report();
  }

  // Hold ▲/▼: jump straight to the site's fixed top/bottom bar (e.g. YouTube's Home · Shorts · You).
  function toBar(where) {
    var cands = candidates(), vh = innerHeight, best = null, bs = 1e12;
    var cx = cur && document.contains(cur) ? (function (r) { return r.left + r.width / 2; })(rectOf(cur)) : innerWidth / 2;
    cands.forEach(function (el) {
      if (!pinned(el)) return;
      var r = el.getBoundingClientRect();
      if (!inView(r)) return;
      var cy = r.top + r.height / 2;
      if (where === 'bottom' ? cy < vh * 0.6 : cy > vh * 0.4) return;
      var s = Math.abs(r.left + r.width / 2 - cx) + (where === 'bottom' ? (vh - cy) : cy) * 2;
      if (s < bs) { bs = s; best = el; }
    });
    if (!best) return JSON.stringify({ nobar: where });
    cur = best;
    return report();
  }

  function hasBars() {
    var cands = candidates(), top = false, bottom = false, vh = innerHeight;
    for (var i = 0; i < cands.length && !(top && bottom); i++) {
      if (!pinned(cands[i])) continue;
      var r = cands[i].getBoundingClientRect();
      if (!inView(r)) continue;
      var cy = r.top + r.height / 2;
      if (cy > vh * 0.6) bottom = true; else if (cy < vh * 0.4) top = true;
    }
    return JSON.stringify({ top: top, bottom: bottom });
  }

  function move(dir) {
    if (dir === 'barDown') return toBar('bottom');
    if (dir === 'barUp') return toBar('top');
    var cands = candidates();
    var cr = cur && document.contains(cur) ? rectOf(cur) : null;
    if (!cr || !inView(cr)) {
      var start = pickStart(cands);
      if (start) return focusOn(start);
      if (dir === 'up' && !canScroll('up')) return JSON.stringify({ edge: 'up' });
      if (canScroll(dir)) { window.scrollBy(0, (dir === 'down' ? 1 : -1) * innerHeight * 0.6); return null; }
      return JSON.stringify({ edge: dir });
    }
    var curPinned = pinned(cur);
    var vertical = dir === 'up' || dir === 'down';
    // Up/down walks the page content; fixed bars (YouTube's bottom nav, sticky headers)
    // are only used once the content has run out in that direction.
    var target = vertical ? bestIn(dir, cr, cands, false)
      : (bestIn(dir, cr, cands, curPinned) || bestIn(dir, cr, cands, !curPinned));
    if (!target && vertical && canScroll(dir)) {
      window.scrollBy(0, (dir === 'down' ? 1 : -1) * innerHeight * 0.6);
      cache = null;
      target = bestIn(dir, rectOf(cur), candidates(), false);
      if (!target) return report();
    }
    if (!target && vertical) target = bestIn(dir, cr, cands, true);
    if (target) return focusOn(target);
    return JSON.stringify({ edge: dir });
  }

  // ---- video helpers for watch mode ----

  // Largest visible <video> in this document (players in cross-origin iframes aren't reachable).
  function mainVideo() {
    // The fill IIFE below also looks inside same-origin iframes and pins the filled video.
    try { if (window.__sp && __sp.video) return __sp.video(); } catch (e) {}
    var vids = document.getElementsByTagName('video'), best = null, ba = 0;
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      var w = Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0));
      var h = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
      if (w * h > ba) { ba = w * h; best = vids[i]; }
    }
    return ba > 0.25 * innerWidth * innerHeight ? best : null;
  }

  // What the media keys control: the big video, else any audio/video that is playing (or loaded).
  // Music sites (YouTube Music, Spotify) play through a hidden or tiny player behind the artwork.
  function anyMedia() {
    var v = mainVideo(); if (v) return v;
    var ms = document.querySelectorAll('video,audio'), loaded = null;
    for (var i = 0; i < ms.length; i++) {
      var m = ms[i];
      // preload="none" players (Wikipedia) have no currentSrc until played, only <source> children.
      var unloaded = !m.currentSrc && !m.src;
      if (unloaded && !m.querySelector('source[src]')) continue;
      if (!m.paused && !m.ended) return m;
      if (!loaded && (m.readyState > 0 || unloaded)) loaded = m;
    }
    return loaded;
  }

  // The page's own Next / Previous button (player bars label them for screen readers).
  function pressSkip(step) {
    var re = step > 0 ? /^(next|skip|next (song|track|video|episode))\b/i : /^(previous|prev)\b/i;
    var bs = document.querySelectorAll('button[aria-label],[role=button][aria-label],[aria-label][tabindex],button[title]');
    for (var i = 0; i < bs.length; i++) {
      var b = bs[i], label = (b.getAttribute('aria-label') || b.getAttribute('title') || '').trim();
      if (!re.test(label)) continue;
      var r = b.getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || b.disabled || b.getAttribute('aria-disabled') === 'true') continue;
      b.click();
      return 'ok';
    }
    return null;
  }

  // The page's own Play/Pause button. Keeps site players (YouTube Music, Spotify, SoundCloud) in step:
  // pausing their media element behind their back can make them restart the track.
  // Exact labels only, so thumbnail buttons like "Play <song>" never match.
  function pressPlay(m) {
    var want = m && !m.paused ? /^pause\b/i : /^play\b/i;
    var re = /^(play|pause)(\s*\(k\)|\s*\(space\)|\s+keyboard shortcut \w+)?$/i;
    var bs = document.querySelectorAll('button[aria-label],[role=button][aria-label],button[title],[role=button][title]');
    for (var i = 0; i < bs.length; i++) {
      var b = bs[i], label = (b.getAttribute('aria-label') || b.getAttribute('title') || '').trim();
      if (!re.test(label) || !want.test(label)) continue;
      var r = b.getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || b.disabled || b.getAttribute('aria-disabled') === 'true') continue;
      b.click();
      return /^pause/i.test(label) ? 'paused' : 'playing';
    }
    return null;
  }

  function scroller(el) {
    for (var p = el && el.parentElement; p && p !== document.body; p = p.parentElement) {
      var oy = getComputedStyle(p).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && p.scrollHeight > p.clientHeight + 4) return p;
    }
    return document.scrollingElement || document.documentElement;
  }

  // Next/previous item in a vertical feed (Shorts, TikTok): scroll the feed one screen,
  // which snap-scrolling feeds turn into exactly one item. Falls back to arrow keys.
  function feed(step) {
    var v = mainVideo();
    if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur();
    var sc = scroller(v), before = sc.scrollTop;
    sc.scrollBy({ top: step * sc.clientHeight, behavior: 'smooth' });
    setTimeout(function () {
      if (Math.abs(sc.scrollTop - before) < 4) {
        var key = step > 0 ? 'ArrowDown' : 'ArrowUp', code = step > 0 ? 40 : 38;
        [document, document.body].forEach(function (t) {
          t.dispatchEvent(new KeyboardEvent('keydown', { key: key, code: key, keyCode: code, which: code, bubbles: true }));
        });
      }
    }, 250);
    return 'ok';
  }

  // ---- playback controls: speed, pause for the sleep timer, "end of video" ----

  // Every <video>/<audio> here and in same-origin iframes (cross-origin players aren't reachable).
  function eachMedia(fn) {
    var docs = [document], fr = document.getElementsByTagName('iframe');
    for (var i = 0; i < fr.length; i++) { try { if (fr[i].contentDocument) docs.push(fr[i].contentDocument); } catch (e) {} }
    for (var d = 0; d < docs.length; d++) {
      var ms = docs[d].querySelectorAll('video, audio');
      for (var j = 0; j < ms.length; j++) { try { fn(ms[j]); } catch (e) {} }
    }
  }

  var wantRate = 1, hooked = false, endArmed = false, endFired = false, holdUntil = 0;
  function setRate(m) { m.__spRateSrc = m.currentSrc; if (m.playbackRate !== wantRate) m.playbackRate = wantRate; }
  function stopAll(holdMs) {
    holdUntil = Date.now() + holdMs;
    eachMedia(function (m) { if (!m.paused) m.pause(); });
  }
  // Media events don't bubble: listen in the capture phase. Installed on first use only.
  function hook() {
    if (hooked) return;
    hooked = true;
    document.addEventListener('playing', function (e) {
      var m = e.target;
      if (!m || m.playbackRate === undefined) return;
      // The sleep timer just paused everything: keep a site's autoplay from starting the next item.
      if (Date.now() < holdUntil) { m.pause(); return; }
      // Once per source, so a speed picked in the site's own menu is kept for that video.
      if (m.__spRateSrc !== m.currentSrc) setRate(m);
    }, true);
    // "End of video": pause just before the end, so the site's 'ended' autoplay never runs.
    document.addEventListener('timeupdate', function (e) {
      var m = e.target;
      if (!endArmed || !m || m.tagName !== 'VIDEO' || !isFinite(m.duration) || m.duration <= 0) return;
      if (m.duration - m.currentTime > 0.4 + 0.3 * (m.playbackRate || 1) || m !== mainVideo()) return;
      endArmed = false; endFired = true; stopAll(10000);
    }, true);
    // Fallback when timeupdate came too late (very short tail, tab in background).
    document.addEventListener('ended', function (e) {
      if (!endArmed || !e.target || e.target.tagName !== 'VIDEO') return;
      endArmed = false; endFired = true; stopAll(10000);
    }, true);
  }

  window.__sp = {
    move: move,
    rect: report,
    bars: hasBars,
    clear: function () { cur = null; },
    media: function () {
      var v = mainVideo();
      if (!v) return JSON.stringify({ has: false });
      return JSON.stringify({ has: true, playing: !v.paused && !v.ended, vertical: v.videoHeight > v.videoWidth,
        fill: !!(window.__sp.filling && __sp.filling()) });
    },
    toggle: function () {
      var v = anyMedia(), pressed = pressPlay(v);
      holdUntil = 0; // the user pressed play: the sleep timer's hold no longer applies
      if (pressed) return pressed;
      if (!v) return null;
      if (v.paused) v.play(); else v.pause();
      return v.paused ? 'paused' : 'playing';
    },
    seek: function (s) {
      var v = anyMedia(); if (!v) return null;
      v.currentTime = Math.max(0, v.currentTime + s);
      return String(Math.round(v.currentTime));
    },
    feed: feed,
    // Media Next/Previous: a feed (Shorts) scrolls; otherwise press the page's Next/Previous button.
    skip: function (step) { return mainVideo() ? feed(step) : (pressSkip(step) || feed(step)); },
    // Phone remote "Now playing": position, duration (0 for live streams) and caption tracks.
    status: function () {
      var v = anyMedia();
      if (!v) return JSON.stringify({ has: false });
      var tr = v.textTracks || [], cc = 0, on = false;
      for (var i = 0; i < tr.length; i++) {
        if (tr[i].kind !== 'subtitles' && tr[i].kind !== 'captions') continue;
        cc++; if (tr[i].mode === 'showing') on = true;
      }
      return JSON.stringify({ has: true, playing: !v.paused && !v.ended, t: v.currentTime || 0,
        d: isFinite(v.duration) ? v.duration : 0, cc: cc > 0, ccOn: on, speed: v.playbackRate || 1,
        fs: !!document.fullscreenElement || !!(window.__sp.filling && __sp.filling()) });
    },
    seekTo: function (t) {
      var v = anyMedia(); if (!v || !isFinite(v.duration)) return null;
      v.currentTime = Math.max(0, Math.min(t, v.duration));
      return String(Math.round(v.currentTime));
    },
    // Generic <track> captions only: toggles the first subtitles/captions track.
    captions: function () {
      var v = mainVideo(); if (!v || !v.textTracks) return null;
      var tr = v.textTracks, first = null, on = false;
      for (var i = 0; i < tr.length; i++) {
        if (tr[i].kind !== 'subtitles' && tr[i].kind !== 'captions') continue;
        if (!first) first = tr[i];
        if (tr[i].mode === 'showing') { on = true; tr[i].mode = 'hidden'; }
      }
      if (!on && first) first.mode = 'showing';
      return first ? (on ? 'off' : 'on') : null;
    },
    // Wanted speed for this document: applied now and to each later video when it starts playing.
    rate: function (r) {
      r = +r; if (!(r > 0 && r <= 4)) return null;
      hook(); wantRate = r;
      eachMedia(setRate);
      return String(r);
    },
    // Sleep timer: pause every video and audio, and hold off autoplay for a moment.
    pause: function () { hook(); stopAll(3000); return 'ok'; },
    // Sleep timer "End of video". on: arms it (again after a navigation) and reports
    // {fired} once it paused, else {left: wall-clock seconds to the end, -1 if unknown or live}.
    sleepEnd: function (on) {
      hook();
      if (!on) { endArmed = false; endFired = false; return JSON.stringify({ fired: false }); }
      if (endFired) { endFired = false; return JSON.stringify({ fired: true }); }
      endArmed = true;
      var v = mainVideo(), left = -1;
      if (v && isFinite(v.duration) && v.duration > 0 && !v.paused) left = (v.duration - v.currentTime) / (v.playbackRate || 1);
      return JSON.stringify({ fired: false, left: left });
    },
    // Real full screen via the Fullscreen API. Needs a recent user activation, which the app
    // provides by sending a harmless key to the page just before calling this.
    // 'on' = asked (the app falls back to __sp.fill() if no full-screen view appears), 'fail' = can't ask.
    fullscreen: function () {
      try {
        if (document.fullscreenElement) { document.exitFullscreen(); return 'off'; }
        var v = mainVideo(); if (!v) return null;
        // Video in a same-origin iframe: full-screening the frame would not enlarge the video in it.
        if (v.ownerDocument !== document || document.fullscreenEnabled === false) return 'fail';
        var box = v.closest('.html5-video-player, #movie_player, #player-container-id, .player-container, [class*="video-player" i]') || v;
        var req = box.requestFullscreen || box.webkitRequestFullscreen;
        if (!req) return 'fail';
        var p = req.call(box);
        if (p && p.catch) p.catch(function () {
          try {
            var r2 = v.requestFullscreen || v.webkitRequestFullscreen;
            var q = r2 && r2.call(v);
            if (q && q.catch) q.catch(function () {});
          } catch (e) {}
        });
        return 'on';
      } catch (e) { return 'fail'; }
    }
  };
})();

// ---- Page zoom and full-screen fallback ("fill"): top frame only, adds to window.__sp ----
// Zoom: CSS zoom on <html> reflows the whole page (text, images, buttons) at the chosen size; why not
// textZoom / page scale is explained in PageZoom.kt. Fill: when a site's player can't use the Fullscreen
// API in WebView, the main <video> (or the same-origin iframe holding it) is pinned over the whole
// viewport with CSS and everything else is hidden; unfill() puts the page back exactly as it was.
(function () {
  if (window.top !== window || !window.__sp || window.__sp.zoom) return;
  var sp = window.__sp;

  // ---- zoom ----
  var zoomPct = 100, zoomCal = null;
  function applyZoom() {
    var root = document.documentElement;
    if (!root) return;
    var s = document.getElementById('__sp_zoom');
    if (!s) {
      s = document.createElement('style');
      s.id = '__sp_zoom';
      // A child of <html>, not <head>: survives frameworks that rebuild the head.
      root.appendChild(s);
    }
    s.textContent = zoomPct === 100 ? '' : 'html{zoom:' + (zoomPct / 100) + ' !important}';
  }
  sp.zoom = function (pct) {
    try {
      pct = Math.round(+pct);
      if (!(pct >= 25 && pct <= 500)) pct = 100;
      if (pct === zoomPct && document.getElementById('__sp_zoom')) return String(pct);
      zoomPct = pct; zoomCal = null;
      if (document.documentElement) applyZoom();
      else document.addEventListener('DOMContentLoaded', function () { try { applyZoom(); } catch (e) {} });
      return String(pct);
    } catch (e) { return null; }
  };
  // Boxes from getBoundingClientRect → viewport px. Chromium 128+ already reports zoomed boxes (factor 1);
  // older versions report unzoomed ones (factor = zoom). Measured once per level with a probe.
  sp.zoomFactor = function () {
    var root = document.documentElement;
    if (zoomPct === 100 || !root || root.hasAttribute('data-sp-filling')) return 1;
    if (zoomCal) return zoomCal;
    zoomCal = 1;
    try {
      var p = document.createElement('div');
      p.style.cssText = 'position:fixed;left:0;top:0;width:100px;height:1px;visibility:hidden;pointer-events:none';
      document.documentElement.appendChild(p);
      var w = p.getBoundingClientRect().width;
      p.remove();
      var f = w > 0 ? zoomPct / w : 1;
      if (f > 0.3 && f < 5 && Math.abs(f - 1) > 0.02) zoomCal = f;
    } catch (e) {}
    return zoomCal;
  };

  // ---- finding the main video, including same-origin iframes ----
  function playing(v) { return !v.paused && !v.ended && v.readyState > 2; }

  // Calls fn(video, rectInTopViewport, frameChain) for every <video>; frameChain lists the iframes it sits in.
  function eachVideo(fn) {
    function walk(doc, ox, oy, chain, depth) {
      var vids = doc.getElementsByTagName('video');
      for (var i = 0; i < vids.length; i++) {
        try {
          var r = vids[i].getBoundingClientRect();
          fn(vids[i], { left: ox + r.left, top: oy + r.top, right: ox + r.right, bottom: oy + r.bottom, width: r.width, height: r.height }, chain);
        } catch (e) {}
      }
      if (depth >= 2) return;
      var frames = doc.getElementsByTagName('iframe');
      for (var j = 0; j < frames.length; j++) {
        try {
          var d = frames[j].contentDocument; // null or throws for cross-origin frames
          if (!d || !d.documentElement) continue;
          var fr = frames[j].getBoundingClientRect();
          walk(d, ox + fr.left + frames[j].clientLeft, oy + fr.top + frames[j].clientTop, chain.concat([frames[j]]), depth + 1);
        } catch (e) {}
      }
    }
    walk(document, 0, 0, [], 0);
  }

  // Largest visible video (playing ones count double when preferPlaying). relaxed: ignore the viewport,
  // used while filling, when everything else is hidden and a replacement video may be laid out off screen.
  function bestVideo(preferPlaying, relaxed) {
    var best = null, bs = 0, f = sp.zoomFactor(), vw = innerWidth, vh = innerHeight;
    eachVideo(function (v, r, chain) {
      var area;
      if (relaxed) area = r.width * r.height * f * f;
      else {
        var w = Math.max(0, Math.min(r.right * f, vw) - Math.max(r.left * f, 0));
        var h = Math.max(0, Math.min(r.bottom * f, vh) - Math.max(r.top * f, 0));
        area = w * h;
      }
      if (area <= 0) return;
      var s = area * (preferPlaying && playing(v) ? 2 : 1);
      if (s > bs) { bs = s; best = { v: v, chain: chain, area: area }; }
    });
    if (!best) return null;
    if (!relaxed && best.area <= 0.25 * vw * vh) return null;
    return best;
  }

  // ---- fill ----
  var CSS =
    'html[data-sp-filling]{zoom:1 !important;overflow:hidden !important;background:#000 !important}' +
    'html[data-sp-filling] body{overflow:hidden !important}' +
    'html[data-sp-filling] body *:not([data-sp-fill]):not([data-sp-fill-path]){visibility:hidden !important}' +
    // Ancestors: drop anything that would trap a position:fixed child or fade/clip it.
    '[data-sp-fill-path]{visibility:visible !important;transform:none !important;filter:none !important;' +
    'backdrop-filter:none !important;perspective:none !important;contain:none !important;will-change:auto !important;' +
    'clip-path:none !important;-webkit-mask:none !important;mask:none !important;opacity:1 !important;content-visibility:visible !important}' +
    // Percent sizes, not vw/vh: they stay right under any leftover zoom.
    '[data-sp-fill]{visibility:visible !important;display:block !important;position:fixed !important;left:0 !important;top:0 !important;' +
    'right:0 !important;bottom:0 !important;width:100% !important;height:100% !important;min-width:0 !important;min-height:0 !important;' +
    'max-width:none !important;max-height:none !important;margin:0 !important;padding:0 !important;border:0 !important;' +
    'transform:none !important;opacity:1 !important;clip-path:none !important;z-index:2147483647 !important;' +
    'background:#000 !important;object-fit:contain !important}';

  var state = null; // { v, marked: [elements], docs: [documents], sx, sy, misses, timer }

  function markDoc(doc, target, st) {
    if (!doc.getElementById('__sp_fill_css')) {
      var s = doc.createElement('style');
      s.id = '__sp_fill_css';
      s.textContent = CSS;
      doc.documentElement.appendChild(s);
    }
    doc.documentElement.setAttribute('data-sp-filling', '');
    st.docs.push(doc);
    target.setAttribute('data-sp-fill', '');
    st.marked.push(target);
    for (var p = target.parentElement; p && p !== doc.documentElement; p = p.parentElement) {
      p.setAttribute('data-sp-fill-path', '');
      st.marked.push(p);
    }
  }

  function clearMarks(st) {
    st.marked.forEach(function (el) { try { el.removeAttribute('data-sp-fill'); el.removeAttribute('data-sp-fill-path'); } catch (e) {} });
    st.docs.forEach(function (d) {
      try {
        d.documentElement.removeAttribute('data-sp-filling');
        var s = d.getElementById('__sp_fill_css');
        if (s) s.remove();
      } catch (e) {}
    });
    st.marked = []; st.docs = [];
  }

  function pin(st, found) {
    st.v = found.v;
    // Outermost first: the top document pins the iframe, the iframe's document pins the video.
    var docs = [document], targets = found.chain.concat([found.v]);
    for (var i = 0; i < found.chain.length; i++) docs.push(found.chain[i].contentDocument);
    for (var k = 0; k < targets.length; k++) markDoc(docs[k], targets[k], st);
  }

  function stillPinned(st) {
    if (!st.v || !st.v.isConnected) return false;
    for (var i = 0; i < st.marked.length; i++) if (!st.marked[i].isConnected) return false;
    return st.v.hasAttribute('data-sp-fill');
  }

  // Single-page sites swap the <video> on navigation or between items: follow the new one.
  function watch() {
    var st = state;
    if (!st) return;
    try {
      var ok = stillPinned(st);
      // A paused video stays pinned; only an ended or emptied one (kept in the page by some SPAs) is swapped.
      if (ok && (st.v.ended || !(st.v.currentSrc || st.v.src || st.v.srcObject))) {
        var alt = bestVideo(true, true);
        if (alt && alt.v !== st.v && playing(alt.v)) ok = false;
      }
      if (ok) { st.misses = 0; return; }
      clearMarks(st);
      var found = bestVideo(true, true);
      if (found) { st.misses = 0; pin(st, found); return; }
      if (++st.misses >= 6) sp.unfill(); // ~5 s without any video: give the page back
    } catch (e) {}
  }

  sp.fill = function () {
    try {
      if (state) return 'fill';
      var found = bestVideo(true, false);
      if (!found) return null;
      var st = { v: null, marked: [], docs: [], sx: scrollX, sy: scrollY, misses: 0, timer: 0 };
      pin(st, found);
      state = st;
      st.timer = setInterval(watch, 800);
      return 'fill';
    } catch (e) {
      try { if (state) sp.unfill(); } catch (e2) {}
      return null;
    }
  };

  sp.unfill = function () {
    var st = state;
    if (!st) return 'off';
    state = null;
    try { clearInterval(st.timer); } catch (e) {}
    try { clearMarks(st); } catch (e) {}
    try { scrollTo(st.sx, st.sy); } catch (e) {}
    return 'off';
  };

  sp.filling = function () { return !!state; };

  // Used by the watch-mode helpers (mainVideo): the filled video while filling, else the largest visible one.
  sp.video = function () {
    if (state && state.v && state.v.isConnected) return state.v;
    var b = bestVideo(false, false);
    return b ? b.v : null;
  };
})();

// ---- Auto-unmute: sites start videos muted when they can't tell you interacted. ----
(function () {
  if (window.__spUnmute || window.__spNoUnmute) return;
  window.__spUnmute = true;
  var done = new WeakSet();
  function tryUnmute(v) {
    if (window.__spNoUnmute || !v.muted || done.has(v)) return;
    if (v.closest && v.closest('.ad-showing, .ad-interrupting')) return; // leave muted ads alone
    var r = v.getBoundingClientRect();
    if (r.width * r.height < 0.15 * innerWidth * innerHeight) return;   // feed hover-previews stay silent
    done.add(v);
    // Prefer the site's own unmute control so its UI stays in sync (YouTube, Twitch, ...).
    var btn = document.querySelector('.ytp-unmute, .ytm-unmute-button, button[aria-label*="nmute" i], button[title*="nmute" i], button[class*="unmute" i]');
    if (btn) btn.click();
    setTimeout(function () { if (v.muted) v.muted = false; if (v.volume === 0) v.volume = 1; }, btn ? 400 : 0);
  }
  document.addEventListener('playing', function (e) {
    var t = e.target;
    if (t && t.tagName === 'VIDEO') setTimeout(function () { tryUnmute(t); }, 300);
  }, true);
})();
