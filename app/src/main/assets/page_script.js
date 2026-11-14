(function () {
  if (window.__tvbClean) return;
  window.__tvbClean = true;
  // Global Privacy Control: tells sites not to sell/share data (honoured in some regions).
  try { Object.defineProperty(navigator, 'globalPrivacyControl', { get: function () { return true; } }); } catch (e) {}

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
    var vh = innerHeight, vw = innerWidth, set = new Set(), list = [];
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
    for (var i = 0; i < q.length; i++) add(q[i]);
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
    // Keep the outermost interactive element (a link's inner spans inherit cursor:pointer).
    cache = list.filter(function (el) {
      for (var p = el.parentElement, d = 0; p && d < 8; p = p.parentElement, d++) if (set.has(p)) return false;
      return true;
    });
    cacheKey = key;
    return cache;
  }

  function report() {
    if (!cur || !document.contains(cur)) return null;
    var r = rectOf(cur);
    return JSON.stringify({ x: r.left, y: r.top, w: r.width, h: r.height, iw: innerWidth });
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
    var vids = document.getElementsByTagName('video'), best = null, ba = 0;
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      var w = Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0));
      var h = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
      if (w * h > ba) { ba = w * h; best = vids[i]; }
    }
    return ba > 0.25 * innerWidth * innerHeight ? best : null;
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

  window.__sp = {
    move: move,
    rect: report,
    bars: hasBars,
    clear: function () { cur = null; },
    media: function () {
      var v = mainVideo();
      if (!v) return JSON.stringify({ has: false });
      return JSON.stringify({ has: true, playing: !v.paused && !v.ended, vertical: v.videoHeight > v.videoWidth });
    },
    toggle: function () {
      var v = mainVideo(); if (!v) return null;
      if (v.paused) v.play(); else v.pause();
      return v.paused ? 'paused' : 'playing';
    },
    seek: function (s) {
      var v = mainVideo(); if (!v) return null;
      v.currentTime = Math.max(0, v.currentTime + s);
      return String(Math.round(v.currentTime));
    },
    feed: feed,
    // Real full screen via the Fullscreen API. Needs a recent user activation, which the app
    // provides by sending a harmless key to the page just before calling this.
    fullscreen: function () {
      if (document.fullscreenElement) { document.exitFullscreen(); return 'off'; }
      var v = mainVideo(); if (!v) return null;
      var box = v.closest('.html5-video-player, #movie_player, #player-container-id, .player-container, [class*="video-player" i]') || v;
      var p = (box.requestFullscreen || box.webkitRequestFullscreen).call(box);
      if (p && p.catch) p.catch(function () { (v.requestFullscreen || v.webkitRequestFullscreen).call(v); });
      return 'on';
    }
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
