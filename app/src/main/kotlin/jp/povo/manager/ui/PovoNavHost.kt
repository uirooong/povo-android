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
import jp.povo.manager.core.model.PovoWebPageKind
import jp.povo.manager.ui.web.PovoWebHost
import jp.povo.manager.ui.settings.SettingsScreen
import jp.povo.manager.ui.store.ToppingStoreScreen
import kotlinx.serialization.Serializable

@Serializable
object AccountsRoute

@Serializable
object LoginRoute

@Serializable
object SettingsRoute

@Serializable
data class AccountDetailRoute(val accountId: String)

/**
 * One of povo's own account pages for one account.
 *
 * [kind] is carried as the enum's name rather than the enum: the navigation
 * argument has to survive being written into a URL, and a name is the stable
 * form of that. The link itself is looked up from storage rather than routed,
 * so it cannot be forged into the route.
 */
@Serializable
data class PovoWebRoute(val accountId: String, val kind: String)

/** povo's topping catalogue for one account. */
@Serializable
data class ToppingStoreRoute(val accountId: String)

/** Development-only; reachable from the overflow menu in debug builds. */
@Serializable
object SpikeRoute

@Composable
fun PovoNavHost(
    navController: NavHostController = rememberNavController(),
    startAccountId: String? = null,
    onStartAccountHandled: () -> Unit = {},
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
                onOpenWebPage = { kind ->
                    navController.navigate(PovoWebRoute(route.accountId, kind.name))
                },
                onOpenStore = { navController.navigate(ToppingStoreRoute(route.accountId)) },
            )
        }
        composable<ToppingStoreRoute> { entry ->
            ToppingStoreScreen(
                accountId = entry.toRoute<ToppingStoreRoute>().accountId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<PovoWebRoute> { entry ->
            val route = entry.toRoute<PovoWebRoute>()
            val kind = runCatching { PovoWebPageKind.valueOf(route.kind) }.getOrNull()
            // An unknown kind can only come from a route this app did not
            // build, so there is nothing to show; going back is the honest
            // response rather than an error screen.
            if (kind == null) {
                androidx.compose.runtime.LaunchedEffect(route.kind) { navController.popBackStack() }
            } else {
                PovoWebHost(
                    accountId = route.accountId,
                    kind = kind,
                    onDone = { navController.popBackStack() },
                )
            }
        }
    }

    // A notification tap opens straight to the account it is about. Cleared
    // once acted on, so returning to the list does not bounce back here.
    if (startAccountId != null) {
        androidx.compose.runtime.LaunchedEffect(startAccountId) {
            navController.navigate(AccountDetailRoute(startAccountId))
            onStartAccountHandled()
        }
    }
}
