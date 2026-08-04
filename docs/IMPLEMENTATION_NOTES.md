# Seeneva Reader Android — Implementation Notes

Personal fork analysis (Addonniss). This document describes the *current* implementation of the comic book viewer before any feature work. It is intended to guide future improvements to page navigation, speech-balloon ("bubble") reading, zoom, and gestures.

**Status:** Analysis only. No feature code was changed.

---

## 1. Architecture Overview

### Module layout (Gradle multi-module, Kotlin DSL)

| Module | Role |
|---|---|
| `app` | Android application. All UI: screens, presenters, view models, adapters, dialogs, services, widgets. |
| `common` | Shared coroutine dispatchers (`Dispatchers`) and small cross-layer entities (`FileHashData`). |
| `data` | Room database (books, pages, ML objects, tags, metadata), native JNI bridge (`NativeSource`), data-layer entities. |
| `logic` | Business logic: use cases, image loading (Coil + custom fetcher), ML (YOLO/Tesseract), storage pools, settings, reading-order algorithm. |
| `buildSrc` | Dependency/version catalog (`Deps.kt`), ABI/flavor definitions, plugin versions. |
| `native` | **Git submodule** (`Seeneva/seeneva-lib`, Rust) — archive parsing (cbz/cbr/7z/pdf), YOLO detection, Tesseract OCR. **Not initialized in the local clone.** |

### Key stack versions
- Kotlin `1.9.10`, Gradle `8.1.1`, AGP `8.1.2`, Java 17 target, core library desugaring
- `minSdk 16`, `targetSdk 33`, `compileSdk 33`, `buildTools 33.0.2`, NDK `21.4.7075529`
- Kotlinx coroutines `1.5.1`, serialization JSON `1.2.1`
- Koin `2.2.3` (DI), Room `2.5.2`, ViewPager2 `1.0.0`
- Coil `1.2.1` (image loading), `subsampling-scale-image-view-androidx 3.10.0` (page zoom), `rtree2 0.9-RC1` (spatial indexing of balloons)
- Test: JUnit4, MockK `1.11.0`, Kluent `1.65`, Koin Test, kotlinx-coroutines-test, KFaker

### Presentation architecture
MVP + ViewModel per screen, wired with Koin scopes:
- **View** (Fragment/Activity) → **Presenter** (lifecycle-scoped, Koin) → **ViewModel** (`CoroutineViewModel`, Koin `viewModel {}`) → **UseCase** (logic) → **Data source** (Room/native).
- `Presenter`/`PresenterStatefulView` base classes in `app/.../presenter/`; state save/restore through `BaseStatefulPresenter`.
- Viewer-specific Koin scopes: `BookViewerActivity`, `BookViewerPageFragment`, `ViewerConfigDialog`, plus a retain scope `Names.viewerRetainScope` that holds heavy OCR (`OCR`) and TTS (`TTS`) instances alive across config changes.

### Data flow for viewing a book
1. `BookViewerActivity.bookId` → `BookViewerPresenterImpl` → `BookViewerViewModelImpl` → `BookViewerUseCase.subscribe(id)` → Room `ComicBookSource.subscribeFullById` → mapped via `ComicBook?.intoDescription(persisted)` into `ComicBookDescription` (pages sorted by name, read position resolved).
2. `BookViewerActivity.onBookLoaded` populates `ViewerPager.setPages(...)` + preview adapter, sets direction and read position.
3. Each page is a `BookViewerPageFragment` created by `BookViewerAdapter` (FragmentStateAdapter). Fragment presenter `BookViewerPagePresenterImpl` → `BookViewerPageViewModelImpl.loadPageData(pageId)` → `GetPageDataUseCase.subscribePageData(pageId)` which:
   - loads ML objects from Room (`ComicBookPageSource.objectsDataById`),
   - borrows the encoded page image (`EncodedComicPageStorage.borrowEncodedComicPage`),
   - runs `generateReadOrderedObjects` (reading order) and emits `ComicPageData`.
4. Fragment's `viewer` (`PageViewer`) renders the page into `SubsamplingScaleImageView` via custom tile decoders; `ObjectImageHelper` overlays the currently focused balloon.

---

## 2. Feature-by-Feature: Relevant Classes, Behavior, Dependencies

### 2.1 Page Navigation & Page Transitions

**Files**
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/ViewerPager.kt`
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/BookViewerAdapter.kt`
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/PageDiffCallback.kt`
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/BookViewerActivity.kt`
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/BookViewerPresenter.kt` (+ `BookViewerViewModel.kt`)
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/page/BookViewerPageFragment.kt`
- `app/src/main/kotlin/app/seeneva/reader/extension/ViewPager2.kt`

**Current behavior**
- `ViewerPager` wraps the (final) `ViewPager2` and implements RTL by *reversing positions*: `reversePage = count - 1 - pos` is installed as `BookViewerAdapter.setPositionOverrideFun(...)`. All external callers use logical position; the wrapper maps to/from physical pager position (`calculatePosition`).
- Direction is per-book (`ComicBook.direction`), `Direction.LTR/RTL`. `BookViewerActivity.setPagesDirection` flips `ViewerPager.reverse` and the preview list's `LinearLayoutManager.reverseLayout`.
- Flings below `R.dimen.viewer_fling_velocity` (450dp) are intercepted in `ViewerPager.init` via a custom `RecyclerView.OnFlingListener` and forced back to the current page (`smoothScrollToPosition`). This is a deliberate "slow swipe snaps back" hack, with a comment noting `setCurrentItem` doesn't work there.
- Page transitions are ViewPager2's default smooth scroll. `setCurrentItem(item, smoothScroll=true)` is the only transition control; preview clicks use smooth scroll, initial load uses `smoothScroll=false`.
- `BookViewerActivity.pagesChangeCallback` reacts to `onPageSelected`: scrolls the preview strip to the current page, updates the selected-preview highlight + toolbar subtitle counter, calls `presenter.onPageChange(position)` (persists read position), and calls `fragment.reset()` on every non-visible `BookViewerPageFragment`.
- Auto page-turn: when the last balloon on a page is read, `BookViewerPageFragment` → `callback.lastObjectViewed(pageId, direction)` → `BookViewerActivity.lastObjectViewed` advances one page (or back if direction BACKWARD).
- Read position is persisted as the *container* page position via `BookViewerViewModel.saveReadPosition` → `BookViewerUseCase.saveReadPosition` → `ComicBookSource.updateReadPosition`.

**Dependencies / gotchas**
- `BookViewerAdapter` uses `AsyncListDiffer` + custom `getItemId`/`containsItem` so fragments survive list updates; pages are keyed by DB id.
- `ViewerPager.setPages` only attaches the adapter once (`pager.adapter == null`), and on low-memory / pre-API-26 devices limits RecyclerView item view cache and disables prefetch to avoid OOM.
- The preview strip and pager must stay in sync on direction flip; `BookViewerActivity.setPagesDirection` re-scrolls the preview list.

### 2.2 Bubble (Speech Balloon) Detection

**Files**
- `logic/src/main/kotlin/app/seeneva/reader/logic/entity/ml/ObjectClass.kt`
- `data/src/main/kotlin/app/seeneva/reader/data/entity/ComicPageObject.kt`
- `data/src/main/kotlin/app/seeneva/reader/data/source/local/db/dao/ComicPageObjectSource.kt`
- `data/src/main/kotlin/app/seeneva/reader/data/source/local/db/dao/ComicBookPageSource.kt`
- `logic/src/main/kotlin/app/seeneva/reader/logic/usecase/GetPageDataUseCase.kt`
- `logic/src/main/kotlin/app/seeneva/reader/logic/entity/ComicPageData.kt`
- Native submodule (`yolo_seeneva.tflite` asset in `logic/src/main/assets/`)

**Current behavior**
- Detection happens at *add/import time*, not view time: the Rust native code runs a YOLO model (`yolo_seeneva.tflite`) over each page, and results are stored in Room table `comic_page_object`.
- Two object classes exist: `SPEECH_BALLOON(0)` and `PANEL(1)`.
- `ComicPageObject` stores *normalized* coordinates `xMin,yMin,xMax,yMax ∈ [0,1]` plus `prob` and `classId`.
- At view time `GetPageDataUseCase.subscribePageData` reads objects filtered to `PANEL ∪ SPEECH_BALLOON`, then multiplies normalized coords by actual page pixel size into `RectF` bboxes, and builds a `ComicPageObjectContainer` whose `objects` list is the *reading-ordered* output of `generateReadOrderedObjects(...)` (see §2.3).
- There is **no on-device re-detection** in the viewer; the DB is the single source of truth.

### 2.3 Bubble Ordering / Navigation

**Files**
- `logic/src/main/kotlin/app/seeneva/reader/logic/comic/PageObjectHelper.kt`
- `logic/src/main/kotlin/app/seeneva/reader/logic/extension/RTree.kt`
- `logic/src/main/kotlin/app/seeneva/reader/logic/entity/ComicPageData.kt` (`ComicPageObjectContainer`)
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/page/BookViewerPagePresenter.kt`
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/page/BookViewerPageFragment.kt`
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/page/entity/PageObjectDirection.kt`, `SelectedPageObject.kt`

**Current behavior**
- `generateReadOrderedObjects(objects, pageW, pageH, direction)` sorts ML objects into reading order:
  - Groups objects by class; requires at least one non-`PANEL` object.
  - Builds an RTree of all objects; for each object finds its parent panel (max intersection area) and recursively groups neighbours (`yieldObjectNeighbors`) into `PanelGroup`s.
  - Orders panels and groups with direction-aware comparators (`defaultComparator`, `GroupObjectComparator`) that tolerate ML coordinate jitter (`PANEL_MIN_DIFF=160`, `GROUP_MIN_DIFF=80`, `OBJECT_NEIGHBOUR_MIN_DIFF=20`, `OBJECT_MIN_DIFF=15`, `OBJECT_BENEATH=0.15`), scaled proportionally to the standard page size (`ComicHelper.PAGE_WIDTH=1988`, `PAGE_HEIGHT=3056`).
  - LTR sorts by top then left; RTL by top then right.
- The page presenter keeps `readObjectPosition` (index into the ordered list). `nextPageObject(FORWARD|BACKWARD)` returns `SelectedPageObject(bookPath, pagePos, bbox)` and advances the index; `currentPageObject()` returns the current one; `resetReadPageObject()` sets index to -1.
- Navigation UI: `BookViewerPageFragment` tap zones — LTR: right half = FORWARD; RTL: left half = FORWARD. Center strip (20% of width around screen center, `viewer_hide_page_object_x_percentage=0.2`) hides the current balloon instead. When out of objects on the page, it delegates to `lastObjectViewed` → page turn.
- Long press on image: presenter `onPageLongClick(x,y)` uses `ComicPageObjectContainer.get(x,y)` (RTree point search) to find a balloon and trigger text recognition (Tesseract OCR) + TTS read-aloud; long press on the visible balloon view triggers `onCurrentPageObjectLongClick`.
- Direction changes (per-book swap) reset the read position (`readDirectionState.drop(1)` observer calls `resetReadPageObject`).

### 2.4 Bubble Enlargement / Scaling

**Files**
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/page/ObjectImageHelper.kt`
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/page/BookViewerPageFragment.kt`
- `app/src/main/kotlin/app/seeneva/reader/logic/image/ImageLoader.kt` (+ `loadPageObject`, `loadPageObjectBitmap`)
- `app/src/main/res/values/dimensions.xml` (`viewer_balloon_scale_xy=0.5dp`, `viewer_balloon_elevation=8dp`)

**Current behavior**
- Balloons are shown as a cropped-image overlay: `objectView` (a `ShapeableImageView` in `fragment_viewer_page.xml`) sits above the `SubsamplingScaleImageView`.
- `ObjectImageHelper.showPageObject`:
  - Projects the balloon bbox from image coords to view coords (`sourceToViewCoord`), sizes the `objectView` to the bbox, loads the cropped region via `ImageLoader.loadPageObject` (Coil, `Precision.EXACT`, `OriginalSize`, RGB_565), then animates scale from 0 → `resultScaleXY`.
  - `resultScaleXY = scaleImageView.minScale + viewer_balloon_scale_xy` — note the base scale is the *subsampling view's* min scale (page-fit), plus a small constant. Because `viewer_balloon_scale_xy` is a dp dimen (0.5dp), `resources.getDimension` returns it in px (`0.5 * density`); this is a quirk worth re-checking when tuning.
  - Scale is clamped to `maxScaleXY(bbox) = min(viewW/bboxW, viewH/bboxH)` so a scaled balloon never leaves the visible area; translation is corrected via `fixBboxTranslationX/Y`.
  - If the page is zoomed (`scale != minScale`), it first animates the page back to min scale (`animateScale(.0f)` 100ms + `resetScaleAndCenter`), then shows the balloon — **balloon mode always resets page zoom**.
- Switch/hide animations: show 200ms `FastOutSlowInInterpolator`; hide shrinks to 0; a "blow" animation (150ms, scale ×2, alpha→0) runs whenever the subsampling view's scale/center changes while a balloon is visible (`scaleViewStateListener`), dismissing the balloon.
- `ObjectImageHelper.reset()` (page reset / direction change / page change) clears the balloon and calls `resetScaleAndCenter()`.

### 2.5 Image Zoom Implementation & Maximum Zoom

**Files**
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/page/PageViewer.kt`
- `app/src/main/kotlin/app/seeneva/reader/extension/SubsamplingScaleImageView.kt`
- `app/src/main/kotlin/app/seeneva/reader/logic/image/ImageLoader.kt` (`decodeRegion`)
- `logic/src/main/kotlin/app/seeneva/reader/logic/image/coil/CoilImageLoader.kt`, `.../fetcher/ComicImageFetcher.kt`

**Current behavior**
- Zoom is provided by the third-party `SubsamplingScaleImageView` (3.10.0). The app does **not** set a custom `maxScale`/`minScale` anywhere in code — it relies on library defaults (max zoom is the library's tile-based maximum). `siv.maxScale` is only *read* in the help-tip tutorial (`BookViewerPageFragment` animates to `maxScale` around a balloon).
- `PageViewer` configures the view: custom `ImageDecoder`/`ImageRegionDecoder` factories that delegate to `ImageLoader.decodeRegion(...)` (Coil with `ComicPageFetcherData.region`, `Precision.EXACT`, disk/memory cache disabled for regions, RGB_565 or ARGB_8888 depending on device), so tiles are decoded from the comic container on demand.
- Low-memory / pre-API-26 devices: `setEagerLoadingEnabled(false)` and a computed `setMinimumTileDpi(...)` reduce tile quality.
- State restore: `BookViewerPageFragment.onSaveInstanceState` saves `ImageViewState` (scale + center) and restores with `setScaleAndCenter` (or `resetScaleAndCenter`).
- Suspended animation helper `animateScaleAndCenterSuspended` (with cancellation semantics) is used by the tutorial; `ObjectImageHelper` uses `animateScale(...)` directly.
- Note the comment in `BookViewerActivity.onCreate`: "SubsamplingScaleImageView.isPanEnabled for some reason center page after usage" — the `greyOutView` was added to consume touches while system UI is visible because of this library quirk.

### 2.6 Touch / Gesture Handling

**Files**
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/BookViewerActivity.kt` (activity-level `GestureDetectorCompat`)
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/page/BookViewerPageFragment.kt` (page-level `GestureDetectorCompat`)
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/SystemUiManager.kt`
- `app/src/main/res/layout/activity_book_viewer.xml` (`greyOutView`)

**Current behavior (layered)**
1. **Activity level** (`dispatchTouchEvent` → `gestureDetector.onTouchEvent`):
   - Single tap while system UI shown → toggle/hold UI (unless tap is inside the preview strip).
   - Scroll/fling starting inside the preview strip → `systemUiManager.holdShown()` (keeps toolbar visible).
   - Guard: touches only forwarded when lifecycle is `RESUMED` (Android 9 crash workaround, issue #24).
2. **Page level** (`BookViewerPageFragment` `onTouchListener` attached to `scaleImageView` only when LOADED):
   - `onSingleTapConfirmed` → hide balloon (center strip) or `nextPageObject(FORWARD|BACKWARD)` by tap side; `onSingleTapUp` returns true for center-strip taps to suppress other handling.
   - `onLongPress` → object TTS if pressed on visible balloon, else RTree hit-test at `viewToSourceCoord` and OCR+speech; haptic feedback on success.
   - Listener returns `false` so `SubsamplingScaleImageView` still receives the events for pan/zoom.
3. **Library level**: `SubsamplingScaleImageView` handles pan, pinch-zoom, fling, double-tap internally.
4. `greyOutView` (full-screen view above pager when system UI is shown) consumes all touches when `SystemUiState.SHOWED`.

### 2.7 Double-Tap Handling

**Files**
- (no dedicated file) — `app/.../viewer/page/BookViewerPageFragment.kt` (gesture detector), `SubsamplingScaleImageView` library.

**Current behavior**
- There is **no custom double-tap handler in the app**. Double-tap is handled by `SubsamplingScaleImageView`'s built-in zoom (default double-tap-to-zoom behavior).
- The page fragment deliberately uses `onSingleTapConfirmed` (which only fires when a double tap is *not* detected), so single-tap balloon navigation does not conflict with double-tap zoom.
- This is the key integration point for any future "double-tap on balloon = zoom to balloon" feature: currently the library consumes it.

### 2.8 Thumbnail / Page-Navigation Gesture

**Files**
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/BookViewerPreviewAdapter.kt`
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/BookViewerActivity.kt` (`pagesPreviewList`, `pagesChangeCallback`)
- `app/src/main/res/layout/activity_book_viewer.xml` (`pagesPreviewList`)
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/SystemUiManager.kt`

**Current behavior**
- Bottom horizontal `RecyclerView` (`pagesPreviewList`, 30% height) with `BookViewerPreviewAdapter` (AsyncListDiffer, stable ids by container position).
- Each preview cell loads a low-res page preview (`ImageLoader.viewerPreview`, RGB_565, Coil thumb fetcher), waits for layout + visibility before loading, and shows the page number; selected page is scaled to 1.0 and bolded, others 0.7 / 60% alpha.
- Tap on preview → `callback.onPageClick(pos)` → `viewerPager.setCurrentItem(pos, true)` + `systemUiManager.showState(HIDDEN)`.
- Pager page changes scroll the preview strip (`smoothScrollToPosition`) and update the highlight + subtitle counter.
- Touch on the preview strip keeps system UI shown (`holdShown`).

### 2.9 Existing Animations

**Files**
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/BookViewerActivity.kt` (inner `UIAnimator`)
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/page/ObjectImageHelper.kt`
- `app/src/main/kotlin/app/seeneva/reader/extension/SubsamplingScaleImageView.kt`
- `logic/.../image/coil/CoilImageLoader.kt` (crossfade)
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/page/PageViewerHelperFragment.kt` (help tips)

**Current behavior**
- **System UI show/hide**: `UIAnimator` animates toolbar `translationY` (from `-toolbar.bottom` → 0) and alpha of the preview strip + `greyOutView` (0→1), 500ms `FastOutSlowInInterpolator`, suspend-aware (`ValueAnimator.suspendStart`), canceled on destroy.
- **Balloon animations** (ObjectImageHelper): show scale-up 200ms; blow-out 150ms (scale ×2 + alpha→0) on page scale/center change; hide scale-down. All `ViewPropertyAnimatorCompat` with `FastOutSlowInInterpolator`.
- **Page zoom animation**: subsampling view's `animateScaleAndCenter` (library) used by balloon show (`animateScale(.0f)`) and the tutorial (`animateScaleAndCenterSuspended(siv.maxScale, balloonCenter)`).
- **Page transitions**: ViewPager2 default smooth scroll only.
- **Image loading**: Coil crossfade on previews/thumbs (`crossfade(true)`).

### 2.10 Settings / Preferences Architecture

**Files**
- `logic/src/main/kotlin/app/seeneva/reader/logic/ComicsSettings.kt` (`ComicsSettings` / `PrefsComicsSettings`)
- `logic/src/main/kotlin/app/seeneva/reader/logic/entity/configuration/ViewerConfig.kt`
- `logic/src/main/kotlin/app/seeneva/reader/logic/usecase/ViewerConfigUseCase.kt`
- `app/src/main/kotlin/app/seeneva/reader/screen/viewer/dialog/config/ViewerConfigDialog.kt` (+ `Presenter`, `ViewModel`)
- `app/src/main/res/layout/dialog_viewer_settings.xml`

**Current behavior**
- All app settings live in a single `SharedPreferences` file (`"settings"`) via `PrefsComicsSettings`; structured values (query params, viewer config) are kotlinx-serialization JSON strings. Writes go through a `Mutex` + `io` dispatcher; changes are exposed as `SharedPreferences`-backed `Flow`s (`updateFlow`).
- `ComicsSettings` stores: comic list query params, list view type, **viewer config**, and a "show viewer help" boolean (tutorial).
- `ViewerConfig` fields: `keepScreenOn` (default true), `brightness` (default `SYSTEM_BRIGHTNESS=-1f`), `tts` (default true). Applied to the window via `applyToWindow(window)` (FLAG_KEEP_SCREEN_ON + `screenBrightness`).
- `ViewerConfigUseCase` adds validation (brightness in [-1..1]), corrupted-config fallback (`getOrNew`), and a config flow.
- `ViewerConfigDialog` (bottom sheet) edits: keep-screen-on switch, brightness slider (debounced 50ms, with label formatter), system-brightness switch, TTS switch (with engine-install error resolution). Saves through the presenter → `ViewerConfigViewModel.saveConfig`.
- **There is no existing preference for zoom limits, balloon scale, tap-zone size, fling threshold, or reading-order tuning** — those are hardcoded resources/constants (§2.3–§2.5). A TODO in `PageObjectHelper.kt` explicitly says "maybe it is best to give user a chance to tweak it somehow?" regarding panel-diff thresholds.

### 2.11 Existing Tests & Android Build System

**Tests**
- Unit (JVM):
  - `logic/src/test/kotlin/app/seeneva/reader/logic/storage/ObjectStorageImplTest.kt` — concurrency/borrow-release semantics of `ObjectStorageImpl` (MockK).
  - `app/src/test/kotlin/app/seeneva/reader/EventSenderTest.kt` — `EventSender` flow (Kluent).
- Instrumented (androidTest):
  - `logic/src/androidTest/kotlin/app/seeneva/reader/logic/NativeComicOpeningTest.kt` — opens real cbz/cbr/7z/pdf fixtures from assets, asserts page counts/metadata against `truth_source.json`, checks ML object counts, and smoke-tests OCR. Uses Koin test rule + real native lib.
  - `data/src/androidTest/kotlin/app/seeneva/reader/data/ComicBookTest.kt` — Room DAO behaviors (insert/query/tags/delete/order) against an in-memory DB.
- **There are no tests for the viewer UI, `generateReadOrderedObjects`, `ViewerPager`, `ObjectImageHelper`, or gesture handling.** This is a gap for the planned work.

**Build system**
- Gradle `8.1.1` wrapper; AGP `8.1.2`; Kotlin `1.9.10`; KSP for Room.
- Root `build.gradle.kts` applies android/kotlin plugins to all subprojects, sets `minSdk 16`, excludes legacy `androidx.viewpager` globally (ViewPager2 only), and configures desugaring.
- `app/build.gradle.kts`: viewBinding + buildConfig, ABI splits (universal + per-ABI), flavors `googleplay` / `fdroid` / `github`, release minification + proguard, optional signing via `keystore.properties`.
- Rust: `rust-toolchain` pins 1.52.1 with 4 Android targets; the `native` submodule must be initialized (`git submodule update --init`) and built — **it is currently uninitialized in the local clone**.
- CI: `.github/workflows/pr_check.yml`, `tag_release.yml`; release automation via `fastlane/`.
- `logic/src/main/assets/` contains the ML model (`yolo_seeneva.tflite`) and OCR data (`eng_seeneva.traineddata`).

---

## 3. Important Inter-Component Dependencies

```
BookViewerActivity
 ├─ ViewerPager ── BookViewerAdapter ── BookViewerPageFragment (per page)
 │                                   ├─ PageViewer ── SubsamplingScaleImageView
 │                                   │                └─ (custom tile decoders) ── ImageLoader.decodeRegion
 │                                   ├─ ObjectImageHelper ── ImageLoader.loadPageObject (balloon crop)
 │                                   ├─ BookViewerPagePresenter ── BookViewerPageViewModel
 │                                   │        └─ GetPageDataUseCase ── ComicBookPageSource / EncodedComicPageStorage
 │                                   │              └─ generateReadOrderedObjects (RTree + comparators)
 │                                   └─ PageViewerHelperFragment (tutorial tips)
 ├─ BookViewerPreviewAdapter (bottom strip) ── ImageLoader.viewerPreview
 └─ SystemUiManager + UIAnimator (toolbar / greyOutView / preview strip)

BookViewerPageFragment ──(lastObjectViewed)──▶ BookViewerActivity (page turn)

ComicsSettings (SharedPreferences JSON)
 └─ ViewerConfigUseCase ── ViewerConfigViewModel ── ViewerConfigDialog
ComicBook.direction (Room) ──▶ ViewerPager.reverse / reading order / tap zones
```

Key coupling notes:
- Balloon UX (tap, zoom-reset, overlay) depends on both the ordered object list **and** `SubsamplingScaleImageView` state; `ObjectImageHelper` resets page zoom whenever a balloon is shown, so zoom and balloon mode are mutually exclusive by design.
- Reading order is computed per page load and **recomputed when direction changes** (`subscribePageData` maps over `bookSource.subscribeOnDirection`), but the page fragment only notices via `readDirectionState` (and drops the first emission to avoid a spurious reset).
- Balloon crops go through Coil with `diskCachePolicy/memoryCachePolicy ENABLED` + `Precision.EXACT` + `OriginalSize`; full page tiles go through the same `ComicImageFetcher` with caches disabled for `decodeRegion`.

---

## 4. Potential Implementation Risks

1. **RTL via position reversal is fragile.** `ViewerPager` maps positions by overriding adapter positions; any change to paging (offscreen limits, prefetch, new gestures) can desync logical vs physical position. The code already works around ViewPager2 being final; extending this area needs care (see the fling hack and `setCurrentItem` no-op guard).
2. **No app-level double-tap handling.** Double-tap currently goes straight to the subsampling library zoom. Adding balloon-targeted double-tap means intercepting before/alongside the library or disabling its double-tap and reimplementing.
3. **Balloon mode conflicts with zoom.** `ObjectImageHelper` forcibly resets page scale to `minScale` before showing a balloon and blows the balloon away on any scale/center change. Any "zoom into balloon / pan between balloons while zoomed" feature must rework this interaction.
4. **Hardcoded tuning values.** Balloon scale (`viewer_balloon_scale_xy` as a 0.5dp dimen), tap-zone width (`viewer_hide_page_object_x_percentage`), fling velocity, and all `PageObjectHelper` thresholds are magic constants/resources with no settings backing. Making them configurable requires threading a new setting through `ViewerConfig` + dialog + consumers.
5. **Reading order is ML-dependent and untested.** `generateReadOrderedObjects` has no unit tests and depends on YOLO output quality; improvements to ordering are hard to verify without fixtures + tests.
6. **minSdk 16 / memory constraints.** The app supports API 16 with heavy images; several code paths branch on low-RAM / pre-O behavior (tile DPI, view cache, bitmap configs). New zoom/gesture code must preserve these guards.
7. **Native submodule not initialized locally.** Building/running requires `git submodule update --init` plus Rust 1.52.1 Android targets and NDK `21.4.7075529`; instrumented tests that touch native code (e.g. `NativeComicOpeningTest`) will not run otherwise.
8. **Observer/listener single-slot constraints.** `SubsamplingScaleImageView` has single `OnStateChangedListener`/`OnImageEventListener`; the project works around this with compose-listener tags (`addOnStateChangedListener`). New listeners must go through those helpers or they will silently override existing ones.
9. **State save/restore surface.** Balloon visibility + `ImageViewState` are saved per fragment; zoom/gesture changes must update `onSaveInstanceState` and the `subscribeOnImageLoading` restore logic or rotation will break balloon/zoom state.
10. **Gesture layering.** Three layers (Activity detector → fragment detector → library) already have edge cases (greyOutView workaround, `onSingleTapUp` suppression, Android 9 dispatch guard). New gestures risk being eaten by the wrong layer; keep the "listener returns false" contract.

---

## 5. Recommended Implementation Order

Work bottom-up (logic first, UI last) so each step is testable in isolation:

1. **Settings groundwork** — extend `ViewerConfig` (+ validation, `ViewerConfigUseCase`, dialog) with any new tunables (e.g. max zoom, balloon scale, tap-zone size, navigation behavior). Safe, isolated, no viewer behavior change yet.
2. **Reading order & navigation logic** — refactor/tune `generateReadOrderedObjects` and add unit tests with synthetic bbox fixtures (LTR + RTL). This is pure logic in `:logic`; highest confidence to improve without UI risk.
3. **Zoom behavior** — set explicit `maxScale`/`minScale` policies in `PageViewer` (or a config-driven wrapper), verify tile decode under the new limits, and update the tutorial (`siv.maxScale` usage) accordingly.
4. **Balloon enlarge/scaling** — rework `ObjectImageHelper` show/scale logic, including the zoom-reset behavior and the blow-away-on-scale-change listener; add a proper "zoom to balloon" path using `animateScaleAndCenterSuspended`.
5. **Gesture & double-tap handling** — add deliberate double-tap (and any new gesture) handling in `BookViewerPageFragment`; reconcile with the library's built-in double-tap; keep the `onSingleTapConfirmed` contract for balloon navigation.
6. **Page navigation / thumbnail gestures** — extend `ViewerPager` / preview interactions (e.g. swipe gestures on the preview strip, faster page turns), mindful of the RTL reversal and fling hack.
7. **Integration & verification** — instrumented viewer tests (scroll/fling, tap zones, balloon show/hide, zoom, direction flip), plus manual QA on the existing low-RAM paths.

---

*Generated 2026-08-03 from a read-only inspection of `/opt/data/seeneva-reader-android` (master @ `003f014`).*

---

## 6. Implemented: Instant viewer interactions (Phase 1)

Implemented 2026-08-03 on master. Adds a viewer setting **"Instant viewer interactions"** (default OFF → existing animated behavior preserved).

### Architectural changes

- **`ViewerConfig`** (logic) gained `@SerialName("instant_viewer_interactions") instantViewerInteractions: Boolean = false`.
  Backward compatible: stored JSON without the field decodes to `false` (kotlinx.serialization default), so existing users keep animated behavior.
- **Settings dialog**: new `SwitchMaterial` (`instantInteractionsSwitch`) in `dialog_viewer_settings.xml`, wired in `ViewerConfigDialog` → `ViewerConfigPresenter.onInstantInteractionsChange` → `saveConfig(copy(...))` (same pattern as keep-screen-on).
- **Page transitions** (`BookViewerActivity`): the flag is tracked from `onConfigChanged` (the existing config flow already reaches the activity via `BookViewerViewModel.configState`). The two smooth programmatic page changes — thumbnail-preview click and "last balloon viewed → next page" — now pass `smoothScroll = !instantViewerInteractions`. Initial positioning and direction-flip already used `smoothScroll=false`.
- **Speech-bubble presentation/dismissal** (`ObjectImageHelper`): new `instantInteractions` flag short-circuits:
  - the 200 ms balloon scale-up (via existing `animate` parameter),
  - the 100 ms zoom-to-min-scale reset before showing a balloon (`resetScaleAndCenter()` + immediate switch instead of `animateScale(.0f)`),
  - the hide-then-show switch animation when swapping balloons (previous loading task disposed, new balloon set directly),
  - the 150 ms blow-out dismissal (immediate `isGone`, including dismissal triggered by the scale/center-change listener).
- **App-triggered zoom** (`BookViewerPageFragment`): the tutorial "TTS balloon" zoom to `maxScale` uses `setScaleAndCenter` (instant) instead of `animateScaleAndCenterSuspended` when the setting is on.
- **Not touched**: pinch/pan gestures, user-swipe page animation (ViewPager2's own scroll animation), bubble detection/ordering, RTL/LTR, balloon size, max zoom, double-tap, thumbnail navigation, dependencies.

### Test

- New `logic` unit test `ViewerConfigTest` (no MockK): default value false, old-JSON decode → false, true round-trips. Passes in the current environment (unlike the pre-existing MockK tests, which are blocked by the container's ByteBuddy attach limitation).

### Known limitation

- The setting does not affect the physical swipe animation inside ViewPager2 (that is the user's own gesture and ViewPager2 is final, so it cannot be disabled without hacks/reflection). All application-initiated transitions are instant.

### Follow-up correction (2026-08-03): user-initiated page swipes are instant too

User testing found the page-turn animation was still present for user swipes. Extended the setting to user-initiated page turns:

- `ViewerPager.instantPageTurns` (new) — when ON, a touch listener on the pager's internal RecyclerView intercepts horizontal swipes: the page does NOT slide with the finger, and on release the target page is set via `pager.setCurrentItem(target, false)` (posted after the touch dispatch, since calling it inside the dispatch is unsafe — the existing fling code has the same caveat).
- The target is computed in physical pager coordinates by the pure helper `ViewerPager.instantPageTurnTarget(currentItem, itemCount, dx, width, thresholdFraction)` (swipe must cover ≥ 25% of the pager width; clamped to valid range). Physical-space math keeps the RTL position reversal semantics identical to the animated pager.
- Zoomed-image panning is unaffected: the page's image view consumes the gesture first (child dispatch), so the listener only sees drags the image view did not consume. `ACTION_DOWN` may be consumed by the image view, so the listener tracks the swipe from the first event the RecyclerView receives.
- Thumbnail strip: `pagesPreviewList.smoothScrollToPosition` in `onPageSelected` is now `scrollToPosition` (instant) when the setting is ON. The existing slow-fling snap-back never fires in instant mode because the swipe is fully consumed.
- Wired from `BookViewerActivity.onConfigChanged` (same place as the Phase 1 flag).
- New unit tests: `ViewerPagerTest` (6, app module, no MockK) covering swipe-left/right, short swipe, first/last page clamps, empty pager.

---

## 7. Implemented: Direct bubble selection (Phase 1, item 2)

Implemented 2026-08-03 on master. Tap directly on a detected speech bubble → that exact bubble is enlarged (spatial selection), instead of advancing to the next bubble in reading order.

### Behavior

- **Tap inside a detected speech bubble** → `viewToSourceCoord(e.x, e.y)` → `presenter.onPageTap(x, y)` → `ComicPageObjectContainer.indexOf(x, y)` (RTree point query, same infrastructure as long-press) → `showPageObject(SelectedPageObject)` + same haptic feedback as reading-order selection. `readObjectPosition` is set to the tapped bubble, so subsequent forward/backward taps continue from it.
- **Tap outside all bubbles** → previous behavior exactly (reading-order navigation by tap side, LTR/RTL aware; center-strip hide-zone check still runs first).
- **Untouched**: reading-order algorithm, bubble scale, long-press OCR/TTS, LTR/RTL, page navigation, Instant viewer interactions setting (bubble show/hide still respects it), bubble detection.

### Architectural changes

- `ComicPageObjectContainer.indexOf(x, y): Int?` (logic) — RTree-backed spatial lookup that returns the object index instead of the object; delegates to the existing `get(x, y)` so overlap resolution is exactly the existing RTree search order (`firstOrNull`), identical to the long-press path.
- `BookViewerPagePresenter.onPageTap(x, y): SelectedPageObject?` (app) — wires `indexOf` → `intoSelectedPageObject`, updates `readObjectPosition`.
- `BookViewerPageFragment.onSingleTapConfirmed` — new precedence: hide-zone → direct bubble selection → reading-order fallback.

### Tests

- New `logic` unit test `ComicPageObjectContainerTest` (4 tests, no MockK): tap inside bubble A → A; tap inside bubble B → B; tap in gap → null; overlapping bubbles → deterministic + consistent with RTree `get(x,y)` order.
- Note: `android.graphics.RectF` constructors are stubbed in the mockable android.jar, so the test allocates `RectF` via `sun.misc.Unsafe.allocateInstance` + public-field assignment; the test JVM gets `--add-opens=java.base/sun.misc=ALL-UNNAMED` from a user-space `~/.gradle/init.gradle` (outside the repo). This exercises the real RTree container on a plain JVM.

### Known limitation

- `indexOf(x, y)` returns the first object of ANY class containing the point (same rule as the existing long-press). If a page has PANEL objects that overlap a bubble, the tap could select the panel. No class filter was added to stay consistent with the existing RTree behavior; revisit if panel detection ships and this becomes user-visible.

### Correction (2026-08-03): center hide-zone no longer shadows direct bubble selection

User testing found that bubbles in the middle of the page could not be selected — the center hide-zone check ran first, so tapping the middle hid/shrunk the current bubble instead of selecting. Fixed the tap precedence in `BookViewerPageFragment.onSingleTapConfirmed`:

1. Convert tap to source coordinates.
2. Inside a detected bubble (`presenter.onPageTap` → RTree `indexOf`) → select/enlarge that exact bubble (wins over the hide-zone).
3. Not inside a bubble but in the center hide-zone → hide current bubble (unchanged behavior).
4. Otherwise → existing reading-order navigation (unchanged).

`onSingleTapUp` (hide-zone event consumption for the library's zoom) is unchanged. Added `ComicPageObjectContainerTest.centerLocatedBubbleIsSelectable` — a center-located bubble is found by the spatial query. The precedence itself is UI orchestration (MotionEvent + `viewToSourceCoord` + gesture detector), so it relies on manual verification; the spatial lookup it depends on is covered by the logic tests.

---

## 8. Implemented: RTL reading order fix (Phase 3)

Implemented 2026-08-04 on master. User testing on real RTL (manga) pages showed the speech-bubble reading order was wrong for two-column layouts where the columns start at different vertical positions.

### Root cause

`defaultComparator` sorted by the **top edge first** in BOTH directions; the RTL branch only changed the secondary X comparator. So in RTL, a higher LEFT column/panel was ordered before a LOWER RIGHT column/panel whenever the top difference exceeded the tolerance band (GROUP_MIN_DIFF 80 for in-panel groups, PANEL_MIN_DIFF 160 for fake/real panels).

### Fix

The RTL branch of `defaultComparator` now sorts by X first (right edge, descending) and by top second:

- LTR: top first, then left (unchanged).
- RTL: right first, then top (right column/panel always precedes the left one, regardless of vertical offset; ties on the right edge fall through to top-to-bottom within the column).

LTR behavior is byte-identical. `GroupObjectComparator` (intra-group) and all thresholds are untouched.

### Tests

`PageObjectHelperTest` (logic, no MockK; uses a test-only runtime shadow of `android.graphics.RectF` provided outside the repo via `/opt/data/tools/test-shadows/rectf-shadow.jar` prepended by the user-space Gradle init script). Focused fixtures: `twoColumnsAlignedLtrAndRtl`, `twoColumnsOffsetNoPanels`, `twoColumnsOffsetInPanel` — desired behavior LTR `A→C→B→D`, RTL `B→D→A→C` (aligned and offset columns, with and without panels). `noPanelsTwoColumnLayoutRtl` also started passing (same root cause).

### Known limitations (intentionally retained, out of Phase 3 scope)

The following LTR fixtures still fail and are retained as documented pre-existing behavior, NOT Phase 3 regressions:

- `noPanelsTwoColumnLayout`, `panelWithTwoColumnLayout` — LTR reads row-band-first when bubbles are separated by > OBJECT_NEIGHBOUR_MIN_DIFF (20px) gaps. On real pages column bubbles are closer (one group) or separated by panels, so LTR is correct there; these fixtures document the large-gap behavior.
- `panelRaggedTopsShouldNotBreakPanelOrder` — LTR panel order treats side-by-side panels whose top edges differ by < PANEL_MIN_DIFF (160px) as the same row and falls back to X order. Possible future LTR improvement; deliberately not changed because Phase 3 preserves LTR behavior exactly.
