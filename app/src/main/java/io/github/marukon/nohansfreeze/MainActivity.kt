package io.github.marukon.nohansfreeze

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors
import java.util.zip.ZipFile

class MainActivity : AppCompatActivity() {

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val icon: Drawable,
        val isSystem: Boolean,
        val isXposedModule: Boolean,
        val isRecommended: Boolean
    )

    private lateinit var tvStatus: TextView
    private lateinit var btnAllowAllXposed: MaterialButton
    private lateinit var btnAllowRecommended: MaterialButton
    private lateinit var etSearch: EditText
    private lateinit var swOnlyXposed: MaterialSwitch
    private lateinit var swSystemApps: MaterialSwitch
    private lateinit var rvApps: RecyclerView
    private lateinit var layoutLoading: View

    private var xposedService: XposedService? = null
    private var remotePrefs: SharedPreferences? = null
    private var whitelistedPackages: MutableSet<String> = mutableSetOf()
    private var allApps: List<AppEntry> = emptyList()
    private val displayedApps: MutableList<AppEntry> = mutableListOf()
    private var appsLoaded = false
    private val executor = Executors.newSingleThreadExecutor()

    private val adapter = AppAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnAllowAllXposed = findViewById(R.id.btnAllowAllXposed)
        btnAllowRecommended = findViewById(R.id.btnAllowRecommended)
        etSearch = findViewById(R.id.etSearch)
        swOnlyXposed = findViewById(R.id.swOnlyXposed)
        swSystemApps = findViewById(R.id.swSystemApps)
        rvApps = findViewById(R.id.rvApps)
        layoutLoading = findViewById(R.id.layoutLoading)

        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter
        layoutLoading.visibility = View.VISIBLE
        rvApps.visibility = View.GONE

        tvStatus.text = getString(R.string.status_disconnected)
        btnAllowAllXposed.text = getString(R.string.btn_allow_all_xposed, 0)
        btnAllowAllXposed.setOnClickListener { allowAllXposedModules() }
        btnAllowRecommended.text = getString(R.string.btn_allow_recommended, 0)
        btnAllowRecommended.setOnClickListener { allowRecommendedApps() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilter()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        swOnlyXposed.setOnCheckedChangeListener { _, _ -> applyFilter() }
        swSystemApps.setOnCheckedChangeListener { _, _ -> applyFilter() }

        registerLSPosedService()
        loadInstalledApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun registerLSPosedService() {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                val prefs = service.getRemotePreferences(MainHook.PREF_NAME)
                remotePrefs = prefs
                val saved = prefs.getStringSet(MainHook.KEY_WHITELIST_PKGS, emptySet()) ?: emptySet()
                runOnUiThread {
                    whitelistedPackages = saved.toMutableSet()
                    updateStatusText()
                    applyFilter()
                }
            }

            override fun onServiceDied(service: XposedService) {
                xposedService = null
                remotePrefs = null
                runOnUiThread {
                    tvStatus.text = getString(R.string.status_disconnected)
                }
            }
        })
    }

    private fun updateStatusText() {
        val service = xposedService
        tvStatus.text = if (service != null) {
            getString(R.string.status_connected, service.apiVersion, whitelistedPackages.size)
        } else {
            getString(R.string.status_disconnected)
        }
    }

    private fun detectXposedModule(info: ApplicationInfo): Boolean {
        if (info.metaData?.containsKey("xposedmodule") == true) {
            return true
        }
        val sourceDir = info.sourceDir ?: return false
        return runCatching {
            ZipFile(sourceDir).use { zip ->
                zip.getEntry("META-INF/xposed/module.prop") != null ||
                    zip.getEntry("assets/xposed_init") != null
            }
        }.getOrDefault(false)
    }

    private fun loadInstalledApps() {
        executor.execute {
            val pm = packageManager
            val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_ALL
            val packages = pm.getInstalledApplications(flags)
            val entries = packages
                .filter { it.packageName != packageName }
                .map { info ->
                    val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isXposed = detectXposedModule(info)
                    AppEntry(
                        label = pm.getApplicationLabel(info).toString(),
                        packageName = info.packageName,
                        icon = pm.getApplicationIcon(info),
                        isSystem = isSys,
                        isXposedModule = isXposed,
                        isRecommended = RECOMMENDED_PACKAGES.contains(info.packageName)
                    )
                }

            val xposedCount = entries.count { it.isXposedModule }
            val recommendedCount = entries.count { it.isRecommended }
            runOnUiThread {
                allApps = entries
                appsLoaded = true
                btnAllowAllXposed.text = getString(R.string.btn_allow_all_xposed, xposedCount)
                btnAllowRecommended.text = getString(R.string.btn_allow_recommended, recommendedCount)
                layoutLoading.visibility = View.GONE
                rvApps.alpha = 0f
                rvApps.visibility = View.VISIBLE
                rvApps.animate().alpha(1f).setDuration(180L).start()
                applyFilter()
            }
        }
    }

    private fun allowAllXposedModules() {
        val prefs = remotePrefs
        if (prefs == null) {
            Toast.makeText(this, R.string.status_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        val xposedPkgs = allApps.filter { it.isXposedModule }.map { it.packageName }
        if (xposedPkgs.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_xposed_found, Toast.LENGTH_SHORT).show()
            return
        }
        whitelistedPackages.addAll(xposedPkgs)
        prefs.edit().putStringSet(MainHook.KEY_WHITELIST_PKGS, whitelistedPackages.toSet()).apply()
        updateStatusText()
        applyFilter()
        Toast.makeText(
            this,
            getString(R.string.toast_xposed_whitelisted, xposedPkgs.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun allowRecommendedApps() {
        val prefs = remotePrefs
        if (prefs == null) {
            Toast.makeText(this, R.string.status_disconnected, Toast.LENGTH_SHORT).show()
            return
        }
        val recommendedPkgs = allApps.filter { it.isRecommended }.map { it.packageName }
        if (recommendedPkgs.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_recommended_found, Toast.LENGTH_SHORT).show()
            return
        }
        whitelistedPackages.addAll(recommendedPkgs)
        prefs.edit().putStringSet(MainHook.KEY_WHITELIST_PKGS, whitelistedPackages.toSet()).apply()
        updateStatusText()
        applyFilter()
        Toast.makeText(
            this,
            getString(R.string.toast_recommended_whitelisted, recommendedPkgs.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyFilter() {
        if (!appsLoaded) return
        val query = etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val showSystem = swSystemApps.isChecked
        val onlyXposed = swOnlyXposed.isChecked

        val filtered = allApps
            .filter { entry ->
                val matchesSystem = showSystem || !entry.isSystem || whitelistedPackages.contains(entry.packageName)
                val matchesXposed = !onlyXposed || entry.isXposedModule
                val matchesQuery = query.isEmpty() ||
                    entry.label.lowercase().contains(query) ||
                    entry.packageName.lowercase().contains(query)
                matchesSystem && matchesXposed && matchesQuery
            }
            .sortedWith(
                compareByDescending<AppEntry> { whitelistedPackages.contains(it.packageName) }
                    .thenByDescending { it.isRecommended }
                    .thenByDescending { it.isXposedModule }
                    .thenBy { it.label.lowercase() }
            )

        displayedApps.clear()
        displayedApps.addAll(filtered)
        adapter.notifyDataSetChanged()
    }

    private fun togglePackage(pkgName: String, enabled: Boolean) {
        val prefs = remotePrefs
        if (prefs == null) {
            Toast.makeText(this, R.string.status_disconnected, Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
            return
        }
        if (enabled) {
            whitelistedPackages.add(pkgName)
        } else {
            whitelistedPackages.remove(pkgName)
        }
        prefs.edit().putStringSet(MainHook.KEY_WHITELIST_PKGS, whitelistedPackages.toSet()).apply()
        updateStatusText()
        applyFilter()
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
            val tvAppName: TextView = view.findViewById(R.id.tvAppName)
            val tvRecommendBadge: TextView = view.findViewById(R.id.tvRecommendBadge)
            val tvXposedBadge: TextView = view.findViewById(R.id.tvXposedBadge)
            val tvPackageName: TextView = view.findViewById(R.id.tvPackageName)
            val swWhitelist: MaterialSwitch = view.findViewById(R.id.swWhitelist)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = displayedApps.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = displayedApps[position]
            holder.ivIcon.setImageDrawable(item.icon)
            holder.tvAppName.text = item.label
            holder.tvRecommendBadge.visibility = if (item.isRecommended) View.VISIBLE else View.GONE
            holder.tvXposedBadge.visibility = if (item.isXposedModule) View.VISIBLE else View.GONE
            holder.tvPackageName.text = item.packageName

            holder.swWhitelist.setOnCheckedChangeListener(null)
            holder.swWhitelist.isChecked = whitelistedPackages.contains(item.packageName)
            holder.swWhitelist.setOnCheckedChangeListener { _, isChecked ->
                togglePackage(item.packageName, isChecked)
            }

            holder.itemView.setOnClickListener {
                holder.swWhitelist.toggle()
            }
        }
    }

    companion object {
        private val RECOMMENDED_PACKAGES = setOf(
            "com.azure.authenticator",
            "com.microsoft.skydrive",
            "com.microsoft.emmx",
            "com.microsoft.office.outlook",
            "com.microsoft.teams",
            "com.google.android.apps.authenticator2",
            "org.frknkrc44.hma_oss",
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "org.telegram.messenger.web"
        )
    }
}
