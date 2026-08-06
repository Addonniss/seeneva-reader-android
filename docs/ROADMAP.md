# Seeneva Reader Personal Fork Roadmap

## Phase 1 — Viewer UX

- [x] Instant viewer interactions (implemented as viewer setting "Instant viewer interactions"; replaces "Disable viewer animations" naming)
- [x] Direct bubble selection
- [x] Bubble size adjustment (viewer setting "Bubble size": percentage slider 100%–400% scaling the enlarged bubble relative to page min scale; manual device verification by the Romanian user)
- [x] Maximum zoom adjustment (viewer setting "Maximum zoom": 1×–10× in 1× increments, default 2× preserving the original behavior; persistent; manually verified on the physical device by the Romanian user)
- [x] Double-tap page navigation (direction-aware for LTR/RTL; at fit scale double-tap navigates pages, when zoomed the original double-tap zoom behavior is preserved; configurable in Viewer Settings "Double-tap page navigation", defaults to ON; manually verified on the physical device by the Romanian user)
- [x] Alternative thumbnail navigation gesture (configurable viewer gestures in Viewer Settings: "Bottom swipe up", "Two-finger double-tap at bottom", "Two-finger double-tap at top"; each maps independently to None / Thumbnail Navigation / Settings; defaults: bottom swipe = None (the old bottom-to-top gesture no longer opens Thumbnail Navigation), two-finger bottom = Thumbnail Navigation, two-finger top = Settings; system-bar reveal is decoupled from the app UI so disabling the bottom swipe never shows Thumbnail Navigation; manually verified on the physical device by the Romanian user)

## Phase 2 — Advanced Zoom

- [x] Two-finger rectangular area zoom (two-finger hold ~150ms activates a rectangle selection preview; movement before the hold completes is treated as a normal pinch; a slow pinch never activates the rectangle via a stillness window that restarts the hold timer; release zooms immediately through deferred execution so the subsampling view resets its gesture state first — pan/pinch work right after the zoom with no extra tap; the terminating ACTION_UP pointer-count fix prevents the selector from staying stuck; scaling is a geometry-based FIT/FILL hybrid: panels FIT completely with the longer edge governing and no crop, strips/page halves/bands FILL so they still zoom; ignores insignificant zooms and respects the Maximum Zoom limit; manually verified on the physical device by the Romanian user)
- [ ] Instant zoom update on gesture release
- [ ] Pinch-out / zoom-out behavior

## Phase 3 — Alternative Reading Targets

- [ ] Investigate existing panel detection
- [ ] Panel reading mode
- [ ] Bubble / panel mode selector

## Engineering

- [ ] Add tests for reading order
- [ ] Add tests for new viewer behavior
- [ ] Manual test on physical Android device
