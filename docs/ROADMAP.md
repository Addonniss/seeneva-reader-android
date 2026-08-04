# Seeneva Reader Personal Fork Roadmap

## Phase 1 — Viewer UX

- [x] Instant viewer interactions (implemented as viewer setting "Instant viewer interactions"; replaces "Disable viewer animations" naming)
- [x] Direct bubble selection
- [x] LTR / RTL bubble reading order (Phase 3: fixed RTL reading order for offset columns — right column/panel now precedes the left one regardless of vertical starting position; LTR behavior preserved exactly; tested with aligned and vertically offset RTL columns, with and without panels; manually verified on the physical device by the Romanian user)
- [x] Bubble size adjustment (viewer setting "Bubble size": percentage slider 100%–250% scaling the enlarged bubble relative to page min scale; manual device verification by the Romanian user)
- [ ] Maximum zoom adjustment
- [ ] Double-tap page navigation
- [ ] Alternative thumbnail navigation gesture

## Phase 2 — Advanced Zoom

- [ ] Two-finger rectangular area zoom
- [ ] Instant zoom update on gesture release
- [ ] Pinch-out / zoom-out behavior

## Phase 3 — Alternative Reading Targets

- [ ] Investigate existing panel detection
- [ ] Panel reading mode
- [ ] Bubble / panel mode selector

## Engineering

- [x] Add tests for reading order (logic module `PageObjectHelperTest` — synthetic LTR/RTL fixtures; known pre-existing LTR limitation fixtures documented separately in IMPLEMENTATION_NOTES)
- [ ] Add tests for new viewer behavior
- [ ] Manual test on physical Android device
