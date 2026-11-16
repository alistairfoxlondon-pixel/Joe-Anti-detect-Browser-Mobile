package com.joebrowser.app.browser

import android.view.View
import com.joebrowser.app.R
import com.joebrowser.app.data.BrowserEngine

/**
 * Per-engine layout identity for the browser shell.
 *
 * A profile must not be "the Joe Browser shell with a logo glued on": the browsing experience has
 * to match the identity being presented. This models the five real products the app can present —
 * Chrome, Chromium, Brave, Edge and Firefox — using each one's actual layout conventions, so a
 * Chrome identity gets Chrome's top tab strip, share affordance and omnibox-only navigation, while
 * a Firefox identity gets its tab counter, its bottom toolbar and its segmented URL bar.
 *
 * Only the chrome differs. The WebView, the fingerprint layer and the proxy stack are shared, so
 * behaviour is identical across engines while presentation follows the profile. Colour is
 * deliberately *not* part of this model: the app's single green identity owns every surface, and
 * the engine is expressed through geometry, affordances and the engine wordmark instead of a
 * second brand palette fighting the first.
 *
 * The class holds no activity logic and touches no WebView, so it stays testable without a device.
 */
enum class EngineChrome(
    val engine: BrowserEngine,
    val label: String,
    /** How the tab affordance is presented in the toolbar. */
    val tabStyle: TabStyle,
    /** Shape vocabulary of the URL bar. */
    val urlStyle: UrlStyle,
    /** Where the navigation cluster lives. */
    val navStyle: NavStyle,
    /** Chrome/Edge keep the omnibox as the whole toolbar; Firefox keeps a nav row. */
    val showsNavRow: Boolean,
    /** Firefox and Edge both expose a home affordance; Chrome, Chromium and Brave do not. */
    val showsHome: Boolean,
    /** Chrome and Edge put share in the toolbar. */
    val showsShare: Boolean,
    /** Brave keeps its shield permanently inside the URL bar. */
    val shieldInUrlBar: Boolean,
    /** Edge and Firefox show a visible top tab strip; the others use a counter. */
    val showsTabStrip: Boolean,
    val menuProfile: MenuProfile
) {
    /**
     * Chrome: blue-on-white top strip, omnibox-only navigation, share to the right of the
     * omnibox, three-dot overflow, no home button.
     */
    CHROME(
        BrowserEngine.CHROME, "Chrome",
        TabStyle.COUNT_RIGHT, UrlStyle.ROUND_FULL, NavStyle.OMNIBOX_ONLY,
        showsNavRow = false, showsHome = false, showsShare = true, shieldInUrlBar = false,
        showsTabStrip = true, menuProfile = MenuProfile.CHROME
    ),

    /**
     * Chromium: the un-branded build. Same bones as Chrome but no share button, a mono counter,
     * and the conventional back/forward row, which is how Chromium looks before a vendor skin.
     */
    CHROMIUM(
        BrowserEngine.CHROMIUM, "Chromium",
        TabStyle.COMPACT, UrlStyle.ROUND_SMALL, NavStyle.STANDARD,
        showsNavRow = true, showsHome = false, showsShare = false, shieldInUrlBar = false,
        showsTabStrip = false, menuProfile = MenuProfile.CHROMIUM
    ),

    /**
     * Brave: the shield is the product, so it sits permanently inside the URL bar; the omnibox is
     * a full capsule and the toolbar keeps the standard back/forward/reload row.
     */
    BRAVE(
        BrowserEngine.BRAVE, "Brave",
        TabStyle.COUNT_RIGHT, UrlStyle.ROUND_FULL, NavStyle.SHIELD_INLINE,
        showsNavRow = true, showsHome = false, showsShare = false, shieldInUrlBar = true,
        showsTabStrip = true, menuProfile = MenuProfile.BRAVE
    ),

    /**
     * Edge: top tab strip, a squared-off omnibox, home and share both present, and the
     * collections/read-aloud entries where Edge puts them.
     */
    EDGE(
        BrowserEngine.EDGE, "Edge",
        TabStyle.STRIP, UrlStyle.ROUND_SMALL, NavStyle.OMNIBOX_ONLY,
        showsNavRow = false, showsHome = true, showsShare = true, shieldInUrlBar = false,
        showsTabStrip = true, menuProfile = MenuProfile.EDGE
    ),

    /**
     * Firefox: segmented URL bar with the shield to the left, tabs as a counter at the trailing
     * edge, and the conventional nav row at the bottom of the toolbar.
     */
    FIREFOX(
        BrowserEngine.FIREFOX, "Firefox",
        TabStyle.COUNT_RIGHT, UrlStyle.ROUND_SMALL, NavStyle.STANDARD,
        showsNavRow = true, showsHome = true, showsShare = true, shieldInUrlBar = true,
        showsTabStrip = false, menuProfile = MenuProfile.FIREFOX
    );

    enum class TabStyle {
        /** A visible strip of tabs at the top of the page. */
        STRIP,
        /** A single nested pill showing the active tab. */
        COMPACT,
        /** Just a counter in the toolbar. */
        COUNT_RIGHT
    }

    enum class UrlStyle {
        /** Fully rounded capsule. */
        ROUND_FULL,
        /** Slightly rounded field. */
        ROUND_SMALL
    }

    enum class NavStyle {
        /** Everything in the omnibox; no separate bottom row. */
        OMNIBOX_ONLY,
        /** Conventional back / forward / reload row under the page. */
        STANDARD,
        /** Standard row plus Brave's permanent shield inside the URL bar. */
        SHIELD_INLINE
    }

    enum class MenuProfile { CHROME, CHROMIUM, BRAVE, EDGE, FIREFOX }

    /** The identity-strip caption, e.g. "Chrome · Windows 11". */
    fun caption(os: String): String = "$label · $os"

    /** Applied to the URL bar container; the two shapes live in the drawable system. */
    val urlBarBackgroundRes: Int
        get() = when (urlStyle) {
            UrlStyle.ROUND_FULL -> R.drawable.bg_url_pill
            UrlStyle.ROUND_SMALL -> R.drawable.bg_url_field
        }

    companion object {
        fun of(engine: BrowserEngine): EngineChrome =
            entries.firstOrNull { it.engine == engine } ?: CHROME

        fun ofId(id: String?): EngineChrome = of(BrowserEngine.of(id))
    }

    /**
     * Apply this engine's layout to already-inflated chrome.
     *
     * Takes the individual views rather than a binding so the exact same call works for the
     * activity's own layout and for any future shell (tabs sheet, split view, tests).
     */
    fun apply(
        urlBar: View,
        urlShield: View,
        navRow: View,
        homeButton: View,
        shareButton: View,
        tabStrip: View,
        tabCounter: View,
        tabPill: View
    ) {
        urlBar.setBackgroundResource(urlBarBackgroundRes)
        urlShield.visibility = if (shieldInUrlBar) View.VISIBLE else View.GONE
        navRow.visibility = if (showsNavRow) View.VISIBLE else View.GONE
        homeButton.visibility = if (showsHome) View.VISIBLE else View.GONE
        shareButton.visibility = if (showsShare) View.VISIBLE else View.GONE
        tabStrip.visibility = if (showsTabStrip) View.VISIBLE else View.GONE
        tabCounter.visibility = if (tabStyle == TabStyle.COUNT_RIGHT) View.VISIBLE else View.INVISIBLE
        tabPill.visibility = if (tabStyle == TabStyle.COMPACT) View.VISIBLE else View.GONE
    }
}
