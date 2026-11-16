package com.joebrowser.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.R
import com.joebrowser.app.auth.LocalAccount
import com.joebrowser.app.databinding.ActivityAccountBinding

/**
 * Account gate: create an account on first run, sign in afterwards.
 *
 * There is no server, no verification mail and no network call in this flow — the account is a
 * PBKDF2 hash in private, unbacked-up storage (see [LocalAccount]). The screen therefore only
 * has to be fast and obvious:
 *
 *  - the mode is a segmented control at the top, so "Sign in" is discoverable immediately,
 *  - the form is two fields (three when creating) with inline errors, never a dialog,
 *  - the primary button is disabled-looking only through validation copy, not through a
 *    greyed-out button that leaves the user guessing.
 */
class AccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountBinding

    /** true = registration form, false = sign-in form. */
    private var registerMode: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // An existing account always opens on sign-in; a fresh device can only create one.
        registerMode = !LocalAccount.exists(this)
        renderMode()

        binding.modeSignIn.setOnClickListener { switchMode(register = false) }
        binding.modeCreate.setOnClickListener { switchMode(register = true) }

        binding.forgotPassword.setOnClickListener { showPasswordOptions() }
        binding.submitButton.setOnClickListener { submit() }

        listOf(binding.emailLayout, binding.passwordLayout, binding.confirmLayout).forEach { fieldLayout ->
            val field = when (fieldLayout) {
                binding.emailLayout -> binding.emailInput
                binding.passwordLayout -> binding.passwordInput
                else -> binding.confirmInput
            }
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Clear both the banner and the field error: leaving a red outline on a
                    // field the user is already fixing reads as a second, stale failure.
                    fieldLayout.error = null
                    clearError()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        binding.emailInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { binding.passwordInput.requestFocus(); true } else false
        }
        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                if (registerMode) binding.confirmInput.requestFocus() else submit()
                true
            } else false
        }
        binding.confirmInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
    }

    private fun switchMode(register: Boolean) {
        if (registerMode == register) return
        if (!register && !LocalAccount.exists(this)) {
            showError(R.string.account_error_none)
            return
        }
        registerMode = register
        clearError()
        renderMode()
    }

    private fun renderMode() {
        val context = this
        val selectedBg = R.drawable.bg_segment_thumb
        val selectedText = ContextCompat.getColor(context, R.color.text)
        val idleText = ContextCompat.getColor(context, R.color.text_dim)

        binding.modeSignIn.setBackgroundResource(if (registerMode) 0 else selectedBg)
        binding.modeCreate.setBackgroundResource(if (registerMode) selectedBg else 0)
        binding.modeSignIn.setTextColor(if (registerMode) idleText else selectedText)
        binding.modeCreate.setTextColor(if (registerMode) selectedText else idleText)
        binding.modeSignIn.setTextAppearance(if (registerMode) R.style.Text_Jb_BodyMedium else R.style.Text_Jb_CardTitle)
        binding.modeCreate.setTextAppearance(if (registerMode) R.style.Text_Jb_CardTitle else R.style.Text_Jb_BodyMedium)

        binding.accountTitle.setText(
            if (registerMode) R.string.account_title_create else R.string.account_title_signin
        )
        binding.submitButton.setText(if (registerMode) R.string.account_create else R.string.account_signin)
        binding.forgotPassword.visibility = if (registerMode) View.GONE else View.VISIBLE
        binding.confirmLayout.visibility = if (registerMode) View.VISIBLE else View.GONE
        binding.confirmInput.setText("")
        binding.passwordInput.setImeOptions(
            if (registerMode) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
        )
    }

    private fun clearError() {
        binding.accountError.visibility = View.GONE
    }

    private fun showError(messageRes: Int) {
        binding.accountError.setText(messageRes)
        binding.accountError.visibility = View.VISIBLE
    }

    /** Banner + field-level error together, so it is obvious which field needs attention. */
    private fun showFieldError(messageRes: Int, layout: com.google.android.material.textfield.TextInputLayout) {
        showError(messageRes)
        layout.error = getString(messageRes)
        layout.requestFocus()
    }

    private fun submit() {
        val email = binding.emailInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()

        val outcome = if (registerMode) {
            val confirm = binding.confirmInput.text?.toString().orEmpty()
            when {
                !isEmail(email) -> { showFieldError(R.string.account_error_email, binding.emailLayout); return }
                password.length < MIN_PASSWORD -> {
                    showFieldError(R.string.account_error_password_short, binding.passwordLayout); return
                }
                password != confirm -> {
                    showFieldError(R.string.account_error_mismatch, binding.confirmLayout); return
                }
                else -> LocalAccount.register(this, email, password)
            }
        } else {
            when {
                !isEmail(email) -> { showFieldError(R.string.account_error_email, binding.emailLayout); return }
                password.isEmpty() -> {
                    showFieldError(R.string.account_error_password_empty, binding.passwordLayout); return
                }
                else -> LocalAccount.signIn(this, email, password)
            }
        }

        outcome.onSuccess {
            Toast.makeText(this, R.string.account_signed_in, Toast.LENGTH_SHORT).show()
            val forward = Intent(this, MainActivity::class.java).apply {
                intent?.extras?.let { putExtras(it) }
                intent?.data?.let { data = it }
            }
            startActivity(forward)
            finish()
        }.onFailure {
            showError(R.string.account_error_credentials)
        }
    }

    private fun isEmail(value: String): Boolean =
        value.length in 5..254 && EMAIL.matches(value)

    /**
     * Forgot-password sheet: change the password (keeping the account and every profile) or
     * reset the account outright. PBKDF2 is one-way, so a forgotten password genuinely cannot be
     * recovered — the honest choice is between changing it while unlocked and starting over.
     */
    private fun showPasswordOptions() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_forgot)
            .setItems(
                arrayOf(
                    getString(R.string.account_change_password),
                    getString(R.string.account_reset)
                )
            ) { _, which ->
                if (which == 0) showChangePassword() else confirmReset()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showChangePassword() {
        val sheet = com.joebrowser.app.databinding.SheetChangePasswordBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(sheet.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val current = sheet.currentPasswordInput.text?.toString().orEmpty()
                val next = sheet.newPasswordInput.text?.toString().orEmpty()
                val again = sheet.confirmPasswordInput.text?.toString().orEmpty()
                when {
                    current.isEmpty() -> {
                        sheet.currentLayout.error = getString(R.string.account_error_password_empty); return@setOnClickListener
                    }
                    next.length < MIN_PASSWORD -> {
                        sheet.newLayout.error = getString(R.string.account_error_password_short); return@setOnClickListener
                    }
                    next != again -> {
                        sheet.confirmLayout.error = getString(R.string.account_error_mismatch); return@setOnClickListener
                    }
                }
                LocalAccount.changePassword(this, current, next)
                    .onSuccess {
                        Toast.makeText(this, R.string.account_password_changed, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    .onFailure { sheet.currentLayout.error = getString(R.string.account_error_credentials) }
            }
        }
        dialog.show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_reset_title)
            .setMessage(R.string.account_reset_body)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.account_reset) { _, _ ->
                LocalAccount.reset(this)
                registerMode = true
                binding.emailInput.setText("")
                binding.passwordInput.setText("")
                binding.confirmInput.setText("")
                renderMode()
            }
            .show()
    }

    companion object {
        private const val MIN_PASSWORD = 6
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")
    }
}
