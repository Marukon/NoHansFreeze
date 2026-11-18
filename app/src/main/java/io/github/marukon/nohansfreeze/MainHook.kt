package io.github.marukon.nohansfreeze

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule() {

    @Volatile
    private var whitelist: Set<String> = emptySet()

    @Volatile
    private var getPkgNameMethod: Method? = null

    private var remotePrefs: SharedPreferences? = null
    private val hooked = AtomicBoolean(false)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == KEY_WHITELIST_PKGS || key == null) {
            refreshWhitelist(prefs)
        }
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        initPreferences()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        logBoth(Log.INFO, "onSystemServerStarting triggered")
        initPreferences()
        hookHansFramework(param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        if (param.packageName == "system" || param.packageName == "android") {
            logBoth(Log.INFO, "onPackageReady(${param.packageName}) triggered")
            initPreferences()
            hookHansFramework(param.classLoader)
        }
    }

    private fun logBoth(priority: Int, msg: String, tr: Throwable? = null) {
        if (!ENABLE_LOGGING) return
        if (tr != null) {
            log(priority, TAG, msg, tr)
            Log.println(priority, TAG, "$msg\n${Log.getStackTraceString(tr)}")
        } else {
            log(priority, TAG, msg)
            Log.println(priority, TAG, msg)
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean = true

    override fun onHotReloaded(param: HotReloadedParam) {
        super.onHotReloaded(param)
        initPreferences()
    }

    private fun initPreferences() {
        if (remotePrefs != null) return
        runCatching {
            val prefs = getRemotePreferences(PREF_NAME)
            remotePrefs = prefs
            refreshWhitelist(prefs)
            prefs.registerOnSharedPreferenceChangeListener(prefListener)
        }.onFailure {
            logBoth(Log.WARN, "Failed to init remote preferences", it)
        }
    }

    private fun refreshWhitelist(prefs: SharedPreferences) {
        val loaded = prefs.getStringSet(KEY_WHITELIST_PKGS, emptySet())?.toSet() ?: emptySet()
        whitelist = loaded
        logBoth(Log.INFO, "Whitelist refreshed, count=${loaded.size}: $loaded")
    }

    private fun extractPkgName(hansPackage: Any?): String? {
        if (hansPackage == null) return null
        val cached = getPkgNameMethod
        if (cached != null) {
            return runCatching { cached.invoke(hansPackage) as? String }.getOrNull()
        }
        return runCatching {
            val method = hansPackage.javaClass.getMethod("getPkgName")
            getPkgNameMethod = method
            method.invoke(hansPackage) as? String
        }.getOrNull()
    }

    private fun isWhitelisted(pkgName: String?): Boolean {
        if (pkgName.isNullOrEmpty()) return false
        return whitelist.contains(pkgName)
    }

    private fun hookHansFramework(classLoader: ClassLoader) {
        if (!hooked.compareAndSet(false, true)) return

        val hansPkgClass = runCatching {
            classLoader.loadClass("com.android.server.hans.OplusHansPackage")
        }.onFailure {
            logBoth(Log.ERROR, "Failed to load OplusHansPackage", it)
            hooked.set(false)
        }.getOrNull() ?: return

        runCatching {
            val importanceClass = classLoader.loadClass("com.android.server.hans.OplusHansImportance")
            listOf(
                importanceClass.getDeclaredMethod(
                    "isImportantPkg",
                    hansPkgClass,
                    Int::class.javaPrimitiveType
                ),
                importanceClass.getDeclaredMethod(
                    "isImportantPkg",
                    hansPkgClass,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                ),
                importanceClass.getDeclaredMethod(
                    "isImportantPkg",
                    hansPkgClass,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java
                )
            ).forEach { method ->
                hook(method).intercept { chain ->
                    val pkgName = extractPkgName(chain.getArg(0))
                    if (isWhitelisted(pkgName)) {
                        return@intercept true
                    }
                    chain.proceed()
                }
            }
            logBoth(Log.INFO, "Hooked OplusHansImportance.isImportantPkg (all 3 overloads)")
        }.onFailure {
            logBoth(Log.ERROR, "Failed to hook OplusHansImportance.isImportantPkg", it)
        }

        runCatching {
            val cgroupClass = classLoader.loadClass("com.android.server.hans.freeze.HansCGroup")
            val freezeMethod = cgroupClass.getDeclaredMethod(
                "hansFreezeLocked",
                hansPkgClass,
                String::class.java
            )
            hook(freezeMethod).intercept { chain ->
                val pkgName = extractPkgName(chain.getArg(0))
                if (isWhitelisted(pkgName)) {
                    logBoth(Log.INFO, "Blocked hansFreezeLocked for $pkgName")
                    return@intercept false
                }
                chain.proceed()
            }
            logBoth(Log.INFO, "Hooked HansCGroup.hansFreezeLocked")
        }.onFailure {
            logBoth(Log.ERROR, "Failed to hook HansCGroup.hansFreezeLocked", it)
        }

        runCatching {
            val restrictionClass = classLoader.loadClass("com.android.server.hans.OplusHansRestriction")
            val strictModeMethod = restrictionClass.getDeclaredMethod(
                "isBlockedForStrictMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            hook(strictModeMethod).intercept { chain ->
                val pkgName = chain.getArg(4) as? String
                if (isWhitelisted(pkgName)) {
                    return@intercept false
                }
                chain.proceed()
            }
            logBoth(Log.INFO, "Hooked OplusHansRestriction.isBlockedForStrictMode")
        }.onFailure {
            logBoth(Log.ERROR, "Failed to hook OplusHansRestriction.isBlockedForStrictMode", it)
        }
    }

    companion object {
        private const val TAG = "NoHansFreeze"
        private const val ENABLE_LOGGING = false
        const val PREF_NAME = "nohans_whitelist"
        const val KEY_WHITELIST_PKGS = "whitelist_pkgs"
    }
}
