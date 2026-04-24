package com.example.mirror.webview.web

import android.webkit.WebView

/**
 * WebView JS Injection Utility — responsible for injecting dark mode and focus
 * management system into any web page.
 *
 * Usage: call in WebViewClient.onPageFinished
 * ```
 * WebViewInjector.inject(webView) {
 *     // Callback after JS execution completes (main thread)
 * }
 * ```
 */
object WebViewInjector {

    /**
     * Inject dark mode + focus management system into the WebView.
     * @param webView target WebView
     * @param onComplete optional callback after JS execution completes (main thread)
     */
    fun inject(webView: WebView, onComplete: (() -> Unit)? = null) {
        val js = buildInjectionJs()
        webView.evaluateJavascript(js) {
            onComplete?.invoke()
        }
    }

    /**
     * Build the complete injection JS code
     */
    private fun buildInjectionJs(): String {
        return """
            (function() {
                ${darkModeJs()}
                ${focusSystemJs()}
            })();
        """.trimIndent()
    }

    // ============================================================
    // Dark Mode Injection
    // ============================================================

    private fun darkModeJs(): String = """
        // ========== Force Dark Mode Injection ==========
        function getBrightness(colorStr) {
            if (!colorStr || colorStr === 'transparent' || colorStr === 'rgba(0, 0, 0, 0)') return -1;
            var m = colorStr.match(/\d+/g);
            if (m && m.length >= 3) {
                return (parseInt(m[0]) * 299 + parseInt(m[1]) * 587 + parseInt(m[2]) * 114) / 1000;
            }
            return -1;
        }

        // 1. Inject global dark styles
        var darkStyleId = '__mercury_dark_mode__';
        if (!document.getElementById(darkStyleId)) {
            var style = document.createElement('style');
            style.id = darkStyleId;
            style.textContent = [
                'html, body { background-color: #000000 !important; color: #CCCCCC !important; }',
                '*:not(.focused):not(.__mercury_focused), *:not(.focused):not(.__mercury_focused)::before, *:not(.focused):not(.__mercury_focused)::after { border-color: #333333 !important; }',
                'input, textarea, select, button { background-color: #1a1a1a !important; color: #CCCCCC !important; border-color: #444 !important; }',
                'a { color: #6CB4EE !important; }',
                'h1, h2, h3, h4, h5, h6 { color: #FFFFFF !important; }',
                'table, th, td { background-color: #000000 !important; color: #CCCCCC !important; border-color: #333 !important; }',
                'hr { border-color: #333 !important; background-color: #333 !important; }',
                'img { opacity: 0.9; }',
                '.focused { border-color: #00d4ff !important; box-shadow: 0 0 12px rgba(0,212,255,0.4) !important; }',
                '.__mercury_focused { outline: 3px solid #00E5FF !important; outline-offset: 3px !important; box-shadow: 0 0 16px 4px rgba(0,229,255,0.7), inset 0 0 8px rgba(0,229,255,0.15) !important; background-color: rgba(0,229,255,0.12) !important; transition: outline 0.15s ease, box-shadow 0.15s ease !important; }'
            ].join('\n');
            (document.head || document.documentElement).appendChild(style);
        }

        // 2. Force html/body inline styles
        document.documentElement.style.setProperty('background-color', '#000000', 'important');
        document.documentElement.style.setProperty('color', '#CCCCCC', 'important');
        document.body.style.setProperty('background-color', '#000000', 'important');
        document.body.style.setProperty('color', '#CCCCCC', 'important');

        // 3. Traverse all elements to handle light backgrounds and text
        var allElements = document.querySelectorAll('*');
        for (var i = 0; i < allElements.length; i++) {
            var el = allElements[i];
            try {
                var cs = window.getComputedStyle(el);
                // Determine if this is a small decorative element (progress bars, badges, etc.); preserve its color
                var elRect = el.getBoundingClientRect();
                var isSmallDecorative = (elRect.height <= 30 || elRect.width <= 60);

                var bgBrightness = getBrightness(cs.backgroundColor);
                if (bgBrightness > 50) {
                    // Small decorative element with noticeable hue (non-gray); preserve original color
                    if (isSmallDecorative) {
                        var bgM = cs.backgroundColor.match(/\d+/g);
                        if (bgM && bgM.length >= 3) {
                            var maxC = Math.max(parseInt(bgM[0]), parseInt(bgM[1]), parseInt(bgM[2]));
                            var minC = Math.min(parseInt(bgM[0]), parseInt(bgM[1]), parseInt(bgM[2]));
                            // Saturation check: max-min > 30 means it has color, not gray/white
                            if (maxC - minC <= 30) {
                                el.style.setProperty('background-color', '#000000', 'important');
                            }
                        } else {
                            el.style.setProperty('background-color', '#000000', 'important');
                        }
                    } else {
                        el.style.setProperty('background-color', '#000000', 'important');
                    }
                }
                var bgImage = cs.backgroundImage;
                if (bgImage && bgImage !== 'none' && bgImage.indexOf('gradient') !== -1) {
                    if (!isSmallDecorative) {
                        el.style.setProperty('background-image', 'none', 'important');
                        el.style.setProperty('background-color', '#000000', 'important');
                    }
                }
                var colorBrightness = getBrightness(cs.color);
                if (colorBrightness >= 0 && colorBrightness < 100) {
                    el.style.setProperty('color', '#CCCCCC', 'important');
                }
                if (!el.classList.contains('focused') && !el.classList.contains('__mercury_focused')) {
                    var shadow = cs.boxShadow;
                    if (shadow && shadow !== 'none') {
                        var shadowBrightness = getBrightness(shadow);
                        if (shadowBrightness > 128) {
                            el.style.setProperty('box-shadow', 'none', 'important');
                        }
                    }
                }
                if (!el.classList.contains('__mercury_focused')) {
                    var outlineBrightness = getBrightness(cs.outlineColor);
                    if (outlineBrightness > 128) {
                        el.style.setProperty('outline-color', '#333333', 'important');
                    }
                }
            } catch(e) {}
        }

        // 4. Handle iframes (same-origin only)
        try {
            var iframes = document.querySelectorAll('iframe');
            for (var j = 0; j < iframes.length; j++) {
                try {
                    var iDoc = iframes[j].contentDocument || iframes[j].contentWindow.document;
                    if (iDoc && iDoc.body) {
                        iDoc.documentElement.style.setProperty('background-color', '#000000', 'important');
                        iDoc.body.style.setProperty('background-color', '#000000', 'important');
                        iDoc.body.style.setProperty('color', '#CCCCCC', 'important');
                    }
                } catch(e) {}
            }
        } catch(e) {}

        // 5. MutationObserver watches dynamic DOM (including async elements like popups)
        // After page navigation, old observer is bound to old DOM; must recreate
        if (window.__mercuryDarkObserver) {
            try { window.__mercuryDarkObserver.disconnect(); } catch(e) {}
            window.__mercuryDarkObserver = null;
        }
        if (!window.__mercuryDarkObserver) {
            function darkenElementFull(el) {
                try {
                    var cs = window.getComputedStyle(el);
                    var elR = el.getBoundingClientRect();
                    var isSmallDec = (elR.height <= 30 || elR.width <= 60);
                    // Background color
                    var bgB = getBrightness(cs.backgroundColor);
                    if (bgB > 50) {
                        if (isSmallDec) {
                            var bgM2 = cs.backgroundColor.match(/\d+/g);
                            if (bgM2 && bgM2.length >= 3) {
                                var maxC2 = Math.max(parseInt(bgM2[0]), parseInt(bgM2[1]), parseInt(bgM2[2]));
                                var minC2 = Math.min(parseInt(bgM2[0]), parseInt(bgM2[1]), parseInt(bgM2[2]));
                                if (maxC2 - minC2 <= 30) {
                                    el.style.setProperty('background-color', '#000000', 'important');
                                }
                            } else {
                                el.style.setProperty('background-color', '#000000', 'important');
                            }
                        } else {
                            el.style.setProperty('background-color', '#000000', 'important');
                        }
                    }
                    // Gradient background (preserve gradients on small decorative elements like progress bars)
                    var bgImg = cs.backgroundImage;
                    if (bgImg && bgImg !== 'none' && bgImg.indexOf('gradient') !== -1) {
                        if (!isSmallDec) {
                            el.style.setProperty('background-image', 'none', 'important');
                            el.style.setProperty('background-color', '#000000', 'important');
                        }
                    }
                    // Text color
                    var cB = getBrightness(cs.color);
                    if (cB >= 0 && cB < 100) el.style.setProperty('color', '#CCCCCC', 'important');
                    // box-shadow (remove light-colored shadows)
                    if (!el.classList.contains('focused') && !el.classList.contains('__mercury_focused')) {
                        var shadow = cs.boxShadow;
                        if (shadow && shadow !== 'none') {
                            var sB = getBrightness(shadow);
                            if (sB > 128) el.style.setProperty('box-shadow', 'none', 'important');
                        }
                    }
                    // Outline
                    if (!el.classList.contains('__mercury_focused')) {
                        var oB = getBrightness(cs.outlineColor);
                        if (oB > 128) el.style.setProperty('outline-color', '#333333', 'important');
                    }
                    // border-color (light-colored borders)
                    if (!el.classList.contains('focused') && !el.classList.contains('__mercury_focused')) {
                        var borderB = getBrightness(cs.borderTopColor);
                        if (borderB > 128) el.style.setProperty('border-color', '#333333', 'important');
                    }
                } catch(e) {}
            }
            function darkenTree(root) {
                if (root.nodeType !== 1) return;
                darkenElementFull(root);
                var children = root.querySelectorAll('*');
                for (var k = 0; k < children.length; k++) darkenElementFull(children[k]);
            }
            // Delayed secondary processing (after async rendering of popups, etc.)
            var pendingDarkenTimer = null;
            function scheduleDarkenRetry(roots) {
                if (pendingDarkenTimer) clearTimeout(pendingDarkenTimer);
                pendingDarkenTimer = setTimeout(function() {
                    for (var r = 0; r < roots.length; r++) darkenTree(roots[r]);
                    // Refresh focusable list after popup appears
                    if (window.__mercuryFocus && window.__mercuryFocus.refresh) {
                        window.__mercuryFocus.refresh();
                    }
                    pendingDarkenTimer = null;
                }, 150);
            }
            window.__mercuryDarkObserver = new MutationObserver(function(mutations) {
                var addedRoots = [];
                for (var m = 0; m < mutations.length; m++) {
                    var added = mutations[m].addedNodes;
                    for (var n = 0; n < added.length; n++) {
                        var node = added[n];
                        darkenTree(node);
                        if (node.nodeType === 1) addedRoots.push(node);
                    }
                    // Also handle attribute changes (e.g., style changes making elements bright)
                    if (mutations[m].type === 'attributes' && mutations[m].target.nodeType === 1) {
                        darkenElementFull(mutations[m].target);
                    }
                }
                // Delayed secondary processing: popup internals may have async-rendered children
                if (addedRoots.length > 0) {
                    scheduleDarkenRetry(addedRoots);
                }
            });
            window.__mercuryDarkObserver.observe(document.body, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['style', 'class']
            });
        }
    """.trimIndent()

    // ============================================================
    // Focus Management System
    // ============================================================

    private fun focusSystemJs(): String = """
        // ========== Focus Management System ==========
        (function initFocusSystem() {
            // Must reinitialize focus system after each page navigation,
            // since old focusableElements reference DOM elements from the previous page.
            // Clean up old focus state
            if (window.__mercuryFocus) {
                try {
                    var oldEls = document.querySelectorAll('.__mercury_focused');
                    for (var oi = 0; oi < oldEls.length; oi++) {
                        oldEls[oi].classList.remove('__mercury_focused');
                    }
                } catch(e) {}
                window.__mercuryFocus = null;
            }

            var focusStyleId = '__mercury_focus_style__';
            if (!document.getElementById(focusStyleId)) {
                var fs = document.createElement('style');
                fs.id = focusStyleId;
                fs.textContent = [
                    '.__mercury_focused { outline: 3px solid #00E5FF !important; outline-offset: 3px !important; box-shadow: 0 0 16px 4px rgba(0,229,255,0.7), inset 0 0 8px rgba(0,229,255,0.15) !important; background-color: rgba(0,229,255,0.12) !important; transition: outline 0.15s ease, box-shadow 0.15s ease !important; }',
                    '.__mercury_focused:not(a):not(button):not(input) { background-color: rgba(0,229,255,0.15) !important; }'
                ].join('\n');
                (document.head || document.documentElement).appendChild(fs);
            }

            function collectClickableElements() {
                var seen = new Set();
                var clickable = [];

                function isVisible(el) {
                    try {
                        var rect = el.getBoundingClientRect();
                        if (rect.width <= 0 || rect.height <= 0) return false;
                        var cs = window.getComputedStyle(el);
                        if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) <= 0) return false;
                        return true;
                    } catch(e) { return false; }
                }

                // Detect modal popups (ARIA attributes + CSS fixed/absolute overlays)
                function findModalContainer() {
                    var bestModal = null;
                    var bestZIndex = -1;

                    // Priority 1: Standard ARIA dialog attributes
                    var ariaCandidates = document.querySelectorAll('[role="dialog"], [role="alertdialog"], [aria-modal="true"]');
                    for (var c = 0; c < ariaCandidates.length; c++) {
                        var el = ariaCandidates[c];
                        var cs = window.getComputedStyle(el);
                        if (cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity) > 0) {
                            if (el.querySelector('a, button, input, textarea, select, [onclick], [role="button"]')) {
                                var z = parseInt(cs.zIndex) || 0;
                                if (z > bestZIndex) { bestModal = el; bestZIndex = z; }
                            }
                        }
                    }
                    if (bestModal) return bestModal;

                    // Priority 2: CSS-based overlays (position:fixed/absolute + high z-index + contains interactive elements)
                    // This covers popups on sites that don't use ARIA attributes
                    var allEls = document.body.children;
                    var viewW = window.innerWidth;
                    var viewH = window.innerHeight;
                    for (var i = 0; i < allEls.length; i++) {
                        var el = allEls[i];
                        try {
                            var cs = window.getComputedStyle(el);
                            if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) <= 0) continue;
                            var pos = cs.position;
                            if (pos !== 'fixed' && pos !== 'absolute') continue;
                            var z = parseInt(cs.zIndex) || 0;
                            if (z < 100) continue; // Low z-index fixed elements are usually not popups
                            // Must contain interactive elements
                            if (!el.querySelector('a, button, input, textarea, select, [onclick], [role="button"]')) continue;
                            if (z > bestZIndex) { bestModal = el; bestZIndex = z; }
                        } catch(e) {}
                    }
                    // Also check deeper-level fixed elements (some popups are nested inside)
                    if (!bestModal) {
                        var fixedEls = document.querySelectorAll('*');
                        for (var j = 0; j < fixedEls.length; j++) {
                            var el = fixedEls[j];
                            try {
                                var cs = window.getComputedStyle(el);
                                if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) <= 0) continue;
                                if (cs.position !== 'fixed') continue;
                                var z = parseInt(cs.zIndex) || 0;
                                if (z < 100) continue;
                                if (!el.querySelector('a, button, input, textarea, select, [onclick], [role="button"]')) continue;
                                // Check if it covers a significant area (at least 20% of viewport width)
                                var rect = el.getBoundingClientRect();
                                if (rect.width < viewW * 0.2) continue;
                                if (z > bestZIndex) { bestModal = el; bestZIndex = z; }
                            } catch(e) {}
                        }
                    }
                    return bestModal;
                }

                var modalRoot = findModalContainer();
                // If a popup is detected, limit search scope to the popup container
                var searchRoot = modalRoot || document;

                function addEl(el) {
                    if (!seen.has(el) && isVisible(el)) {
                        // If a popup exists, only collect elements inside the popup
                        if (modalRoot && !modalRoot.contains(el)) return;
                        seen.add(el);
                        clickable.push(el);
                    }
                }

                // Round 1: CSS selectors to directly match definite interactive elements
                var selectors = [
                    'a[href]',                          // Links
                    'button',                           // Buttons
                    'input:not([type="hidden"])',        // All visible inputs
                    'textarea',                         // Text areas
                    'select',                           // Dropdown selects
                    'label[for]',                       // Labels associated with form controls
                    'summary',                          // details/summary collapsible
                    '[onclick]',                        // Inline onclick
                    '[onmousedown]',                    // Inline onmousedown
                    '[onmouseup]',                      // Inline onmouseup
                    '[ontouchstart]',                   // Inline touch events
                    '[ontouchend]',                     // Inline touch events
                    '[role="button"]',                  // ARIA button
                    '[role="link"]',                    // ARIA link
                    '[role="tab"]',                     // ARIA tab
                    '[role="menuitem"]',                // ARIA menu item
                    '[role="menuitemcheckbox"]',         // ARIA menu item checkbox
                    '[role="menuitemradio"]',            // ARIA menu item radio
                    '[role="option"]',                  // ARIA listbox option
                    '[role="switch"]',                  // ARIA switch
                    '[role="checkbox"]',                // ARIA checkbox
                    '[role="radio"]',                   // ARIA radio
                    '[role="combobox"]',                // ARIA combobox
                    '[role="searchbox"]',               // ARIA searchbox
                    '[role="slider"]',                  // ARIA slider
                    '[role="spinbutton"]',              // ARIA spinbutton
                    '[role="treeitem"]',                // ARIA tree item
                    '[tabindex]',                       // Explicit tabindex
                    '[contenteditable="true"]',         // Editable regions
                    '[data-href]',                      // Custom link attribute
                    '[data-url]',                       // Custom link attribute
                    '[data-link]',                      // Custom link attribute
                    '[data-click]',                     // Custom click attribute
                    '[data-action]',                    // Custom action attribute
                    'details',                          // Collapsible details
                    'audio[controls]',                  // Audio with controls
                    'video[controls]'                   // Video with controls
                ];
                var selectorStr = selectors.join(', ');
                var matched = searchRoot.querySelectorAll(selectorStr);
                for (var i = 0; i < matched.length; i++) {
                    addEl(matched[i]);
                }

                // Round 2: Traverse all elements, detect cursor:pointer or JS event listeners
                var allEls = searchRoot.querySelectorAll('*');
                for (var j = 0; j < allEls.length; j++) {
                    var el = allEls[j];
                    if (seen.has(el)) continue;
                    try {
                        var cs = window.getComputedStyle(el);
                        var isClickable = false;

                        // cursor: pointer is the most common clickable hint
                        if (cs.cursor === 'pointer') isClickable = true;

                        // Check for click events bound via addEventListener
                        // (Libraries like jQuery set _onclick, jQuery data, etc. on elements)
                        if (!isClickable && el._onclick) isClickable = true;

                        // Check jQuery-bound events (if the page uses jQuery)
                        if (!isClickable && typeof jQuery !== 'undefined') {
                            try {
                                var events = jQuery._data(el, 'events');
                                if (events && (events.click || events.mousedown || events.touchstart || events.tap)) {
                                    isClickable = true;
                                }
                            } catch(e2) {}
                        }

                        // Check common Vue/React event binding attributes
                        if (!isClickable) {
                            var attrs = el.attributes;
                            for (var k = 0; k < attrs.length; k++) {
                                var attrName = attrs[k].name.toLowerCase();
                                // Vue: @click, v-on:click
                                // React: uses __reactFiber$ internally, cannot detect directly
                                // but usually sets cursor:pointer, already covered above
                                if (attrName.indexOf('@click') !== -1 ||
                                    attrName.indexOf('v-on:click') !== -1 ||
                                    attrName.indexOf('ng-click') !== -1 ||    // AngularJS
                                    attrName.indexOf('(click)') !== -1 ||     // Angular
                                    attrName.indexOf('data-toggle') !== -1 || // Bootstrap
                                    attrName.indexOf('data-dismiss') !== -1 ||// Bootstrap
                                    attrName.indexOf('data-bs-toggle') !== -1 // Bootstrap 5
                                ) {
                                    isClickable = true;
                                    break;
                                }
                            }
                        }

                        if (isClickable) addEl(el);
                    } catch(e) {}
                }

                // Round 3: Filter — if both parent and child are in the list,
                // remove the parent to avoid duplicate focus
                // (simple filtering only, no deep recursion)
                var filtered = [];
                for (var f = 0; f < clickable.length; f++) {
                    var item = clickable[f];
                    var dominated = false;
                    // If item is an ancestor of another clickable element and is not a/button/input,
                    // skip it (let the more specific child element receive focus)
                    if (item.tagName !== 'A' && item.tagName !== 'BUTTON' && item.tagName !== 'INPUT' && item.tagName !== 'SELECT' && item.tagName !== 'TEXTAREA') {
                        for (var g = 0; g < clickable.length; g++) {
                            if (f !== g && item.contains(clickable[g]) && item !== clickable[g]) {
                                dominated = true;
                                break;
                            }
                        }
                    }
                    if (!dominated) filtered.push(item);
                }

                return filtered;
            }

            var focusableElements = collectClickableElements();
            var currentIndex = -1;

            function setFocusIndex(idx) {
                if (currentIndex >= 0 && currentIndex < focusableElements.length) {
                    focusableElements[currentIndex].classList.remove('__mercury_focused');
                }
                currentIndex = idx;
                if (currentIndex >= 0 && currentIndex < focusableElements.length) {
                    var el = focusableElements[currentIndex];
                    el.classList.add('__mercury_focused');
                    try { el.scrollIntoView({ behavior: 'auto', block: 'center', inline: 'center' }); } catch(e) { el.scrollIntoView(true); }
                }
            }

            // Page scroll step (pixels), adjustable based on screen size
            var SCROLL_STEP = Math.max(120, Math.round(window.innerHeight * 0.35));

            function scrollPage(direction) {
                // direction > 0 scrolls down, < 0 scrolls up
                window.scrollBy({ top: direction * SCROLL_STEP, behavior: 'smooth' });
            }

            // Check if element is within viewport (or near viewport edge)
            function isNearViewport(el, margin) {
                try {
                    var rect = el.getBoundingClientRect();
                    var vh = window.innerHeight;
                    // Element top is within margin below viewport, or bottom is within margin above viewport
                    return rect.top < vh + margin && rect.bottom > -margin;
                } catch(e) { return true; }
            }

            function moveFocus(direction) {
                // Re-collect clickable elements (DOM may have changed)
                var oldEl = currentIndex >= 0 && currentIndex < focusableElements.length ? focusableElements[currentIndex] : null;
                focusableElements = collectClickableElements();
                if (oldEl) {
                    var newIdx = focusableElements.indexOf(oldEl);
                    if (newIdx >= 0) currentIndex = newIdx;
                    else currentIndex = -1;
                }

                // If focusable elements <= 1, convert swipe gesture to page scroll
                if (focusableElements.length <= 1) {
                    scrollPage(direction);
                    return;
                }

                // Calculate candidate index for next focus
                var nextIndex;
                if (currentIndex < 0) {
                    nextIndex = direction > 0 ? 0 : focusableElements.length - 1;
                } else {
                    nextIndex = currentIndex + direction;
                    if (nextIndex >= focusableElements.length) nextIndex = 0;
                    if (nextIndex < 0) nextIndex = focusableElements.length - 1;
                }

                var nextEl = focusableElements[nextIndex];
                var vh = window.innerHeight;
                // Allow focus to jump directly within ± 0.5x viewport height
                // If next focus is beyond this range, scroll first to show intermediate content
                var jumpThreshold = vh * 0.5;

                // Check if next focus is too far from current viewport
                if (!isNearViewport(nextEl, jumpThreshold)) {
                    // Next focus too far; scroll a distance to let user read intermediate content
                    scrollPage(direction);
                    // Don't switch focus; keep current focus unchanged
                    return;
                }

                // Next focus is within acceptable range; switch normally
                setFocusIndex(nextIndex);
            }

            function clickFocused() {
                if (currentIndex < 0 || currentIndex >= focusableElements.length) return;
                var el = focusableElements[currentIndex];

                // Get element center coordinates for constructing realistic events
                var rect = el.getBoundingClientRect();
                var cx = rect.left + rect.width / 2;
                var cy = rect.top + rect.height / 2;
                var eventOpts = { bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy };

                // Simulate complete click event sequence (SPA frameworks usually depend on these)
                el.dispatchEvent(new PointerEvent('pointerdown', eventOpts));
                el.dispatchEvent(new MouseEvent('mousedown', eventOpts));
                el.focus();
                el.dispatchEvent(new PointerEvent('pointerup', eventOpts));
                el.dispatchEvent(new MouseEvent('mouseup', eventOpts));
                el.dispatchEvent(new MouseEvent('click', eventOpts));

                // For <a href="..."> links, manually navigate if click event wasn't prevented
                if (el.tagName === 'A' && el.href) {
                    var href = el.getAttribute('href');
                    if (href && href !== '#' && href.indexOf('javascript:') !== 0) {
                        // Slight delay to give frameworks a chance to handle routing
                        setTimeout(function() {
                            // Check if URL has changed (framework may have already handled routing)
                            if (window.location.href.indexOf(el.href) === -1) {
                                window.location.href = el.href;
                            }
                        }, 100);
                    }
                }
            }

            window.__mercuryFocus = {
                moveFocus: moveFocus,
                clickFocused: clickFocused,
                getCount: function() { return focusableElements.length; },
                getCurrentIndex: function() { return currentIndex; },
                refresh: function() { focusableElements = collectClickableElements(); }
            };

            if (focusableElements.length > 0) setFocusIndex(0);
        })();
    """.trimIndent()
}