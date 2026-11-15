package com.joebrowser.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.joebrowser.app.R
import com.joebrowser.app.auth.LocalAccount
import com.joebrowser.app.databinding.ActivityAccountBinding

/**
 * Local account gate: register (first run) or sign in (cold start).
 *
 * There is no network call and no email verification anywhere here — the account hash lives in
 * private, unbacked-up storage on this device (see [LocalAccount]). On success the activity
 * forwards the original deep-link extras to [MainActivity] and finishes itself.
 */
class AccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountBinding

    /** true = registration form, false = sign-in form. */
    private var registerMode: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerMode = !LocalAccount.exists(this)
        renderMode()

        binding.toggleMode.setOnClickListener {
            // Only an existing account can switch to sign-in; a brand-new device has nothing to
            // sign into, so the toggle is hidden there entirely.
            if (!LocalAccount.exists(this)) return@setOnClickListener
            registerMode = !registerMode
            clearError()
            renderMode()
        }

        binding.forgotPassword.setOnClickListener { confirmReset() }
        binding.submitButton.setOnClickListener { submit() }

        listOf(binding.emailInput, binding.passwordInput, binding.confirmInput).forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = clearError()
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        binding.confirmInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { binding.confirmInput.requestFocus(); true } else false
        }
        binding.emailInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { binding.passwordInput.requestFocus(); true } else false
        }
    }

    private fun renderMode() {
        val hasAccount = LocalAccount.exists(this)
        binding.accountTitle.setText(if (registerMode) R.string.account_title_create else R.string.account_title_signin)
        binding.submitButton.setText(if (registerMode) R.string.account_create else R.string.account_signin)
        binding.toggleMode.setText(if (registerMode) R.string.account_to_signin else R.string.account_to_create)
        binding.toggleMode.visibility = if (hasAccount) View.VISIBLE else View.GONE
        binding.forgotPassword.visibility = if (registerMode) View.GONE else View.VISIBLE
        binding.confirmLayout.visibility = if (registerMode) View.VISIBLE else View.GONE
        binding.confirmInput.setText("")
    }

    private fun clearError() {
        binding.accountError.visibility = View.GONE
    }

    private fun showError(messageRes: Int) {
        binding.accountError.setText(messageRes)
        binding.accountError.visibility = View.VISIBLE
    }

    private fun submit() {
        val email = binding.emailInput.text?.toString().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()

        val outcome = if (registerMode) {
            val confirm = binding.confirmInput.text?.toString().orEmpty()
            when {
                email.isBlank() || !email.contains('@') -> { showError(R.string.account_error_email); return }
                password.length < 8 -> { showError(R.string.account_error_password_short); return }
                password != confirm -> { showError(R.string.account_error_mismatch); return }
                else -> LocalAccount.register(this, email, password)
            }
        } else {
            if (email.isBlank()) { showError(R.string.account_error_email); return }
            LocalAccount.signIn(this, email, password)
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
}
