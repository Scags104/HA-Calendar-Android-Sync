package io.hacalsync.auth

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.hacalsync.MainActivity

/**
 * The account only exists so Android has something to attach calendars to.
 * "Add account" from system settings just opens the setup screen.
 */
class Authenticator(private val context: Context) : AbstractAccountAuthenticator(context) {

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle = Bundle().apply {
        putParcelable(AccountManager.KEY_INTENT, Intent(context, MainActivity::class.java))
    }

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?,
    ): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?,
    ): Bundle? = null

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?,
    ): Bundle = Bundle().apply {
        putParcelable(AccountManager.KEY_INTENT, Intent(context, MainActivity::class.java))
    }

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?,
    ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}
