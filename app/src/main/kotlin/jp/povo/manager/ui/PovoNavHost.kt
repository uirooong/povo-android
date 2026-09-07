package jp.povo.manager.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import jp.povo.manager.BuildConfig
import jp.povo.manager.ui.accounts.AccountsScreen
import jp.povo.manager.devtools.ProtocolSpikeScreen
import jp.povo.manager.ui.detail.AccountDetailScreen
import jp.povo.manager.ui.login.LoginScreen
import jp.povo.manager.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
object AccountsRoute

@Serializable
object LoginRoute

@Serializable
object SettingsRoute

@Serializable
data class AccountDetailRoute(val accountId: String)

/** Development-only; reachable from the overflow menu in debug builds. */
@Serializable
object SpikeRoute

@Composable
fun PovoNavHost(
    navController: NavHostController = rememberNavController(),
    startAccountId: String? = null,
) {
    NavHost(navController, startDestination = AccountsRoute) {
        composable<AccountsRoute> {
            AccountsScreen(
                onAddAccount = { navController.navigate(LoginRoute) },
                onOpenAccount = { navController.navigate(AccountDetailRoute(it)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenSpike = { navController.navigate(SpikeRoute) },
            )
        }
        composable<LoginRoute> {
            LoginScreen(
                onDone = { accountId ->
                    // Drop the wizard from the stack so Back returns to the
                    // list rather than to a completed login.
                    navController.popBackStack()
                    navController.navigate(AccountDetailRoute(accountId))
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        // Registered only in debug builds. BuildConfig.DEBUG is a compile-time
        // constant, so in release this branch — and with it the only reference
        // to the devtools package — is removed outright and R8 drops the
        // screen from the APK rather than merely hiding it.
        if (BuildConfig.DEBUG) {
            composable<SpikeRoute> { ProtocolSpikeScreen() }
        }
        composable<AccountDetailRoute> { entry ->
            val route = entry.toRoute<AccountDetailRoute>()
            AccountDetailScreen(
                accountId = route.accountId,
                onBack = { navController.popBackStack() },
            )
        }
    }

    // A widget tap opens straight to the account it showed.
    if (startAccountId != null) {
        androidx.compose.runtime.LaunchedEffect(startAccountId) {
            navController.navigate(AccountDetailRoute(startAccountId))
        }
    }
}
