// YouTube ad cleanup, same approach as uBlock Origin / Brave scriptlets:
// 1) strip ad entries from the player/feed data before YouTube's player reads it,
// 2) hide ad slots, 3) skip anything that still slips through.
(function () {
  var h = location.hostname;
  if (!/(^|\.)youtube\.com$|(^|\.)youtube-nocookie\.com$/.test(h) || window.__spYt) return;
  window.__spYt = true;

  var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams', 'adSlotRenderer'];
  // Feed/shelf items that are ads (web, mobile web and the TV interface).
  var AD_RENDERERS = {
    adSlotRenderer: 1, tvMastheadRenderer: 1, mastheadAdRenderer: 1, statementBannerRenderer: 1, bannerPromoRenderer: 1,
    promotedVideoRenderer: 1, compactPromotedVideoRenderer: 1, promotedSparklesWebRenderer: 1, promotedSparklesTextSearchRenderer: 1,
    displayAdRenderer: 1, inFeedAdLayoutRenderer: 1, primetimePromoRenderer: 1, brandVideoShelfRenderer: 1, brandVideoSingletonRenderer: 1,
    searchPyvRenderer: 1, adBreakServiceRenderer: 1, companionAdRenderer: 1, actionCompanionAdRenderer: 1
  };

  function isAdItem(x) {
    if (!x || typeof x !== 'object' || Array.isArray(x)) return false;
    for (var k in x) {
      if (AD_RENDERERS[k]) return true;
      // Wrappers like { richItemRenderer: { content: { adSlotRenderer: ... } } }
      var v = x[k];
      if (v && typeof v === 'object' && v.content && typeof v.content === 'object') {
        for (var k2 in v.content) if (AD_RENDERERS[k2]) return true;
      }
    }
    return false;
  }

  // Walk a YouTube response once, dropping ad items from every list and ad keys from every object.
  function prune(root) {
    if (!root || typeof root !== 'object') return root;
    var stack = [root], seen = 0;
    while (stack.length && seen < 200000) {
      var o = stack.pop(); seen++;
      if (Array.isArray(o)) {
        for (var i = o.length - 1; i >= 0; i--) {
          if (isAdItem(o[i])) o.splice(i, 1);
          else if (o[i] && typeof o[i] === 'object') stack.push(o[i]);
        }
      } else {
        for (var a = 0; a < AD_KEYS.length; a++) if (AD_KEYS[a] in o) { try { delete o[AD_KEYS[a]]; } catch (e) {} }
        for (var k in o) { var v = o[k]; if (v && typeof v === 'object') stack.push(v); }
      }
    }
    return root;
  }

  function isYtResponse(r) {
    return r && typeof r === 'object' && ('responseContext' in r || 'playerResponse' in r || 'adPlacements' in r || 'contents' in r);
  }

  // Data YouTube parses itself.
  var origParse = JSON.parse;
  JSON.parse = function () {
    var r = origParse.apply(this, arguments);
    try { if (isYtResponse(r)) prune(r); } catch (e) {}
    return r;
  };

  // Data fetched by the player (/youtubei/v1/player, /next, /reel...).
  var origJson = Response.prototype.json;
  Response.prototype.json = function () {
    var url = this.url || '';
    return origJson.apply(this, arguments).then(function (d) {
      if (/\/youtubei\/v1\//.test(url)) { try { prune(d); } catch (e) {} }
      return d;
    });
  };

  // Inline page data (ytInitialPlayerResponse is assigned by a <script> in the HTML).
  ['ytInitialPlayerResponse', 'ytInitialData'].forEach(function (name) {
    var val;
    try {
      Object.defineProperty(window, name, {
        configurable: true,
        get: function () { return val; },
        set: function (v) { val = prune(v); }
      });
    } catch (e) {}
  });

  // Hide ad slots (desktop and mobile layouts).
  var css = [
    'ytd-ad-slot-renderer', 'ytd-in-feed-ad-layout-renderer', 'ytd-banner-promo-renderer', 'ytd-statement-banner-renderer',
    'ytd-promoted-sparkles-web-renderer', 'ytd-promoted-video-renderer', 'ytd-display-ad-renderer', 'ytd-companion-slot-renderer',
    'ytd-player-legacy-desktop-watch-ads-renderer', '#player-ads', '#masthead-ad', '.ytd-merch-shelf-renderer',
    'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-video-renderer', 'ytm-companion-ad-renderer', 'ytm-ad-slot-renderer',
    'ad-slot-renderer', 'ytm-item-section-renderer:has(ad-slot-renderer)', 'ytd-rich-item-renderer:has(ytd-ad-slot-renderer)',
    'ytd-rich-section-renderer:has(ytd-statement-banner-renderer)', '.ytp-ad-overlay-container', '.ytp-ad-image-overlay',
    'ytm-paid-content-overlay-renderer', '.ytp-paid-content-overlay', 'ytlr-masthead-renderer', 'ytlr-ad-slot-renderer', 'ytlr-tv-masthead-renderer'
  ].join(',') + '{display:none !important;}';
  function addStyle() {
    var root = document.head || document.documentElement;
    if (!root) return false;
    var s = document.createElement('style'); s.textContent = css; root.appendChild(s); return true;
  }
  if (!addStyle()) document.addEventListener('DOMContentLoaded', addStyle);

  // Anything that still plays as an ad: mute, jump to the end, press Skip.
  setInterval(function () {
    var player = document.querySelector('.ad-showing, .ad-interrupting');
    if (!player) return;
    var v = player.querySelector('video');
    if (v) {
      v.muted = true;
      if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
    }
    var skip = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytm-skip-ad-button, button[class*="skip-ad"]');
    if (skip) skip.click();
  }, 300);
})();
