package com.droidective.core.apps.bundles

/**
 * What installing a bundle on this phone will do: which of its APKs go in, and what it carries that
 * this install leaves out.
 *
 * @property candidates every APK in the bundle that could be installed.
 * @property selection the ones that will be, chosen for this phone.
 * @property obbFiles expansion files the bundle carries, which this install doesn't place.
 */
data class InstallPlan(
    val format: AppPackageFormat,
    val candidates: List<String>,
    val selection: SplitSelection,
    val obbFiles: List<String>,
    val manifest: AppBundleManifest?,
    /** The API level a bundletool `.apks` has no variant for, when it has none. */
    val noVariantFor: Int? = null,
) {
    /** Why it can't go in, or null when it can. */
    val problem: String?
        get() =
            when {
                noVariantFor != null ->
                    "This ${format.displayName} has no build for this phone's Android version " +
                        "(API $noVariantFor)."
                candidates.isEmpty() -> "There's no APK in this ${format.displayName}."
                selection.abiUnmatched ->
                    "This ${format.displayName} has no native code for this phone's processor, " +
                        "so the app would crash when it starts."
                else -> null
            }

    companion object {
        /**
         * Plans the install of a bundle whose archive holds [entries], on a phone running API
         * [sdk]. bundletool's `.apks` builds a variant per range of Android versions, its [toc]
         * says which files belong to which, and only the phone's variant applies; an `.xapk`'s
         * manifest lists its splits; everything else is the APKs at the archive's root.
         */
        fun of(
            format: AppPackageFormat,
            entries: List<String>,
            manifest: AppBundleManifest?,
            spec: DeviceSpec,
            sdk: Int,
            toc: List<ApksVariant>? = null,
        ): InstallPlan {
            val apks = entries.filter { it.lowercase().endsWith(".apk") }
            val variant =
                toc?.takeIf { format == AppPackageFormat.Apks }?.let { ApksToc.pick(it, sdk) }
            val candidates =
                when {
                    variant != null ->
                        variant.paths.filter { it in entries && it.startsWith("splits/") }
                    toc != null && format == AppPackageFormat.Apks -> emptyList()
                    format == AppPackageFormat.Apks && apks.any { it.startsWith("splits/") } ->
                        apks.filter { it.startsWith("splits/") }
                    format == AppPackageFormat.Xapk && manifest?.splitFiles?.isNotEmpty() == true ->
                        manifest.splitFiles.filter { it in entries }
                    else -> apks.filter { '/' !in it }
                }
            return InstallPlan(
                format = format,
                candidates = candidates,
                selection = select(candidates, spec, variant),
                obbFiles = entries.filter { it.lowercase().endsWith(".obb") },
                manifest = manifest,
                noVariantFor =
                    sdk.takeIf {
                        toc != null && format == AppPackageFormat.Apks && variant == null
                    },
            )
        }

        /**
         * A variant numbered n > 0 names its files `base-<qualifier>_<n+1>.apk`. They're classified
         * by the name without that suffix: stripping any trailing `_<digits>` would turn `x86_64`
         * into `x86`.
         */
        private fun select(
            files: List<String>,
            spec: DeviceSpec,
            variant: ApksVariant?,
        ): SplitSelection {
            val suffix = variant?.number?.takeIf { it > 0 }?.let { "_${it + 1}.apk" }
            if (suffix == null) return SplitApkSelector.select(files, spec)
            val byName = files.associateBy {
                if (it.endsWith(suffix)) it.removeSuffix(suffix) + ".apk" else it
            }
            val chosen = SplitApkSelector.select(byName.keys.toList(), spec)
            return chosen.copy(files = chosen.files.map { byName.getValue(it) })
        }
    }
}
