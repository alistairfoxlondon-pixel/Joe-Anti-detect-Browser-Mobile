package com.joebrowser.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.joebrowser.app.R
import com.joebrowser.app.databinding.ActivityLicensesBinding

/**
 * Open-source acknowledgements. Joe Browser itself is source-available; this screen
 * covers the third-party libraries it builds on.
 */
class LicensesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicensesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_JoeBrowser)
        super.onCreate(savedInstanceState)
        binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnLicensesBack.setOnClickListener { finish() }
        binding.licensesText.text = LICENSES
    }

    companion object {
        private val APACHE2 = "Licensed under the Apache License, Version 2.0. " +
            "You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. " +
            "Unless required by applicable law or agreed to in writing, software distributed under the " +
            "License is distributed on an “AS IS” BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied."
        private val MIT = "Licensed under the MIT License. Permission is hereby granted, free of charge, to any person obtaining a copy " +
            "of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including " +
            "without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, " +
            "and to permit persons to whom the Software is furnished to do so, subject to the following conditions: the above copyright " +
            "notice and this permission notice shall be included in all copies or substantial portions of the Software. " +
            "THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED."

        private val LICENSES = buildString {
            fun entry(name: String, license: String, copyright: String, text: String) {
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine(name)
                appendLine(copyright)
                appendLine("License: $license")
                appendLine()
                appendLine(text)
                appendLine()
            }
            entry("AndroidX (Core, AppCompat, Activity, Fragment, WebKit, Browser)", "Apache-2.0", "© The Android Open Source Project", APACHE2)
            entry("AndroidX WebKit", "Apache-2.0", "© The Android Open Source Project", APACHE2)
            entry("Material Components for Android", "Apache-2.0", "© Google LLC", APACHE2)
            entry("Kotlin standard library & coroutines", "Apache-2.0", "© JetBrains s.r.o.", APACHE2)
            entry("Inter font family", "SIL Open Font License 1.1", "© The Inter Project Authors",
                "Licensed under the SIL Open Font License, Version 1.1. The fonts are free to use, study, modify and redistribute " +
                "under the terms of the OFL. The OFL is available at https://openfontlicense.org.")
            entry("Android Runtime & SDK libraries (org.json, SQLite, java.net)", "Apache-2.0", "© The Android Open Source Project", APACHE2)
            entry("Gradle & Android Gradle Plugin", "Apache-2.0", "© The Android Open Source Project / Gradle Inc.", APACHE2)
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            append("Joe Browser itself: Copyright © Joe Browser contributors.\n")
            append("All network checks (IP echo, geo lookup, proxy checks) contact third-party services and are subject to their privacy policies.")
        }
    }
}
